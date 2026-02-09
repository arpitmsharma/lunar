package com.lunar.sequencer

import com.lunar.sequencer.model.SequencedMessage
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class LettuceRedisSequencer(
    private val connection: StatefulRedisConnection<String, String>,
    private val config: SequencerConfig
) : RedisSequencer {
    private val logger = LoggerFactory.getLogger(LettuceRedisSequencer::class.java)
    private val sync: RedisCommands<String, String> = connection.sync()
    private val json = Json { ignoreUnknownKeys = true }
    private val stashManager = StashManager(connection)
    private val worker = SequencerWorker(connection, config, stashManager)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override suspend fun publish(message: SequencedMessage): String {
        val serialized = json.encodeToString(message)
        val entryId = withContext(Dispatchers.IO) {
            sync.xadd(
                config.incomingStream,
                mapOf("data" to serialized)
            )
        }

        logger.debug("Published message ${message.key}:${message.messageNumber} to ${config.incomingStream} with ID $entryId")
        return entryId
    }

    override fun startSequencing(): Flow<SequencedMessage> {
        logger.info("Starting message sequencer")
        return worker.start()
    }

    override suspend fun stop() {
        logger.info("Stopping message sequencer")
        scope.cancel()
    }
}
