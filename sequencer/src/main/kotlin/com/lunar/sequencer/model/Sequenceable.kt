package com.lunar.sequencer.model

import java.util.UUID

/**
 * Interface for messages that can be sequenced.
 * Defines the minimum contract required for stash-and-drain sequencing.
 */
interface Sequenceable {
    /**
     * Unique identifier for the message partition.
     * Messages with the same key are sequenced together.
     */
    val key: UUID

    /**
     * Sequence number within the partition.
     * Used to determine message ordering.
     */
    val messageNumber: Int
}
