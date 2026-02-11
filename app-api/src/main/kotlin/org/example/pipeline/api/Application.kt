package org.example.pipeline.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.example.pipeline.api.di.apiModule
import org.example.pipeline.api.routes.documentRoutes
import org.example.pipeline.api.routes.healthRoutes
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

/** Default HTTP server port when SERVER_PORT env var is not set. */
private const val DEFAULT_SERVER_PORT = 8080

/**
 * Entry point for the Document Pipeline API server.
 *
 * Starts an embedded Netty server with configurable host and port
 * via SERVER_HOST and SERVER_PORT environment variables.
 */
fun main() {
    val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: DEFAULT_SERVER_PORT
    val host = System.getenv("SERVER_HOST") ?: "0.0.0.0"

    logger.info("Starting Document Pipeline API on $host:$port")

    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

/**
 * Configures the Ktor application module.
 *
 * Installs Koin DI, CORS, JSON content negotiation, status pages
 * error handling, and registers document API routes.
 */
fun Application.module() {
    // Install Koin for dependency injection
    install(Koin) {
        slf4jLogger()
        modules(apiModule)
    }

    // Install CORS for frontend access
    val config by inject<HoconApplicationConfig>()
    val allowedHosts = config.propertyOrNull("cors.allowedHosts")
        ?.getList() ?: listOf("localhost:5173", "localhost:4173")

    install(CORS) {
        allowedHosts.forEach { host ->
            allowHost(host, schemes = listOf("http", "https"))
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
    }

    // Install content negotiation with JSON
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // Install status pages for error handling
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            logger.warn("Bad request: ${cause.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Bad request"))
            )
        }

        exception<NoSuchElementException> { call, cause ->
            logger.warn("Not found: ${cause.message}")
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to (cause.message ?: "Not found"))
            )
        }

        exception<Throwable> { call, cause ->
            logger.error("Internal server error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error")
            )
        }
    }

    // Register routes (health first — available even if other route setup fails)
    healthRoutes()
    documentRoutes()
}
