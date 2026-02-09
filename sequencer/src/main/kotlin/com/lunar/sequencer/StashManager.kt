package com.lunar.sequencer

import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Manages stashed out-of-order messages using Redis sorted sets.
 *
 * This manager is designed to work with messages implementing the
 * [Sequenceable] contract, which defines key and messageNumber properties.
 *
 * Messages are stored as serialized strings with their sequence number
 * as the sort score, enabling efficient retrieval by sequence range.
 */
class StashManager(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val logger = LoggerFactory.getLogger(StashManager::class.java)
    private val sync: RedisCommands<String, String> = connection.sync()

    companion object {
        private const val STASH_KEY_PREFIX = "lunar:stash:"
    }

    /**
     * Stash an out-of-order message for later processing
     */
    suspend fun stash(channel: String, messageNumber: Int, serializedMessage: String) {
        val key = "$STASH_KEY_PREFIX$channel"
        withContext(Dispatchers.IO) {
            sync.zadd(key, messageNumber.toDouble(), serializedMessage)
        }
        logger.debug("Stashed message $messageNumber for channel $channel")
    }

    /**
     * Drain messages from stash in sequence order
     * @return List of serialized messages in the specified range
     */
    suspend fun drain(channel: String, fromSeq: Int, toSeq: Int): List<String> {
        val key = "$STASH_KEY_PREFIX$channel"
        val results = withContext(Dispatchers.IO) {
            sync.zrangebyscore(key, fromSeq.toDouble(), toSeq.toDouble())
        }
        return results ?: emptyList()
    }

    /**
     * Remove a specific message from the stash after processing
     */
    suspend fun remove(channel: String, messageNumber: Int) {
        val key = "$STASH_KEY_PREFIX$channel"
        // We need to remove by score, but ZREM needs the actual member
        // So we'll use ZRANGEBYSCORE to get the member first
        val members = withContext(Dispatchers.IO) {
            sync.zrangebyscore(key, messageNumber.toDouble(), messageNumber.toDouble())
        }
        members?.forEach { member ->
            withContext(Dispatchers.IO) {
                sync.zrem(key, member)
            }
            logger.debug("Removed message $messageNumber from stash for channel $channel")
        }
    }

    /**
     * Get the count of stashed messages for a channel
     */
    suspend fun count(channel: String): Long {
        val key = "$STASH_KEY_PREFIX$channel"
        return withContext(Dispatchers.IO) {
            sync.zcard(key)
        }
    }
}
