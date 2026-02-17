package org.example.pipeline.api.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

/** Tests for the API rate limiting behavior. */
class RateLimitTest : FunSpec({

    test("returns 429 after exceeding rate limit") {
        testApplication {
            application {
                install(RateLimit) {
                    register(RateLimitName("test-limit")) {
                        rateLimiter(limit = 3, refillPeriod = 1.minutes)
                        requestKey { call -> call.request.local.remoteHost }
                    }
                }
                install(ContentNegotiation) {
                    json(Json { prettyPrint = true; isLenient = true })
                }
                routing {
                    rateLimit(RateLimitName("test-limit")) {
                        get("/test") {
                            call.respondText("ok")
                        }
                    }
                }
            }

            // First 3 requests should succeed
            repeat(3) { i ->
                val response = client.get("/test")
                response.status shouldBe HttpStatusCode.OK
            }

            // 4th request should be rate limited
            val rateLimited = client.get("/test")
            rateLimited.status shouldBe HttpStatusCode.TooManyRequests
        }
    }

    test("different rate limit zones are independent") {
        testApplication {
            application {
                install(RateLimit) {
                    register(RateLimitName("zone-a")) {
                        rateLimiter(limit = 2, refillPeriod = 1.minutes)
                        requestKey { call -> call.request.local.remoteHost }
                    }
                    register(RateLimitName("zone-b")) {
                        rateLimiter(limit = 2, refillPeriod = 1.minutes)
                        requestKey { call -> call.request.local.remoteHost }
                    }
                }
                install(ContentNegotiation) {
                    json(Json { prettyPrint = true; isLenient = true })
                }
                routing {
                    rateLimit(RateLimitName("zone-a")) {
                        get("/a") { call.respondText("a") }
                    }
                    rateLimit(RateLimitName("zone-b")) {
                        get("/b") { call.respondText("b") }
                    }
                }
            }

            // Exhaust zone-a
            repeat(2) { client.get("/a").status shouldBe HttpStatusCode.OK }
            client.get("/a").status shouldBe HttpStatusCode.TooManyRequests

            // Zone-b should still work
            client.get("/b").status shouldBe HttpStatusCode.OK
        }
    }
})
