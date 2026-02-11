package org.example.pipeline.worker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.util.Base64

class HttpClassificationServiceTest : FunSpec({

    // Find a free port
    val port = ServerSocket(0).use { it.localPort }
    var fakeServer: EmbeddedServer<*, *>? = null
    lateinit var service: HttpClassificationService

    // Track received requests for assertions
    var lastReceivedContent: String? = null
    var lastReceivedMimeType: String? = null
    var responseClassification = "invoice"
    var responseConfidence = 0.95f
    var shouldFail = false
    var failStatusCode = HttpStatusCode.InternalServerError
    var shouldReturnMalformed = false

    @Serializable
    data class FakeClassifyRequest(val content: String, val mimeType: String)

    var responseOcrJson: String? = null

    beforeSpec {
        fakeServer = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                post("/classify-with-ocr") {
                    if (shouldFail) {
                        call.respond(failStatusCode, mapOf("error" to "Service error"))
                        return@post
                    }
                    if (shouldReturnMalformed) {
                        call.respondText("not json at all {{{", ContentType.Application.Json)
                        return@post
                    }
                    val request = call.receive<FakeClassifyRequest>()
                    lastReceivedContent = request.content
                    lastReceivedMimeType = request.mimeType
                    // Build JSON response manually to include nullable ocr field
                    val ocrPart = if (responseOcrJson != null) responseOcrJson else "null"
                    call.respondText(
                        """{"classification":"$responseClassification","confidence":$responseConfidence,"ocr":$ocrPart}""",
                        ContentType.Application.Json
                    )
                }
            }
        }.start(wait = false)

        // Wait for server to be ready
        kotlinx.coroutines.delay(1000)

        service = HttpClassificationService("http://localhost:$port")
    }

    afterSpec {
        service.close()
        fakeServer?.stop(100, 100)
    }

    beforeEach {
        lastReceivedContent = null
        lastReceivedMimeType = null
        responseClassification = "invoice"
        responseConfidence = 0.95f
        shouldFail = false
        shouldReturnMalformed = false
        responseOcrJson = null
    }

    context("classify success") {
        test("returns classification result from ML service") {
            responseClassification = "invoice"
            responseConfidence = 0.95f

            val result = service.classify("hello".toByteArray(), "application/pdf")
            result.classification shouldBe "invoice"
            result.confidence shouldBe 0.95f
        }

        test("sends base64-encoded content") {
            val content = "binary content here".toByteArray()
            service.classify(content, "application/pdf")

            val expectedBase64 = Base64.getEncoder().encodeToString(content)
            lastReceivedContent shouldBe expectedBase64
        }

        test("sends mimeType in request") {
            service.classify("data".toByteArray(), "image/png")
            lastReceivedMimeType shouldBe "image/png"
        }

        test("handles boundary confidence 0.0") {
            responseConfidence = 0.0f
            val result = service.classify("data".toByteArray(), "text/plain")
            result.confidence shouldBe 0.0f
        }

        test("handles boundary confidence 1.0") {
            responseConfidence = 1.0f
            val result = service.classify("data".toByteArray(), "text/plain")
            result.confidence shouldBe 1.0f
        }
    }

    context("classify errors") {
        test("throws on HTTP 500") {
            shouldFail = true
            failStatusCode = HttpStatusCode.InternalServerError

            shouldThrow<Exception> {
                service.classify("data".toByteArray(), "application/pdf")
            }
        }

        test("throws on HTTP 400") {
            shouldFail = true
            failStatusCode = HttpStatusCode.BadRequest

            shouldThrow<Exception> {
                service.classify("data".toByteArray(), "application/pdf")
            }
        }

        test("throws on malformed JSON response") {
            shouldReturnMalformed = true

            shouldThrow<Exception> {
                service.classify("data".toByteArray(), "application/pdf")
            }
        }
    }

    context("error resilience") {
        test("connection refused when ML service is down") {
            // Find a port with nothing listening
            val deadPort = java.net.ServerSocket(0).use { it.localPort }
            val deadService = HttpClassificationService("http://localhost:$deadPort")
            try {
                shouldThrow<Exception> {
                    deadService.classify("data".toByteArray(), "application/pdf")
                }
            } finally {
                deadService.close()
            }
        }

        test("timeout on slow ML service response") {
            val slowPort = java.net.ServerSocket(0).use { it.localPort }
            // Create a dedicated slow server
            val slowServer = embeddedServer(Netty, port = slowPort) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                routing {
                    post("/classify-with-ocr") {
                        // Delay longer than the 30s client timeout
                        // In practice, we use a shorter custom timeout for this test
                        kotlinx.coroutines.delay(60_000)
                        call.respondText("{}", ContentType.Application.Json)
                    }
                }
            }.start(wait = false)

            // Use a service with a short timeout to verify timeout behavior
            val slowService = HttpClassificationService("http://localhost:$slowPort", requestTimeoutMs = 5_000)
            try {
                shouldThrow<Exception> {
                    slowService.classify("data".toByteArray(), "application/pdf")
                }
            } finally {
                slowService.close()
                slowServer.stop(100, 100)
            }
        }
    }

    context("encoding") {
        test("empty content produces valid base64") {
            val content = ByteArray(0)
            service.classify(content, "application/octet-stream")

            val expectedBase64 = Base64.getEncoder().encodeToString(content)
            lastReceivedContent shouldBe expectedBase64
        }

        test("binary content is properly base64 encoded") {
            val content = ByteArray(256) { it.toByte() }
            service.classify(content, "application/octet-stream")

            val expectedBase64 = Base64.getEncoder().encodeToString(content)
            lastReceivedContent shouldBe expectedBase64
        }
    }

    context("OCR response parsing") {
        test("null ocr field results in null ocrResultJson") {
            responseOcrJson = null
            val result = service.classify("hello".toByteArray(), "text/plain")
            result.ocrResultJson shouldBe null
        }

        test("present ocr field is serialized to ocrResultJson string") {
            responseOcrJson = """{"pages":[{"pageIndex":0,"width":100,"height":200,"text":"hello","blocks":[]}],"fullText":"hello"}"""
            val result = service.classify("hello".toByteArray(), "application/pdf")
            result.ocrResultJson shouldBe responseOcrJson
        }
    }
})
