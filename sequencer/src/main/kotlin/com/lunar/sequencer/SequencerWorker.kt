package com.lunar.sequencer

import com.lunar.sequencer.model.SequencedMessage
import io.lettuce.core.Consumer
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class SequencerWorker(
    private val connection: StatefulRedisConnection<String, String>,
    private val config: SequencerConfig,
    private val stashManager: StashManager
) {
    private val logger = LoggerFactory.getLogger(SequencerWorker::class.java)
    private val sync: RedisCommands<String, String> = connection.sync()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val NEXT_KEY_PREFIX = "lunar:next:"
    }

    /**
     * Start sequencing messages from the incoming stream
     */
    fun start(): Flow<SequencedMessage> = flow {
        try {
            withContext(Dispatchers.IO) {
                sync.xgroupCreate(
                    XReadArgs.StreamOffset.from(config.incomingStream, "0-0"),
                    config.consumerGroupName,
                    io.lettuce.core.XGroupCreateArgs.Builder.mkstream()
                )
            }
            logger.info("Created consumer group ${config.consumerGroupName}")
        } catch (e: Exception) {
            logger.debug("Consumer group ${config.consumerGroupName} already exists: ${e.message}")
        }

        while (true) {
            try {
                val messages = withContext(Dispatchers.IO) {
                    sync.xreadgroup(
                        Consumer.from(config.consumerGroupName, config.consumerName),
                        XReadArgs.Builder.block(config.blockMillis).count(config.batchSize.toLong()),
                        XReadArgs.StreamOffset.lastConsumed(config.incomingStream)
                    )
                }

                if (messages != null && messages.isNotEmpty()) {
                    for (streamMessage in messages) {
                        val messageId = streamMessage.id
                        val bodyMap = streamMessage.body

                        try {
                            val data = bodyMap["data"]
                            if (data != null) {
                                val sequencedMsg = json.decodeFromString<SequencedMessage>(data)
                                processMessage(sequencedMsg) { msg ->
                                    emit(msg)
                                }

                                // ACK the message
                                withContext(Dispatchers.IO) {
                                    sync.xack(streamMessage.stream, config.consumerGroupName, messageId)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error processing message $messageId", e)
                            handleFailedMessage(messageId, bodyMap["data"] ?: "", streamMessage.stream)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Error reading from stream", e)
                delay(1000)
            }
        }
    }

    /**
     * Process a message using stash-and-drain algorithm
     * Emits messages via the provided emit function
     */
    private suspend fun processMessage(message: SequencedMessage, emit: suspend (SequencedMessage) -> Unit) {
        val key = message.key
        val msgNum = message.messageNumber

        // Get next expected sequence (use channel as string for Redis key)
        val nextKey = "$NEXT_KEY_PREFIX$key"
        val next = withContext(Dispatchers.IO) {
            // Atomically initialize counter if it doesn't exist
            val setResult = sync.setnx(nextKey, "1")
            if (setResult) {
                // We successfully initialized it
                1
            } else {
                // Another worker initialized it, get the current value
                sync.get(nextKey)?.toIntOrNull() ?: 1
            }
        }

        logger.debug("Processing message $msgNum for channel $key (next expected: $next)")

        when {
            msgNum < next -> {
                // Duplicate - skip
                logger.debug("Duplicate message $msgNum for $key (already processed)")
            }
            msgNum == next -> {
                // In-order - emit first, then increment counter and drain
                logger.debug("In-order message $msgNum for $key")
                emit(message)
                val newNext = withContext(Dispatchers.IO) {
                    sync.incr(nextKey)
                }
                logger.debug("Incremented next for $key from $next to $newNext")
                drainStash(key.toString(), next + 1, emit)
            }
            msgNum > next -> {
                // Out-of-order - stash
                logger.debug("Out-of-order message $msgNum for $key (waiting for $next)")
                stashManager.stash(key.toString(), msgNum, json.encodeToString(message))
            }
        }
    }

    /**
     * Drain stashed messages that are now in sequence
     * Emits drained messages via the provided emit function
     */
    private suspend fun drainStash(channel: String, startSeq: Int, emit: suspend (SequencedMessage) -> Unit) {
        var currentSeq = startSeq
        val nextKey = "$NEXT_KEY_PREFIX$channel"

        while (true) {
            val stashed = stashManager.drain(channel, currentSeq, currentSeq).firstOrNull()
            if (stashed == null) break

            logger.debug("Draining message $currentSeq from stash for channel $channel")

            try {
                val message = json.decodeFromString<SequencedMessage>(stashed)
                emit(message)

                withContext(Dispatchers.IO) {
                    sync.incr(nextKey)
                }

                stashManager.remove(channel, currentSeq)
                currentSeq++
            } catch (e: Exception) {
                logger.error("Failed to drain message $currentSeq for channel $channel", e)
                break
            }
        }

        if (currentSeq > startSeq) {
            logger.info("Drained ${currentSeq - startSeq} messages from stash for channel $channel")
        }
    }

    /**
     * Handle a message that failed processing after retries
     */
    private suspend fun handleFailedMessage(entryId: String, data: String, stream: String) {
        try {
            if (data.isBlank()) {
                logger.error("Empty message data for $entryId, acking")
                withContext(Dispatchers.IO) {
                    sync.xack(stream, config.consumerGroupName, entryId)
                }
                return
            }

            val message = json.decodeFromString<SequencedMessage>(data)
            val newRetryCount = message.retryCount + 1

            if (newRetryCount >= config.maxRetries) {
                // Move to dead letter queue
                logger.error("Message ${message.key}:${message.messageNumber} failed after $newRetryCount retries, moving to dead letter")

                val updatedMessage = message.copy(retryCount = newRetryCount)
                withContext(Dispatchers.IO) {
                    sync.xadd(
                        config.deadLetterStream,
                        mapOf(
                            "data" to json.encodeToString(updatedMessage),
                            "originalEntryId" to entryId,
                            "reason" to "max_retries_exceeded"
                        )
                    )

                    // ACK the failed message
                    sync.xack(stream, config.consumerGroupName, entryId)
                }
            } else {
                logger.warn("Message ${message.key}:${message.messageNumber} failed, retry count: $newRetryCount")
                // Don't ACK - it will be retried
            }
        } catch (e: Exception) {
            logger.error("Error handling failed message $entryId", e)
            // ACK it anyway to avoid infinite loop
            withContext(Dispatchers.IO) {
                sync.xack(stream, config.consumerGroupName, entryId)
            }
        }
    }
}
