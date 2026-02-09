package com.lunar.db

import com.lunar.repository.StashRepository
import com.lunar.repository.StashedMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.util.UUID

class ExposedStashRepository : StashRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun exists(channelId: UUID, messageNumber: Int): Boolean {
        return Stash.selectAll().where {
            (Stash.channelId eq channelId) and (Stash.messageNumber eq messageNumber)
        }.singleOrNull() != null
    }

    override fun save(channelId: UUID, messageNumber: Int, serializedPayload: String) {
        Stash.insert {
            it[Stash.channelId] = channelId
            it[Stash.messageNumber] = messageNumber
            it[Stash.payload] = serializedPayload
        }
    }

    override fun findNext(channelId: UUID, messageNumber: Int): StashedMessage? {
        val row = Stash.selectAll().where {
            (Stash.channelId eq channelId) and (Stash.messageNumber eq messageNumber)
        }.singleOrNull() ?: return null

        val payloadStr = row[Stash.payload]
        val parsed = json.parseToJsonElement(payloadStr).jsonObject
        val messageType = parsed["messageType"]?.jsonPrimitive?.content ?: return null

        return StashedMessage(
            channelId = channelId,
            messageNumber = messageNumber,
            messageType = messageType,
            payload = payloadStr
        )
    }

    override fun deleteAll(channelId: UUID, messageNumbers: List<Int>) {
        if (messageNumbers.isNotEmpty()) {
            Stash.deleteWhere {
                (Stash.channelId eq channelId) and (Stash.messageNumber inList messageNumbers)
            }
        }
    }
}
