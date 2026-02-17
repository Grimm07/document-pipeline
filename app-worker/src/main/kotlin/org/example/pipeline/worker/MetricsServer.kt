package org.example.pipeline.worker

import com.sun.net.httpserver.HttpServer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * Lightweight HTTP server exposing Prometheus `/metrics` and `/health` endpoints for the worker.
 *
 * Uses the JDK built-in [HttpServer] to avoid adding a web framework dependency
 * to the worker module. Binds to a configurable port (default 8081).
 *
 * @param registry Prometheus meter registry for scraping metrics
 * @param port TCP port to bind (default 8081, configurable via WORKER_METRICS_PORT)
 * @param healthCheck Lambda returning true when the worker is healthy; defaults to always healthy
 */
class MetricsServer(
    private val registry: PrometheusMeterRegistry,
    private val port: Int = DEFAULT_PORT,
    private val healthCheck: () -> Boolean = { true }
) {
    private val logger = LoggerFactory.getLogger(MetricsServer::class.java)
    private var server: HttpServer? = null

    /**
     * Starts the metrics and health HTTP server.
     */
    fun start() {
        val httpServer = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        httpServer.createContext("/metrics") { exchange ->
            val response = registry.scrape().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(HTTP_OK, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        httpServer.createContext("/health") { exchange ->
            val healthy = runCatching { healthCheck() }.getOrDefault(false)
            val status = if (healthy) HTTP_OK else HTTP_SERVICE_UNAVAILABLE
            val body = if (healthy) """{"status":"ok"}""" else """{"status":"degraded"}"""
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.executor = null
        httpServer.start()
        server = httpServer
        logger.info("Worker metrics server started on port {}", port)
    }

    /**
     * Stops the metrics HTTP server.
     */
    fun stop() {
        server?.stop(0)
        logger.info("Worker metrics server stopped")
    }

    private companion object {
        const val DEFAULT_PORT = 8081
        const val HTTP_OK = 200
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
