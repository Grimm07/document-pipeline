package org.example.pipeline.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.koin.ktor.ext.inject

/** Registers the Prometheus metrics scrape endpoint. */
fun Application.metricsRoutes() {
    val registry by inject<PrometheusMeterRegistry>()
    routing {
        /** Prometheus scrape endpoint — returns metrics in text exposition format. */
        get("/metrics") {
            call.respondText(registry.scrape(), ContentType.Text.Plain)
        }
    }
}
