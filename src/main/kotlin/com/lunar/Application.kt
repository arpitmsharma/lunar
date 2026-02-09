package com.lunar

import com.lunar.db.DatabaseFactory
import com.lunar.db.ExposedRocketRepository
import com.lunar.db.ExposedStashRepository
import com.lunar.db.ExposedTransactionRunner
import com.lunar.routes.rocketRoutes
import com.lunar.service.RocketService
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
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8088
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("com.lunar.Application")

    DatabaseFactory.init()

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

    val rocketRepository = ExposedRocketRepository()
    val stashRepository = ExposedStashRepository()
    val transactionRunner = ExposedTransactionRunner()
    val rocketService = RocketService(rocketRepository, stashRepository, transactionRunner)

    routing {
        rocketRoutes(rocketService)
    }
}
