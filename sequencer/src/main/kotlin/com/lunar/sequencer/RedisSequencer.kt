package com.lunar.sequencer

import com.lunar.sequencer.model.SequencedMessage
import kotlinx.coroutines.flow.Flow

interface RedisSequencer {
    /**
     * Publish a message to the incoming stream for sequencing
     * @return Stream entry ID
     */
    suspend fun publish(message: SequencedMessage): String

    /**
     * Start sequencing messages from the incoming stream to the ordered stream
     * @return Flow of sequenced messages ready for processing
     */
    fun startSequencing(): Flow<SequencedMessage>

    /**
     * Stop the sequencer and clean up resources
     */
    suspend fun stop()
}
