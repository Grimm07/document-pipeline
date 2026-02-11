package org.example.pipeline.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

class CorsTest : FunSpec({

    fun ApplicationTestBuilder.setupCorsApp(
        allowedHosts: List<String> = listOf("localhost:5173", "localhost:4173")
    ) {
        application {
            install(CORS) {
                allowedHosts.forEach { host ->
                    allowHost(host, schemes = listOf("http", "https"))
                }
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Options)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Accept)
            }
            install(ContentNegotiation) {
                json(Json { prettyPrint = true })
            }
            routing {
                get("/test") {
                    call.respondText("ok")
                }
            }
        }
    }

    test("allowed origin gets CORS headers in response") {
        testApplication {
            setupCorsApp()

            val response = client.get("/test") {
                header(HttpHeaders.Origin, "http://localhost:5173")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "http://localhost:5173"
        }
    }

    test("preflight OPTIONS returns correct CORS headers") {
        testApplication {
            setupCorsApp()

            val response = client.options("/test") {
                header(HttpHeaders.Origin, "http://localhost:5173")
                header(HttpHeaders.AccessControlRequestMethod, "GET")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "http://localhost:5173"
        }
    }

    test("second dev port is also allowed") {
        testApplication {
            setupCorsApp()

            val response = client.get("/test") {
                header(HttpHeaders.Origin, "http://localhost:4173")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "http://localhost:4173"
        }
    }

    test("disallowed origin does not get CORS headers") {
        testApplication {
            setupCorsApp()

            val response = client.get("/test") {
                header(HttpHeaders.Origin, "http://evil.com")
            }

            // Ktor CORS plugin returns 403 for disallowed origins
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }
})
