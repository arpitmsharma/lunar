package com.lunar.consumer

import com.lunar.model.IncomingMessage
import com.lunar.model.Metadata
import com.lunar.sequencer.RedisSequencer
import com.lunar.service.RocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class OrderedMessageConsumer(
    private val redisSequencer: RedisSequencer,
    private val rocketService: RocketService,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(OrderedMessageConsumer::class.java)
    private var consumerJob: Job? = null

    fun start() {
        logger.info("Starting ordered message consumer")
        consumerJob = scope.launch {
            redisSequencer.startSequencing()
                .catch { e ->
                    logger.error("Error in sequencing flow", e)
                }
                .collect { sequencedMessage ->
                    try {
                        // Convert back to IncomingMessage
                        val incoming = IncomingMessage(
                            metadata = Metadata(
                                channel = sequencedMessage.key.toString(),
                                messageNumber = sequencedMessage.messageNumber,
                                messageTime = sequencedMessage.messageTime,
                                messageType = sequencedMessage.messageType
                            ),
                            message = sequencedMessage.payload
                        )

                        rocketService.applyMessage(incoming)

                    } catch (e: Exception) {
                        logger.error("Failed to process message ${sequencedMessage.key}:${sequencedMessage.messageNumber}", e)
                        // Don't rethrow - continue processing other messages
                    }
                }
        }
    }

    suspend fun stop() {
        logger.info("Stopping ordered message consumer")
        consumerJob?.cancelAndJoin()
        redisSequencer.stop()
    }
}
