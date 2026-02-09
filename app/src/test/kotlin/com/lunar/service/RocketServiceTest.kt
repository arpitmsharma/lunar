package com.lunar.service

import com.lunar.model.IncomingMessage
import com.lunar.model.Metadata
import com.lunar.model.RocketState
import com.lunar.repository.RocketRepository
import com.lunar.repository.TransactionRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class FakeRocketRepository : RocketRepository {
    val rockets = mutableMapOf<UUID, RocketState>()

    override fun getForUpdate(channelId: UUID) = rockets[channelId]

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
            "channel" -> list.sortedBy { it.channelId }
            else -> list.sortedBy { it.channelId }
        }
        return if (sortOrder?.lowercase() == "desc") sorted.reversed() else sorted
    }
}

class FakeTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = block()
}

class RocketServiceTest {

    companion object {
        val TEST_CHANNEL = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val CH_SLOW = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
        val CH_FAST = UUID.fromString("550e8400-e29b-41d4-a716-446655440002")
        val CH_MID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003")
    }

    private lateinit var rocketRepo: FakeRocketRepository
    private lateinit var service: RocketService

    @BeforeEach
    fun setup() {
        rocketRepo = FakeRocketRepository()
        service = RocketService(rocketRepo, FakeTransactionRunner())
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
    fun `apply messages in sequence correctly`() {
        service.applyMessage(launchMsg(TEST_CHANNEL, 1, speed = 500))
        service.applyMessage(speedUpMsg(TEST_CHANNEL, 2, 100))

        val rocket = service.getRocket(TEST_CHANNEL)!!
        assertEquals(600, rocket.speed)
        assertEquals("Falcon-9", rocket.type)
        assertEquals("ARTEMIS", rocket.mission)
        assertEquals(2, rocketRepo.rockets[TEST_CHANNEL]!!.lastApplied)
    }

    @Test
    fun `explosion stops further state changes`() {
        service.applyMessage(launchMsg(TEST_CHANNEL, 1, speed = 500))
        service.applyMessage(explodeMsg(TEST_CHANNEL, 2, "ENGINE_FAILURE"))
        service.applyMessage(speedUpMsg(TEST_CHANNEL, 3, 9999))

        val rocket = service.getRocket(TEST_CHANNEL)!!
        assertEquals("exploded", rocket.status)
        assertEquals(500, rocket.speed)
    }

    @Test
    fun `mission change updates mission`() {
        service.applyMessage(launchMsg(TEST_CHANNEL, 1))
        service.applyMessage(missionMsg(TEST_CHANNEL, 2, "LUNA-2"))

        assertEquals("LUNA-2", service.getRocket(TEST_CHANNEL)!!.mission)
    }

    @Test
    fun `speed decrease can go negative`() {
        service.applyMessage(launchMsg(TEST_CHANNEL, 1, speed = 100))
        service.applyMessage(speedDownMsg(TEST_CHANNEL, 2, 200))

        assertEquals(-100, service.getRocket(TEST_CHANNEL)!!.speed)
    }

    @Test
    fun `getRocket returns null for unknown channel`() {
        assertNull(service.getRocket(UUID.fromString("00000000-0000-0000-0000-000000000000")))
    }

    @Test
    fun `getAllRockets returns sorted list`() {
        service.applyMessage(launchMsg(CH_SLOW, 1, speed = 100))
        service.applyMessage(launchMsg(CH_FAST, 1, speed = 900))
        service.applyMessage(launchMsg(CH_MID, 1, speed = 500))

        val rockets = service.getAllRockets("speed", "asc")
        assertEquals(3, rockets.size)
        assertEquals(CH_SLOW.toString(), rockets[0].channel)
        assertEquals(CH_MID.toString(), rockets[1].channel)
        assertEquals(CH_FAST.toString(), rockets[2].channel)
    }

    @Test
    fun `creates rocket on first message`() {
        assertNull(rocketRepo.rockets[TEST_CHANNEL])

        service.applyMessage(launchMsg(TEST_CHANNEL, 1))

        assertEquals("Falcon-9", rocketRepo.rockets[TEST_CHANNEL]!!.type)
    }

    @Test
    fun `updates lastApplied correctly`() {
        service.applyMessage(launchMsg(TEST_CHANNEL, 1, speed = 500))
        assertEquals(1, rocketRepo.rockets[TEST_CHANNEL]!!.lastApplied)

        service.applyMessage(speedUpMsg(TEST_CHANNEL, 2, 100))
        assertEquals(2, rocketRepo.rockets[TEST_CHANNEL]!!.lastApplied)

        service.applyMessage(speedUpMsg(TEST_CHANNEL, 3, 100))
        assertEquals(3, rocketRepo.rockets[TEST_CHANNEL]!!.lastApplied)
    }
}
