package org.example.pipeline.api.routes

import com.rabbitmq.client.Connection
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.ktor.ext.inject
import javax.sql.DataSource

/** Timeout in seconds for the database health check probe. */
private const val DB_HEALTH_TIMEOUT_SECONDS = 2

/**
 * Registers health check routes for infrastructure probes.
 *
 * Probes database connectivity and RabbitMQ connection status.
 * Returns 200 when all dependencies are healthy, 503 when any is degraded.
 */
fun Application.healthRoutes() {
    val dataSource by inject<DataSource>()
    val rabbitConnection by inject<Connection>()

    routing {
        /**
         * Readiness probe — checks database and RabbitMQ connectivity.
         * Returns 200 when all checks pass, 503 when any dependency is down.
         */
        get("/api/health") {
            val checks = mutableMapOf<String, String>()

            val dbOk = runCatching {
                dataSource.connection.use { it.isValid(DB_HEALTH_TIMEOUT_SECONDS) }
            }.getOrDefault(false)
            checks["database"] = if (dbOk) "ok" else "fail"

            val rabbitOk = runCatching {
                rabbitConnection.isOpen
            }.getOrDefault(false)
            checks["rabbitmq"] = if (rabbitOk) "ok" else "fail"

            val allOk = checks.values.all { it == "ok" }
            val status = if (allOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            val overallStatus = if (allOk) "ok" else "degraded"

            val body = buildJsonObject {
                put("status", overallStatus)
                putJsonObject("checks") {
                    checks.forEach { (k, v) -> put(k, v) }
                }
            }
            call.respondText(body.toString(), ContentType.Application.Json, status)
        }
    }
}
