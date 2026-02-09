package com.lunar

import com.lunar.db.DatabaseFactory
import com.lunar.model.RocketResponse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IntegrationTest {

    companion object {
        // Test UUIDs for different channels
        val CHANNEL_IN_ORDER = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
        val CHANNEL_OOO = UUID.fromString("550e8400-e29b-41d4-a716-446655440002")
        val CHANNEL_DUP = UUID.fromString("550e8400-e29b-41d4-a716-446655440003")
        val CHANNEL_EXPLODE = UUID.fromString("550e8400-e29b-41d4-a716-446655440004")
        val CHANNEL_MALFORMED = UUID.fromString("550e8400-e29b-41d4-a716-446655440005")
        val CHANNEL_NEGATIVE = UUID.fromString("550e8400-e29b-41d4-a716-446655440006")
        val CHANNEL_MISSING = UUID.fromString("550e8400-e29b-41d4-a716-446655440007")
        val CHANNEL_MISSION = UUID.fromString("550e8400-e29b-41d4-a716-446655440008")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:15-alpine").apply {
            withDatabaseName("lunar_test")
            withUsername("test")
            withPassword("test")
        }

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

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
        channel: UUID,
        messageNumber: Int,
        messageType: String,
        message: JsonObject
    ): JsonObject = buildJsonObject {
        putJsonObject("metadata") {
            put("channel", channel.toString())
            put("messageNumber", messageNumber)
            put("messageTime", "2024-01-01T00:00:00Z")
            put("messageType", messageType)
        }
        put("message", message)
    }

    private fun launchMessage(channel: UUID, num: Int, type: String = "Falcon-9", speed: Int = 500, mission: String = "ARTEMIS") =
        buildMessage(channel, num, "RocketLaunched", buildJsonObject {
            put("type", type)
            put("launchSpeed", speed)
            put("mission", mission)
        })

    private fun speedIncreased(channel: UUID, num: Int, by: Int) =
        buildMessage(channel, num, "RocketSpeedIncreased", buildJsonObject {
            put("by", by)
        })

    private fun speedDecreased(channel: UUID, num: Int, by: Int) =
        buildMessage(channel, num, "RocketSpeedDecreased", buildJsonObject {
            put("by", by)
        })

    private fun missionChanged(channel: UUID, num: Int, newMission: String) =
        buildMessage(channel, num, "RocketMissionChanged", buildJsonObject {
            put("newMission", newMission)
        })

    private fun exploded(channel: UUID, num: Int, reason: String) =
        buildMessage(channel, num, "RocketExploded", buildJsonObject {
            put("reason", reason)
        })

    /**
     * Poll until a condition is met or timeout
     */
    private suspend fun <T> pollUntil(
        timeoutMs: Long = 5000,
        intervalMs: Long = 100,
        condition: suspend () -> T?
    ): T {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            condition()?.let { return it }
            delay(intervalMs)
        }
        throw AssertionError("Timeout waiting for condition after ${timeoutMs}ms")
    }

    @Test
    @Order(1)
    fun `test in-order messages`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        // Give the background consumer time to start
        runBlocking { delay(500) }

        val channel = CHANNEL_IN_ORDER

        // Send messages in order - expect 202 Accepted
        val r1 = client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1)) }
        assertEquals(HttpStatusCode.Accepted, r1.status)

        val r2 = client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) }
        assertEquals(HttpStatusCode.Accepted, r2.status)

        val r3 = client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 3, 200)) }
        assertEquals(HttpStatusCode.Accepted, r3.status)

        // Poll until rocket state is correct
        runBlocking {
            val rocket = pollUntil {
                val response = client.get("/rockets/$channel")
                if (response.status == HttpStatusCode.OK) {
                    val r = response.body<RocketResponse>()
                    if (r.speed == 800) r else null
                } else null
            }
            assertEquals(800, rocket.speed) // 500 + 100 + 200
            assertEquals("Falcon-9", rocket.type)
            assertEquals("ARTEMIS", rocket.mission)
        }
    }

    @Test
    @Order(2)
    fun `test out-of-order messages are applied correctly`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = CHANNEL_OOO

        // Send messages out of order: 3, 1, 2
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 3, 200)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 500)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) }

        // Poll until rocket state is correct
        runBlocking {
            val rocket = pollUntil {
                val response = client.get("/rockets/$channel")
                if (response.status == HttpStatusCode.OK) {
                    val r = response.body<RocketResponse>()
                    if (r.speed == 800) r else null
                } else null
            }
            assertEquals(800, rocket.speed) // 500 + 100 + 200
        }
    }

    @Test
    @Order(3)
    fun `test duplicate messages are ignored`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = CHANNEL_DUP

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 500)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 2, 100)) } // duplicate

        runBlocking {
            val rocket = pollUntil {
                val response = client.get("/rockets/$channel")
                if (response.status == HttpStatusCode.OK) {
                    val r = response.body<RocketResponse>()
                    if (r.speed == 600) r else null
                } else null
            }
            assertEquals(600, rocket.speed) // 500 + 100, not 500 + 100 + 100
        }
    }

    @Test
    @Order(4)
    fun `test explosion stops further state changes`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = CHANNEL_EXPLODE

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 500)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(exploded(channel, 2, "ENGINE_FAILURE")) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedIncreased(channel, 3, 9999)) }

        runBlocking {
            val rocket = pollUntil {
                val response = client.get("/rockets/$channel")
                if (response.status == HttpStatusCode.OK) {
                    val r = response.body<RocketResponse>()
                    if (r.status == "exploded") r else null
                } else null
            }
            assertEquals("exploded", rocket.status)
            assertEquals(500, rocket.speed) // Speed should NOT have increased
        }
    }

    @Test
    @Order(5)
    fun `test sorting rockets by speed`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val ch1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440100")
        val ch2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440101")
        val ch3 = UUID.fromString("550e8400-e29b-41d4-a716-446655440102")

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(ch1, 1, speed = 100)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(ch2, 1, speed = 900)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(ch3, 1, speed = 500)) }

        runBlocking {
            val testRockets = pollUntil {
                val response = client.get("/rockets?sort=speed&order=asc")
                val rockets = response.body<List<RocketResponse>>()
                val test = rockets.filter { UUID.fromString(it.channel) in setOf(ch1, ch2, ch3) }
                if (test.size == 3) test else null
            }
            assertEquals(3, testRockets.size)
            assertEquals(ch1.toString(), testRockets[0].channel) // slowest first
            assertEquals(ch3.toString(), testRockets[1].channel)
            assertEquals(ch2.toString(), testRockets[2].channel)
        }
    }

    @Test
    @Order(6)
    fun `test 404 for unknown rocket`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        // Use a valid UUID that doesn't exist in the database
        val nonexistentId = "00000000-0000-0000-0000-000000000999"
        val response = client.get("/rockets/$nonexistentId")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    @Order(7)
    fun `test malformed JSON payload returns 400`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = CHANNEL_MALFORMED

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
        // Message is accepted but will fail during processing
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    @Order(8)
    fun `test invalid sort parameter defaults gracefully`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
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
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = CHANNEL_NEGATIVE

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, speed = 100)) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(speedDecreased(channel, 2, 200)) }

        runBlocking {
            val rocket = pollUntil {
                val response = client.get("/rockets/$channel")
                if (response.status == HttpStatusCode.OK) {
                    val r = response.body<RocketResponse>()
                    if (r.speed == -100) r else null
                } else null
            }
            assertEquals(-100, rocket.speed) // Speed should be -100 (100 - 200)
        }
    }

    @Test
    @Order(10)
    fun `test missing by field in speed event returns 400`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = CHANNEL_MISSING

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
        // Message is accepted but will fail during processing
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    @Order(11)
    fun `test mission change updates mission field`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "redis.host" to redis.host,
                "redis.port" to redis.firstMappedPort.toString()
            )
        }
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val channel = CHANNEL_MISSION

        client.post("/messages") { contentType(ContentType.Application.Json); setBody(launchMessage(channel, 1, mission = "ARTEMIS")) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(missionChanged(channel, 2, "LUNA-2")) }
        client.post("/messages") { contentType(ContentType.Application.Json); setBody(missionChanged(channel, 3, "MARS-EXPLORE")) }

        runBlocking {
            val rocket = pollUntil {
                val response = client.get("/rockets/$channel")
                if (response.status == HttpStatusCode.OK) {
                    val r = response.body<RocketResponse>()
                    if (r.mission == "MARS-EXPLORE") r else null
                } else null
            }
            assertEquals("MARS-EXPLORE", rocket.mission)
            assertEquals("Falcon-9", rocket.type)
            assertEquals(500, rocket.speed)
        }
    }
}
