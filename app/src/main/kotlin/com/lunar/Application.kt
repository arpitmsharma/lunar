package com.lunar

import com.lunar.consumer.OrderedMessageConsumer
import com.lunar.db.DatabaseFactory
import com.lunar.db.ExposedRocketRepository
import com.lunar.db.ExposedTransactionRunner
import com.lunar.routes.rocketRoutes
import com.lunar.sequencer.LettuceRedisSequencer
import com.lunar.sequencer.SequencerConfig
import com.lunar.service.RocketService
import io.lettuce.core.RedisClient
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8088
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("com.lunar.Application")
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Database initialization
    DatabaseFactory.init()

    // Redis initialization - prioritize environment variables
    val redisHost = System.getenv("REDIS_HOST")
        ?: environment.config.propertyOrNull("redis.host")?.getString()
        ?: "localhost"
    val redisPort = System.getenv("REDIS_PORT")?.toIntOrNull()
        ?: environment.config.propertyOrNull("redis.port")?.getString()?.toInt()
        ?: 6379

    logger.info("Connecting to Redis at $redisHost:$redisPort")
    val redisClient = RedisClient.create("redis://$redisHost:$redisPort")
    val redisConnection = redisClient.connect()

    val sequencerConfig = SequencerConfig(redisHost = redisHost, redisPort = redisPort)
    val redisSequencer = LettuceRedisSequencer(redisConnection, sequencerConfig)

    // Service layer
    val rocketRepository = ExposedRocketRepository()
    val transactionRunner = ExposedTransactionRunner()
    val rocketService = RocketService(rocketRepository, transactionRunner)

    // Background consumer
    val consumer = OrderedMessageConsumer(redisSequencer, rocketService, scope)
    consumer.start()
    logger.info("Started ordered message consumer")

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        })
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            logger.warn("Bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Invalid request")))
        }
        exception<Exception> { call, cause ->
            logger.error("Internal server error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "Unknown error")))
        }
    }

    routing {
        rocketRoutes(rocketService, redisSequencer)
    }

    // Shutdown hook
    environment.monitor.subscribe(io.ktor.server.application.ApplicationStopPreparing) {
        logger.info("Application stopping - cleaning up resources")
        runBlocking {
            consumer.stop()
            redisConnection.close()
            redisClient.shutdown()
            scope.cancel()
        }
        logger.info("Resources cleaned up")
    }
}
