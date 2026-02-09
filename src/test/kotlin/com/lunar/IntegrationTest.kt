package com.lunar

import com.lunar.db.DatabaseFactory
import com.lunar.model.RocketResponse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:15-alpine").apply {
            withDatabaseName("lunar_test")
            withUsername("test")
            withPassword("test")
        }

        @JvmStatic
        @BeforeAll
        fun setup() {
            DatabaseFactory.init(
                url = postgres.jdbcUrl,
                user = postgres.username,
                password = postgres.password
            )
        }
    }

    private fun buildMessage(
        channel: String,
        messageNumber: Int,
        messageType: String,
        message: JsonObject
    ): JsonObject = buildJsonObject {
        putJsonObject("metadata") {
            put("channel", channel)
            put("messageNumber", messageNumber)
            put("messageTime", "2024-01-01T00:00:00Z")
            put("messageType", messageType)
        }
        put("message", message)
    }

    private fun launchMessage(channel: String, num: Int, type: String = "Falcon-9", speed: Int = 500, mission: String = "ARTEMIS") =
        buildMessage(channel, num, "RocketLaunched", buildJsonObject {
            put("type", type)
            put("launchSpeed", speed)
            put("mission", mission)
        })

    private fun speedIncreased(channel: String, num: Int, by: Int) =
        buildMessage(channel, num, "RocketSpeedIncreased", buildJsonObject {
            put("by", by)
        })

    private fun speedDecreased(channel: String, num: Int, by: Int) =
        buildMessage(channel, num, "RocketSpeedDecreased", buildJsonObject {
            put("by", by)
        })

    private fun missionChanged(channel: String, num: Int, newMission: String) =
        buildMessage(channel, num, "RocketMissionChanged", buildJsonObject {
            put("newMission", newMission)
        })

    private fun exploded(channel: String, num: Int, reason: String) =
        buildMessage(channel, num, "RocketExploded", buildJsonObject {
            put("reason", reason)
        })

    @Test
    @Order(1)
    fun `test in-order messages`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-000000000001"

        // Send messages in order
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 3, 200)) }

        val response = client.get("/rockets/$channel")
        assertEquals(HttpStatusCode.OK, response.status)
        val rocket = response.body<RocketResponse>()
        assertEquals(800, rocket.speed) // 500 + 100 + 200
        assertEquals("Falcon-9", rocket.type)
        assertEquals("ARTEMIS", rocket.mission)
    }

    @Test
    @Order(2)
    fun `test out-of-order messages are applied correctly`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-000000000002"

        // Send messages out of order: 3, 1, 2
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 3, 200)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 500)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) }

        val response = client.get("/rockets/$channel")
        assertEquals(HttpStatusCode.OK, response.status)
        val rocket = response.body<RocketResponse>()
        assertEquals(800, rocket.speed) // 500 + 100 + 200
    }

    @Test
    @Order(3)
    fun `test duplicate messages are ignored`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-000000000003"

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 500)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) } // duplicate

        val response = client.get("/rockets/$channel")
        val rocket = response.body<RocketResponse>()
        assertEquals(600, rocket.speed) // 500 + 100, not 500 + 100 + 100
    }

    @Test
    @Order(4)
    fun `test explosion stops further state changes`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-000000000004"

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 500)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(exploded(channel, 2, "ENGINE_FAILURE")) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 3, 9999)) }

        val response = client.get("/rockets/$channel")
        val rocket = response.body<RocketResponse>()
        assertEquals("exploded", rocket.status)
        assertEquals(500, rocket.speed) // Speed should NOT have increased
    }

    @Test
    @Order(5)
    fun `test sorting rockets by speed`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val ch1 = "00000000-0000-0000-0000-000000000005"
        val ch2 = "00000000-0000-0000-0000-000000000006"
        val ch3 = "00000000-0000-0000-0000-000000000007"

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(ch1, 1, speed = 100)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(ch2, 1, speed = 900)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(ch3, 1, speed = 500)) }

        val response = client.get("/rockets?sort=speed&order=asc")
        val rockets = response.body<List<RocketResponse>>()

        // Find our test rockets in the result (there may be rockets from other tests)
        val testRockets = rockets.filter { it.channel in listOf(ch1, ch2, ch3) }
        assertEquals(3, testRockets.size)
        assertEquals(ch1, testRockets[0].channel) // slowest first
        assertEquals(ch3, testRockets[1].channel)
        assertEquals(ch2, testRockets[2].channel)
    }

    @Test
    @Order(6)
    fun `test 404 for unknown rocket`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.get("/rockets/00000000-0000-0000-0000-999999999999")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    @Order(7)
    fun `test malformed JSON payload returns 400`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-000000000008"

        // Launch message missing required 'type' field
        val malformedLaunch = buildMessage(channel, 1, "RocketLaunched", buildJsonObject {
            put("launchSpeed", 500)
            put("mission", "ARTEMIS")
            // 'type' is missing
        })

        val response = client.post("/messages") {
            contentType(ContentType.Application.Json)
            setBody(malformedLaunch)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    @Order(8)
    fun `test invalid sort parameter defaults gracefully`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        // Use an invalid sort parameter — should not error, defaults to sorting by channel
        val response = client.get("/rockets?sort=nonexistent_field&order=asc")
        assertEquals(HttpStatusCode.OK, response.status)
        // Successfully parsed as a list without error — the invalid sort parameter defaulted to channel
        response.body<List<RocketResponse>>()
    }

    @Test
    @Order(9)
    fun `test speed decrease below zero`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-000000000009"

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 100)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedDecreased(channel, 2, 200)) }

        val response = client.get("/rockets/$channel")
        assertEquals(HttpStatusCode.OK, response.status)
        val rocket = response.body<RocketResponse>()
        // Speed should be -100 (100 - 200) — the system allows negative speed
        assertEquals(-100, rocket.speed)
    }

    @Test
    @Order(10)
    fun `test missing by field in speed event returns 400`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-00000000000a"

        // First launch the rocket
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1)) }

        // Send SpeedIncreased with missing 'by' field
        val malformedSpeed = buildMessage(channel, 2, "RocketSpeedIncreased", buildJsonObject {
            // 'by' is missing
        })

        val response = client.post("/messages") {
            contentType(ContentType.Application.Json)
            setBody(malformedSpeed)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    @Order(11)
    fun `test mission change updates mission field`() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = "00000000-0000-0000-0000-00000000000b"

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, mission = "ARTEMIS")) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(missionChanged(channel, 2, "LUNA-2")) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(missionChanged(channel, 3, "MARS-EXPLORE")) }

        val response = client.get("/rockets/$channel")
        assertEquals(HttpStatusCode.OK, response.status)
        val rocket = response.body<RocketResponse>()
        assertEquals("MARS-EXPLORE", rocket.mission)
        assertEquals("Falcon-9", rocket.type)
        assertEquals(500, rocket.speed)
    }
}
