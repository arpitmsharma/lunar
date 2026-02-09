package com.lunar.service

import com.lunar.model.IncomingMessage
import com.lunar.model.Metadata
import com.lunar.model.RocketState
import com.lunar.repository.RocketRepository
import com.lunar.repository.StashRepository
import com.lunar.repository.StashedMessage
import com.lunar.repository.TransactionRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class FakeRocketRepository : RocketRepository {
    val rockets = mutableMapOf<UUID, RocketState>()

    override fun lockAndGet(channelId: UUID) = rockets[channelId]

    override fun create(channelId: UUID): RocketState {
        val state = RocketState(channelId = channelId)
        rockets[channelId] = state
        return state
    }

    override fun save(state: RocketState) {
        rockets[state.channelId] = state
    }

    override fun findByChannel(channelId: UUID) = rockets[channelId]

    override fun findAll(sortField: String?, sortOrder: String?): List<RocketState> {
        val list = rockets.values.toList()
        val sorted = when (sortField?.lowercase()) {
            "speed" -> list.sortedBy { it.speed }
            "type" -> list.sortedBy { it.type }
            "channel" -> list.sortedBy { it.channelId.toString() }
            else -> list.sortedBy { it.channelId.toString() }
        }
        return if (sortOrder?.lowercase() == "desc") sorted.reversed() else sorted
    }
}

class FakeStashRepository : StashRepository {
    val stash = mutableMapOf<Pair<UUID, Int>, String>()

    override fun exists(channelId: UUID, messageNumber: Int) =
        stash.containsKey(channelId to messageNumber)

    override fun save(channelId: UUID, messageNumber: Int, serializedPayload: String) {
        stash[channelId to messageNumber] = serializedPayload
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun findNext(channelId: UUID, messageNumber: Int): StashedMessage? {
        val payload = stash[channelId to messageNumber] ?: return null
        val parsed = json.parseToJsonElement(payload).jsonObject
        val messageType = parsed["messageType"]?.jsonPrimitive?.content ?: return null
        return StashedMessage(channelId, messageNumber, messageType, payload)
    }

    override fun deleteAll(channelId: UUID, messageNumbers: List<Int>) {
        messageNumbers.forEach { stash.remove(channelId to it) }
    }
}

class FakeTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = block()
}

class RocketServiceTest {

    private lateinit var rocketRepo: FakeRocketRepository
    private lateinit var stashRepo: FakeStashRepository
    private lateinit var service: RocketService

    companion object {
        val CH1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val CH_SLOW = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CH_FAST = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val CH_MID = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val NONEXISTENT = UUID.fromString("00000000-0000-0000-0000-000000000099")
    }

    @BeforeEach
    fun setup() {
        rocketRepo = FakeRocketRepository()
        stashRepo = FakeStashRepository()
        service = RocketService(rocketRepo, stashRepo, FakeTransactionRunner())
    }

    private fun msg(channel: UUID, num: Int, type: String, payload: JsonObject) =
        IncomingMessage(
            metadata = Metadata(channel = channel.toString(), messageNumber = num, messageTime = "2024-01-01T00:00:00Z", messageType = type),
            message = payload
        )

    private fun launchMsg(channel: UUID, num: Int, speed: Int = 500) =
        msg(channel, num, "RocketLaunched", buildJsonObject {
            put("type", "Falcon-9")
            put("launchSpeed", speed)
            put("mission", "ARTEMIS")
        })

    private fun speedUpMsg(channel: UUID, num: Int, by: Int) =
        msg(channel, num, "RocketSpeedIncreased", buildJsonObject { put("by", by) })

    private fun speedDownMsg(channel: UUID, num: Int, by: Int) =
        msg(channel, num, "RocketSpeedDecreased", buildJsonObject { put("by", by) })

    private fun explodeMsg(channel: UUID, num: Int, reason: String) =
        msg(channel, num, "RocketExploded", buildJsonObject { put("reason", reason) })

    private fun missionMsg(channel: UUID, num: Int, newMission: String) =
        msg(channel, num, "RocketMissionChanged", buildJsonObject { put("newMission", newMission) })

    @Test
    fun `in-order messages apply correctly`() {
        service.processMessage(launchMsg(CH1, 1, speed = 500))
        service.processMessage(speedUpMsg(CH1, 2, 100))

        val rocket = service.getRocket(CH1.toString())!!
        assertEquals(600, rocket.speed)
        assertEquals("Falcon-9", rocket.type)
        assertEquals("ARTEMIS", rocket.mission)
    }

