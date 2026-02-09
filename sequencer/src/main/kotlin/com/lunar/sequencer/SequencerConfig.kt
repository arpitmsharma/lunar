package com.lunar.sequencer

import java.util.UUID

data class SequencerConfig(
    val redisHost: String = "localhost",
    val redisPort: Int = 6379,
    val incomingStream: String = "lunar:incoming",
    val orderedStream: String = "lunar:ordered",
    val deadLetterStream: String = "lunar:dead-letters",
    val consumerGroupName: String = "sequencer-group",
    val consumerName: String = "sequencer-${UUID.randomUUID()}",
    val batchSize: Int = 50,
    val blockMillis: Long = 1000,
    val maxRetries: Int = 5
)
