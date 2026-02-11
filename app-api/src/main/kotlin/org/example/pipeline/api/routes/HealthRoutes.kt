package org.example.pipeline.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Registers health check routes for infrastructure probes. */
fun Application.healthRoutes() {
    routing {
        /** Liveness/readiness probe — returns 200 when the server is accepting connections. */
        get("/api/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