    @Test
    fun `duplicate messages are ignored`() {
        service.processMessage(launchMsg(CH1, 1, speed = 500))
        service.processMessage(speedUpMsg(CH1, 2, 100))
        service.processMessage(speedUpMsg(CH1, 2, 100))

        assertEquals(600, service.getRocket(CH1.toString())!!.speed)
    }

    @Test
    fun `past messages are ignored`() {
        service.processMessage(launchMsg(CH1, 1, speed = 500))
        service.processMessage(speedUpMsg(CH1, 2, 100))
        service.processMessage(launchMsg(CH1, 1, speed = 9999))

        assertEquals(600, service.getRocket(CH1.toString())!!.speed)
    }

    @Test
    fun `out-of-order messages are stashed and drained`() {
        service.processMessage(speedUpMsg(CH1, 3, 200))
        service.processMessage(launchMsg(CH1, 1, speed = 500))

        // Message 3 is stashed, only message 1 applied
        assertEquals(500, service.getRocket(CH1.toString())!!.speed)

        // Now send message 2 — should drain message 3 too
        service.processMessage(speedUpMsg(CH1, 2, 100))

        assertEquals(800, service.getRocket(CH1.toString())!!.speed)
        // Stash should be empty
        assertEquals(0, stashRepo.stash.size)
    }

    @Test
    fun `explosion stops further state changes`() {
        service.processMessage(launchMsg(CH1, 1, speed = 500))
        service.processMessage(explodeMsg(CH1, 2, "ENGINE_FAILURE"))
        service.processMessage(speedUpMsg(CH1, 3, 9999))

        val rocket = service.getRocket(CH1.toString())!!
        assertEquals("exploded", rocket.status)
        assertEquals(500, rocket.speed)
    }

    @Test
    fun `mission change updates mission`() {
        service.processMessage(launchMsg(CH1, 1))
        service.processMessage(missionMsg(CH1, 2, "LUNA-2"))

        assertEquals("LUNA-2", service.getRocket(CH1.toString())!!.mission)
    }

    @Test
    fun `speed decrease can go negative`() {
        service.processMessage(launchMsg(CH1, 1, speed = 100))
        service.processMessage(speedDownMsg(CH1, 2, 200))

        assertEquals(-100, service.getRocket(CH1.toString())!!.speed)
    }

    @Test
    fun `getRocket returns null for unknown channel`() {
        assertNull(service.getRocket(NONEXISTENT.toString()))
    }

    @Test
    fun `getAllRockets returns sorted list`() {
        service.processMessage(launchMsg(CH_SLOW, 1, speed = 100))
        service.processMessage(launchMsg(CH_FAST, 1, speed = 900))
        service.processMessage(launchMsg(CH_MID, 1, speed = 500))

        val rockets = service.getAllRockets("speed", "asc")
        assertEquals(3, rockets.size)
        assertEquals(CH_SLOW.toString(), rockets[0].channel)
        assertEquals(CH_MID.toString(), rockets[1].channel)
        assertEquals(CH_FAST.toString(), rockets[2].channel)
    }

    @Test
    fun `stash does not duplicate entries`() {
        service.processMessage(speedUpMsg(CH1, 3, 200))
        service.processMessage(speedUpMsg(CH1, 3, 200))

        assertEquals(1, stashRepo.stash.size)
    }

    @Test
    fun `creates rocket on first message`() {
        assertNull(rocketRepo.rockets[CH1])

        service.processMessage(launchMsg(CH1, 1))

        assertEquals("Falcon-9", rocketRepo.rockets[CH1]!!.type)
    }

    @Test
    fun `drain loop handles multiple consecutive stashed messages`() {
        // Stash messages 2, 3, 4 first
        service.processMessage(speedUpMsg(CH1, 2, 100))
        service.processMessage(speedUpMsg(CH1, 3, 100))
        service.processMessage(speedUpMsg(CH1, 4, 100))

        assertEquals(3, stashRepo.stash.size)

        // Now send message 1 — should drain 2, 3, 4
        service.processMessage(launchMsg(CH1, 1, speed = 500))

        assertEquals(800, service.getRocket(CH1.toString())!!.speed)
        assertEquals(0, stashRepo.stash.size)
    }
}
