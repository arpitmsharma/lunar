package com.lunar.routes

import com.lunar.model.IncomingMessage
import com.lunar.sequencer.RedisSequencer
import com.lunar.sequencer.model.SequencedMessage
import com.lunar.service.RocketService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

fun Route.rocketRoutes(service: RocketService, sequencer: RedisSequencer) {

    post("/messages") {
        val message = call.receive<IncomingMessage>()

        // Convert to SequencedMessage
        val sequencedMessage = SequencedMessage(
            key = UUID.fromString(message.metadata.channel),
            messageNumber = message.metadata.messageNumber,
            messageType = message.metadata.messageType,
            messageTime = message.metadata.messageTime,
            payload = message.message
        )

        // Publish to Redis (fire-and-forget)
        sequencer.publish(sequencedMessage)

        // Return 202 Accepted immediately
        call.respond(HttpStatusCode.Accepted)
    }

    get("/rockets") {
        val sort = call.request.queryParameters["sort"]
        val order = call.request.queryParameters["order"]
        val rockets = service.getAllRockets(sort, order)
        call.respond(rockets)
    }

    get("/rockets/{id}") {
        val idStr = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing channel ID"))

        val channelId = try {
            UUID.fromString(idStr)
        } catch (e: IllegalArgumentException) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid UUID format"))
        }

        val rocket = service.getRocket(channelId)
        if (rocket != null) {
            call.respond(rocket)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Rocket not found"))
        }
    }
}
