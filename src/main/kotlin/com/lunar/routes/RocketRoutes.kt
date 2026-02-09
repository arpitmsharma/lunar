package com.lunar.routes

import com.lunar.model.IncomingMessage
import com.lunar.service.RocketService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.rocketRoutes(service: RocketService) {

    post("/messages") {
        val message = call.receive<IncomingMessage>()
        service.processMessage(message)
        call.respond(HttpStatusCode.OK)
    }

    get("/rockets") {
        val sort = call.request.queryParameters["sort"]
        val order = call.request.queryParameters["order"]
        val rockets = service.getAllRockets(sort, order)
        call.respond(rockets)
    }

    get("/rockets/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val rocket = service.getRocket(id)
        if (rocket != null) {
            call.respond(rocket)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Rocket not found"))
        }
    }
}
