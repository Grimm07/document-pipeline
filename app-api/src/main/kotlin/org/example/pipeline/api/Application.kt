package org.example.pipeline.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.example.pipeline.api.di.apiModule
import org.example.pipeline.api.dto.ValidationErrorResponse
import org.example.pipeline.api.routes.documentRoutes
import org.example.pipeline.api.routes.healthRoutes
import org.example.pipeline.api.routes.metricsRoutes
import org.example.pipeline.api.validation.ValidationException
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("Application")

/** Default HTTP server port when SERVER_PORT env var is not set. */
private const val DEFAULT_SERVER_PORT = 8080

/** Maximum allowed length for correlation IDs. */
private const val MAX_CALL_ID_LENGTH = 200

/** Default upload rate limit (requests per refill period). */
private const val DEFAULT_UPLOAD_RATE_LIMIT = 10

/** Default global rate limit (requests per refill period). */
private const val DEFAULT_GLOBAL_RATE_LIMIT = 100

/** Default rate limit refill period in seconds. */
private const val DEFAULT_REFILL_SECONDS = 60

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
@Suppress("LongMethod") // Plugin installation is inherently verbose
fun Application.module() {
    // Install Koin for dependency injection
    install(Koin) {
        slf4jLogger()
        modules(apiModule)
    }

    // Install CallId for correlation ID propagation
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { java.util.UUID.randomUUID().toString() }
        verify { it.isNotEmpty() && it.length <= MAX_CALL_ID_LENGTH }
        replyToHeader(HttpHeaders.XRequestId)
    }

    // Install CallLogging with MDC correlation ID
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("correlationId")
    }

    // Install Micrometer metrics with Prometheus registry
    val prometheusMeterRegistry by inject<PrometheusMeterRegistry>()
    install(MicrometerMetrics) {
        registry = prometheusMeterRegistry
        distributionStatisticConfig = DistributionStatisticConfig.Builder()
            .percentilesHistogram(true).build()
        meterBinders = listOf(JvmMemoryMetrics(), JvmGcMetrics(), JvmThreadMetrics(), ProcessorMetrics())
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
        allowHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.XRequestId)
    }

    // Install rate limiting per client IP
    val uploadLimit = config.propertyOrNull("rateLimit.uploadLimit")
        ?.getString()?.toIntOrNull() ?: DEFAULT_UPLOAD_RATE_LIMIT
    val uploadRefillSecs = config.propertyOrNull("rateLimit.uploadRefillSeconds")
        ?.getString()?.toLongOrNull() ?: DEFAULT_REFILL_SECONDS.toLong()
    val globalLimit = config.propertyOrNull("rateLimit.globalLimit")
        ?.getString()?.toIntOrNull() ?: DEFAULT_GLOBAL_RATE_LIMIT
    val globalRefillSecs = config.propertyOrNull("rateLimit.globalRefillSeconds")
        ?.getString()?.toLongOrNull() ?: DEFAULT_REFILL_SECONDS.toLong()

    install(RateLimit) {
        register(RateLimitName("upload")) {
            rateLimiter(limit = uploadLimit, refillPeriod = uploadRefillSecs.seconds)
            requestKey { call -> call.request.local.remoteHost }
        }
        register(RateLimitName("global")) {
            rateLimiter(limit = globalLimit, refillPeriod = globalRefillSecs.seconds)
            requestKey { call -> call.request.local.remoteHost }
        }
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
        exception<ValidationException> { call, cause ->
            logger.warn("Validation failed: {}", cause.fieldErrors)
            call.respond(
                HttpStatusCode.BadRequest,
                ValidationErrorResponse(error = "Validation failed", fieldErrors = cause.fieldErrors)
            )
        }

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
    // metricsRoutes before documentRoutes to avoid /{id} parameter match
    healthRoutes()
    metricsRoutes()
    documentRoutes()
}
