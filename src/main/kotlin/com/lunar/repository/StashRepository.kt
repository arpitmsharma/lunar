package com.lunar.repository

import java.util.UUID

data class StashedMessage(
    val channelId: UUID,
    val messageNumber: Int,
    val messageType: String,
    val payload: String
)

interface StashRepository {
    fun exists(channelId: UUID, messageNumber: Int): Boolean
    fun save(channelId: UUID, messageNumber: Int, serializedPayload: String)
    fun findNext(channelId: UUID, messageNumber: Int): StashedMessage?
    fun deleteAll(channelId: UUID, messageNumbers: List<Int>)
}
