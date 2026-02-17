package org.example.pipeline.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.HttpURLConnection
import java.net.URI

/** Tests for the worker metrics and health HTTP server. */
class MetricsServerTest : FunSpec({

    /** Finds an available port to avoid conflicts with other tests. */
    fun findAvailablePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    test("health endpoint returns 200 when healthy") {
        val port = findAvailablePort()
        val server = MetricsServer(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), port) { true }
        server.start()
        try {
            val url = URI("http://localhost:$port/health").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.responseCode shouldBe 200
            conn.inputStream.bufferedReader().readText() shouldContain "\"status\":\"ok\""
        } finally {
            server.stop()
        }
    }

    test("health endpoint returns 503 when unhealthy") {
        val port = findAvailablePort()
        val server = MetricsServer(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), port) { false }
        server.start()
        try {
            val url = URI("http://localhost:$port/health").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.responseCode shouldBe 503
            conn.errorStream.bufferedReader().readText() shouldContain "\"status\":\"degraded\""
        } finally {
            server.stop()
        }
    }

    test("health endpoint returns 503 when health check throws") {
        val port = findAvailablePort()
        val server = MetricsServer(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), port) {
            throw RuntimeException("Connection lost")
        }
        server.start()
        try {
            val url = URI("http://localhost:$port/health").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.responseCode shouldBe 503
        } finally {
            server.stop()
        }
    }

    test("metrics endpoint still works") {
        val port = findAvailablePort()
        val server = MetricsServer(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), port)
        server.start()
        try {
            val url = URI("http://localhost:$port/metrics").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.responseCode shouldBe 200
        } finally {
            server.stop()
        }
    }
})
