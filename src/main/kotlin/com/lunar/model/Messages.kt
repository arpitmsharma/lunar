package com.lunar.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class IncomingMessage(
    val metadata: Metadata,
    val message: JsonObject
)

@Serializable
data class Metadata(
    val channel: String,
    val messageNumber: Int,
    val messageTime: String,
    val messageType: String
)

enum class RocketStatus {
    LAUNCHED,
    EXPLODED
}

data class RocketState(
    val channelId: UUID,
    val type: String = "",
    val speed: Int = 0,
    val mission: String = "",
    val status: RocketStatus = RocketStatus.LAUNCHED,
    val explosionReason: String = "",
    val lastApplied: Int = 0
)

sealed interface RocketEvent {
    fun applyTo(state: RocketState): RocketState

    data class Launched(val type: String, val launchSpeed: Int, val mission: String) : RocketEvent {
        override fun applyTo(state: RocketState) = state.copy(
            type = type, speed = launchSpeed, mission = mission, status = RocketStatus.LAUNCHED
        )
    }

    data class SpeedIncreased(val by: Int) : RocketEvent {
        override fun applyTo(state: RocketState) = state.copy(speed = state.speed + by)
    }

    data class SpeedDecreased(val by: Int) : RocketEvent {
        override fun applyTo(state: RocketState) = state.copy(speed = state.speed - by)
    }

    data class MissionChanged(val newMission: String) : RocketEvent {
        override fun applyTo(state: RocketState) = state.copy(mission = newMission)
    }

    data class Exploded(val reason: String) : RocketEvent {
        override fun applyTo(state: RocketState) = state.copy(
            status = RocketStatus.EXPLODED, explosionReason = reason
        )
    }
}

@Serializable
data class RocketResponse(
    val channel: String,
    val type: String,
    val speed: Int,
    val mission: String,
    val status: String,
    @SerialName("explosion_reason") val explosionReason: String = ""
)
