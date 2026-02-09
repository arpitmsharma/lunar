package com.lunar.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventParserTest {

    @Test
    fun `parse RocketLaunched with valid payload`() {
        val payload = buildJsonObject {
            put("type", "Falcon-9")
            put("launchSpeed", 500)
            put("mission", "ARTEMIS")
        }
        val event = EventParser.parse("RocketLaunched", payload)
        assertInstanceOf(RocketEvent.Launched::class.java, event)
        val launched = event as RocketEvent.Launched
        assertEquals("Falcon-9", launched.type)
        assertEquals(500, launched.launchSpeed)
        assertEquals("ARTEMIS", launched.mission)
    }

    @Test
    fun `parse RocketSpeedIncreased with valid payload`() {
        val payload = buildJsonObject { put("by", 100) }
        val event = EventParser.parse("RocketSpeedIncreased", payload)
        assertInstanceOf(RocketEvent.SpeedIncreased::class.java, event)
        assertEquals(100, (event as RocketEvent.SpeedIncreased).by)
    }

    @Test
    fun `parse RocketSpeedDecreased with valid payload`() {
        val payload = buildJsonObject { put("by", 50) }
        val event = EventParser.parse("RocketSpeedDecreased", payload)
        assertInstanceOf(RocketEvent.SpeedDecreased::class.java, event)
        assertEquals(50, (event as RocketEvent.SpeedDecreased).by)
    }

    @Test
    fun `parse RocketMissionChanged with valid payload`() {
        val payload = buildJsonObject { put("newMission", "LUNA-2") }
        val event = EventParser.parse("RocketMissionChanged", payload)
        assertInstanceOf(RocketEvent.MissionChanged::class.java, event)
        assertEquals("LUNA-2", (event as RocketEvent.MissionChanged).newMission)
    }

    @Test
    fun `parse RocketExploded with valid payload`() {
        val payload = buildJsonObject { put("reason", "ENGINE_FAILURE") }
        val event = EventParser.parse("RocketExploded", payload)
        assertInstanceOf(RocketEvent.Exploded::class.java, event)
        assertEquals("ENGINE_FAILURE", (event as RocketEvent.Exploded).reason)
    }

    @Test
    fun `parse RocketLaunched with missing type throws meaningful error`() {
        val payload = buildJsonObject {
            put("launchSpeed", 500)
            put("mission", "ARTEMIS")
        }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketLaunched", payload)
        }
        assertEquals("Missing 'type' in RocketLaunched event", ex.message)
    }

    @Test
    fun `parse RocketLaunched with missing launchSpeed throws meaningful error`() {
        val payload = buildJsonObject {
            put("type", "Falcon-9")
            put("mission", "ARTEMIS")
        }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketLaunched", payload)
        }
        assertEquals("Missing 'launchSpeed' in RocketLaunched event", ex.message)
    }

    @Test
    fun `parse RocketLaunched with missing mission throws meaningful error`() {
        val payload = buildJsonObject {
            put("type", "Falcon-9")
            put("launchSpeed", 500)
        }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketLaunched", payload)
        }
        assertEquals("Missing 'mission' in RocketLaunched event", ex.message)
    }

    @Test
    fun `parse RocketSpeedIncreased with missing by throws meaningful error`() {
        val payload = buildJsonObject { }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketSpeedIncreased", payload)
        }
        assertEquals("Missing 'by' in RocketSpeedIncreased event", ex.message)
    }

    @Test
    fun `parse RocketSpeedDecreased with missing by throws meaningful error`() {
        val payload = buildJsonObject { }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketSpeedDecreased", payload)
        }
        assertEquals("Missing 'by' in RocketSpeedDecreased event", ex.message)
    }

    @Test
    fun `parse RocketMissionChanged with missing newMission throws meaningful error`() {
        val payload = buildJsonObject { }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketMissionChanged", payload)
        }
        assertEquals("Missing 'newMission' in RocketMissionChanged event", ex.message)
    }

    @Test
    fun `parse RocketExploded with missing reason throws meaningful error`() {
        val payload = buildJsonObject { }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketExploded", payload)
        }
        assertEquals("Missing 'reason' in RocketExploded event", ex.message)
    }

    @Test
    fun `parse unknown message type throws meaningful error`() {
        val payload = buildJsonObject { }
        val ex = assertThrows<IllegalArgumentException> {
            EventParser.parse("RocketTeleported", payload)
        }
        assertEquals("Unknown message type: RocketTeleported", ex.message)
    }
}
