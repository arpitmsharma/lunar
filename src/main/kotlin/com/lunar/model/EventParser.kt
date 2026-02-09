package com.lunar.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

object EventParser {

    // Event type name constants to avoid duplication
    private const val EVENT_LAUNCHED = "RocketLaunched"
    private const val EVENT_SPEED_INCREASED = "RocketSpeedIncreased"
    private const val EVENT_SPEED_DECREASED = "RocketSpeedDecreased"
    private const val EVENT_MISSION_CHANGED = "RocketMissionChanged"
    private const val EVENT_EXPLODED = "RocketExploded"

    fun parse(messageType: String, payload: JsonObject): RocketEvent {
        return when (messageType) {
            EVENT_LAUNCHED -> parseLaunched(payload)
            EVENT_SPEED_INCREASED -> parseSpeedIncreased(payload)
            EVENT_SPEED_DECREASED -> parseSpeedDecreased(payload)
            EVENT_MISSION_CHANGED -> parseMissionChanged(payload)
            EVENT_EXPLODED -> parseExploded(payload)
            else -> throw IllegalArgumentException("Unknown message type: $messageType")
        }
    }

    private fun parseLaunched(payload: JsonObject): RocketEvent.Launched {
        val context = ParserContext(payload, EVENT_LAUNCHED)
        return RocketEvent.Launched(
            type = context.requireString("type"),
            launchSpeed = context.requireInt("launchSpeed"),
            mission = context.requireString("mission")
        )
    }

    private fun parseSpeedIncreased(payload: JsonObject): RocketEvent.SpeedIncreased {
        val context = ParserContext(payload, EVENT_SPEED_INCREASED)
        return RocketEvent.SpeedIncreased(
            by = context.requireInt("by")
        )
    }

    private fun parseSpeedDecreased(payload: JsonObject): RocketEvent.SpeedDecreased {
        val context = ParserContext(payload, EVENT_SPEED_DECREASED)
        return RocketEvent.SpeedDecreased(
            by = context.requireInt("by")
        )
    }

    private fun parseMissionChanged(payload: JsonObject): RocketEvent.MissionChanged {
        val context = ParserContext(payload, EVENT_MISSION_CHANGED)
        return RocketEvent.MissionChanged(
            newMission = context.requireString("newMission")
        )
    }

    private fun parseExploded(payload: JsonObject): RocketEvent.Exploded {
        val context = ParserContext(payload, EVENT_EXPLODED)
        return RocketEvent.Exploded(
            reason = context.requireString("reason")
        )
    }

    /**
     * Parsing context that encapsulates the payload and event type.
     * Eliminates the need to pass eventType to every helper function.
     */
    private class ParserContext(
        private val payload: JsonObject,
        private val eventType: String
    ) {
        /**
         * Extract a required string field from the payload.
         * @throws IllegalArgumentException if field is missing or not a string
         */
        fun requireString(field: String): String {
            return payload[field]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing '$field' in $eventType event")
        }

        /**
         * Extract a required integer field from the payload.
         * @throws IllegalArgumentException if field is missing or not an integer
         */
        fun requireInt(field: String): Int {
            return payload[field]?.jsonPrimitive?.int
                ?: throw IllegalArgumentException("Missing '$field' in $eventType event")
        }

        /**
         * Extract an optional string field from the payload.
         * Returns null if the field is missing.
         */
        fun optionalString(field: String): String? {
            return payload[field]?.jsonPrimitive?.content
        }

        /**
         * Extract an optional integer field from the payload.
         * Returns null if the field is missing.
         */
        fun optionalInt(field: String): Int? {
            return payload[field]?.jsonPrimitive?.int
        }
    }

}
