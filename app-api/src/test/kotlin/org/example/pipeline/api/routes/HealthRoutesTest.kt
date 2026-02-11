package org.example.pipeline.api.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

/** Tests for the health check endpoint. */
class HealthRoutesTest : FunSpec({
    test("GET /api/health returns 200 with status ok") {
        testApplication {
            application { healthRoutes() }
            val response = client.get("/api/health")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\":\"ok\""
        }
    }
})
