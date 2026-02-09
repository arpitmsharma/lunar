package com.lunar.sequencer.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * UUID serializer for the sequencer module.
 */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

@Serializable
data class SequencedMessage(
    @Serializable(with = UUIDSerializer::class)
    override val key: UUID,
    override val messageNumber: Int,
    val messageType: String,
    val messageTime: String,
    val payload: JsonObject,
    val retryCount: Int = 0
) : Sequenceable
