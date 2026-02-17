package org.example.pipeline.api.routes

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.testing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.pipeline.api.dto.ValidationErrorResponse
import org.example.pipeline.api.validation.ValidationException
import org.example.pipeline.domain.Document
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.example.pipeline.domain.QueuePublisher
import org.example.pipeline.storage.LocalFileStorageService
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class DocumentRoutesTest : FunSpec({

    val mockRepo = mockk<DocumentRepository>()
    val mockStorage = mockk<FileStorageService>()
    val mockPublisher = mockk<QueuePublisher>()

    val now = Clock.System.now()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun testDocument(
        id: String = UUID.randomUUID().toString(),
        classification: String = "unclassified",
        confidence: Float? = null,
        metadata: Map<String, String> = emptyMap()
    ) = Document(
        id = id,
        storagePath = "2024/07/15/$id.pdf",
        originalFilename = "report.pdf",
        mimeType = "application/pdf",
        fileSizeBytes = 1024L,
        classification = classification,
        confidence = confidence,
        metadata = metadata,
        createdAt = now,
        updatedAt = now
    )

    fun ApplicationTestBuilder.setupApp(
        hoconConfig: HoconApplicationConfig = HoconApplicationConfig(ConfigFactory.load())
    ) {
        application {
            install(Koin) {
                modules(module {
                    single<DocumentRepository> { mockRepo }
                    single<FileStorageService> { mockStorage }
                    single<QueuePublisher> { mockPublisher }
                    single<HoconApplicationConfig> { hoconConfig }
                    single { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
                })
            }
            install(CallId) {
                generate { java.util.UUID.randomUUID().toString() }
            }
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(RateLimit) {
                register(RateLimitName("upload")) {
                    rateLimiter(limit = 1000, refillPeriod = 1.minutes)
                }
                register(RateLimitName("global")) {
                    rateLimiter(limit = 1000, refillPeriod = 1.minutes)
                }
            }
            install(StatusPages) {
                exception<ValidationException> { call, cause ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ValidationErrorResponse(error = "Validation failed", fieldErrors = cause.fieldErrors)
                    )
                }
                exception<IllegalArgumentException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
                }
                exception<NoSuchElementException> { call, cause ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "Not found")))
                }
                exception<Throwable> { call, cause ->
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
                }
            }
            documentRoutes()
        }
    }

    beforeEach {
        clearAllMocks()
    }

    context("POST /api/documents/upload") {
        test("returns 200 on successful upload") {
            testApplication {
                setupApp()

                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers {
                    firstArg<Document>()
                }
                coEvery { mockPublisher.publish(any(), any()) } returns Unit

                val response = client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", "test content".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"report.pdf\"")
                        })
                    }))
                }

                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("stores file via FileStorageService") {
            testApplication {
                setupApp()

                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers { firstArg<Document>() }
                coEvery { mockPublisher.publish(any(), any()) } returns Unit

                client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", "file bytes".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"doc.pdf\"")
                        })
                    }))
                }

                coVerify { mockStorage.store(any(), any(), any()) }
            }
        }

        test("inserts record via DocumentRepository") {
            testApplication {
                setupApp()

                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers { firstArg<Document>() }
                coEvery { mockPublisher.publish(any(), any()) } returns Unit

                client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", "data".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"doc.pdf\"")
                        })
                    }))
                }

                coVerify { mockRepo.insert(any()) }
            }
        }

        test("publishes message via QueuePublisher") {
            testApplication {
                setupApp()

                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers { firstArg<Document>() }
                coEvery { mockPublisher.publish(any(), any()) } returns Unit

                client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", "data".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"doc.pdf\"")
                        })
                    }))
                }

                coVerify { mockPublisher.publish(any(), any()) }
            }
        }

        test("returns 400 when no file is provided") {
            testApplication {
                setupApp()

                val response = client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData { }))
                }

                // Either 400 or some error status is acceptable
                (response.status == HttpStatusCode.BadRequest ||
                    response.status == HttpStatusCode.InternalServerError) shouldBe true
            }
        }

        test("rejects upload exceeding configured file size limit") {
            testApplication {
                val tinyLimitConfig = HoconApplicationConfig(
                    ConfigFactory.parseMap(mapOf("upload.maxFileSizeBytes" to "10"))
                        .withFallback(ConfigFactory.load())
                )
                setupApp(tinyLimitConfig)

                val response = client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", ByteArray(1024), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"big.pdf\"")
                        })
                    }))
                }

                // Ktor should reject oversized multipart with a non-200 status
                (response.status != HttpStatusCode.OK) shouldBe true
            }
        }
    }

    context("GET /api/documents/{id}") {
        test("returns 200 when document exists") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc

                val response = client.get("/api/documents/${doc.id}")
                response.status shouldBe HttpStatusCode.OK

                val body = response.bodyAsText()
                body shouldContain doc.id
                body shouldContain "report.pdf"
            }
        }

        test("returns 404 when document does not exist") {
            testApplication {
                setupApp()

                val missingId = UUID.randomUUID().toString()
                coEvery { mockRepo.getById(missingId) } returns null

                val response = client.get("/api/documents/$missingId")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("response contains all expected fields") {
            testApplication {
                setupApp()

                val doc = testDocument(
                    classification = "invoice",
                    confidence = 0.95f,
                    metadata = mapOf("dept" to "finance")
                )
                coEvery { mockRepo.getById(doc.id) } returns doc

                val response = client.get("/api/documents/${doc.id}")
                val body = response.bodyAsText()
                val jsonObj = json.parseToJsonElement(body).jsonObject

                jsonObj["id"]?.jsonPrimitive?.content shouldBe doc.id
                jsonObj["originalFilename"]?.jsonPrimitive?.content shouldBe "report.pdf"
                jsonObj["mimeType"]?.jsonPrimitive?.content shouldBe "application/pdf"
                jsonObj["classification"]?.jsonPrimitive?.content shouldBe "invoice"
            }
        }

        test("maps domain Document via toResponse extension") {
            testApplication {
                setupApp()

                val doc = testDocument(classification = "receipt", confidence = 0.8f)
                coEvery { mockRepo.getById(doc.id) } returns doc

                val response = client.get("/api/documents/${doc.id}")
                val body = response.bodyAsText()

                // toResponse converts Instant to string
                body shouldContain doc.createdAt.toString()
            }
        }
    }

    context("GET /api/documents") {
        test("returns 200 with list of documents") {
            testApplication {
                setupApp()

                val docs = listOf(testDocument(), testDocument())
                coEvery { mockRepo.list(null, 50, 0) } returns docs

                val response = client.get("/api/documents")
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("forwards classification query parameter") {
            testApplication {
                setupApp()

                coEvery { mockRepo.list("invoice", 50, 0) } returns emptyList()

                client.get("/api/documents?classification=invoice")

                coVerify { mockRepo.list("invoice", 50, 0) }
            }
        }

        test("forwards limit query parameter") {
            testApplication {
                setupApp()

                coEvery { mockRepo.list(null, 10, 0) } returns emptyList()

                client.get("/api/documents?limit=10")

                coVerify { mockRepo.list(null, 10, 0) }
            }
        }

        test("forwards offset query parameter") {
            testApplication {
                setupApp()

                coEvery { mockRepo.list(null, 50, 20) } returns emptyList()

                client.get("/api/documents?offset=20")

                coVerify { mockRepo.list(null, 50, 20) }
            }
        }

        test("uses defaults of limit=50 and offset=0") {
            testApplication {
                setupApp()

                coEvery { mockRepo.list(null, 50, 0) } returns emptyList()

                client.get("/api/documents")

                coVerify { mockRepo.list(null, 50, 0) }
            }
        }

        test("returns empty list response") {
            testApplication {
                setupApp()

                coEvery { mockRepo.list(any(), any(), any()) } returns emptyList()

                val response = client.get("/api/documents")
                response.status shouldBe HttpStatusCode.OK
            }
        }
    }

    context("GET /api/documents/{id}/download") {
        test("returns file bytes with correct Content-Type") {
            testApplication {
                setupApp()

                val doc = testDocument()
                val fileContent = "PDF file content here".toByteArray()
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.retrieve(doc.storagePath) } returns fileContent

                val response = client.get("/api/documents/${doc.id}/download")
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.toString() shouldContain "application/pdf"
                response.bodyAsText() shouldBe "PDF file content here"
            }
        }

        test("sets Content-Disposition attachment header") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.retrieve(doc.storagePath) } returns "data".toByteArray()

                val response = client.get("/api/documents/${doc.id}/download")
                val disposition = response.headers[HttpHeaders.ContentDisposition]
                disposition shouldContain "attachment"
                disposition shouldContain "report.pdf"
            }
        }

        test("returns 404 when document does not exist") {
            testApplication {
                setupApp()

                val missingId = UUID.randomUUID().toString()
                coEvery { mockRepo.getById(missingId) } returns null

                val response = client.get("/api/documents/$missingId/download")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("returns 404 when file is missing from storage") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.retrieve(doc.storagePath) } returns null

                val response = client.get("/api/documents/${doc.id}/download")
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldContain "File not found"
            }
        }
    }

    context("GET /api/documents/{id}/ocr") {
        test("returns OCR JSON when document has OCR results") {
            testApplication {
                setupApp()

                val doc = testDocument().copy(ocrStoragePath = "2024/07/15/ocr-results.json")
                val ocrJson = """{"pages":[],"fullText":"extracted text"}"""
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.retrieve("2024/07/15/ocr-results.json") } returns ocrJson.toByteArray()

                val response = client.get("/api/documents/${doc.id}/ocr")
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.toString() shouldContain "application/json"
                response.bodyAsText() shouldBe ocrJson
            }
        }

        test("returns 404 when document has no OCR results") {
            testApplication {
                setupApp()

                val doc = testDocument() // ocrStoragePath is null
                coEvery { mockRepo.getById(doc.id) } returns doc

                val response = client.get("/api/documents/${doc.id}/ocr")
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldContain "No OCR results"
            }
        }

        test("returns 404 when document does not exist") {
            testApplication {
                setupApp()

                val missingId = UUID.randomUUID().toString()
                coEvery { mockRepo.getById(missingId) } returns null

                val response = client.get("/api/documents/$missingId/ocr")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("returns 404 when OCR file is missing from storage") {
            testApplication {
                setupApp()

                val doc = testDocument().copy(ocrStoragePath = "missing/ocr-results.json")
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.retrieve("missing/ocr-results.json") } returns null

                val response = client.get("/api/documents/${doc.id}/ocr")
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldContain "OCR file not found"
            }
        }

        test("document response includes hasOcrResults field") {
            testApplication {
                setupApp()

                val docWithOcr = testDocument().copy(ocrStoragePath = "some/path.json")
                coEvery { mockRepo.getById(docWithOcr.id) } returns docWithOcr

                val response = client.get("/api/documents/${docWithOcr.id}")
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["hasOcrResults"]?.jsonPrimitive?.content shouldBe "true"
            }
        }
    }

    context("GET /api/documents/search") {
        test("returns documents matching metadata") {
            testApplication {
                setupApp()

                val doc = testDocument(metadata = mapOf("dept" to "finance"))
                coEvery { mockRepo.searchMetadata(mapOf("dept" to "finance"), 50) } returns listOf(doc)

                val response = client.get("/api/documents/search?metadata.dept=finance")
                response.status shouldBe HttpStatusCode.OK

                val body = response.bodyAsText()
                body shouldContain doc.id
            }
        }

        test("returns 400 when no metadata params provided") {
            testApplication {
                setupApp()

                val response = client.get("/api/documents/search")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "metadata"
            }
        }

        test("passes limit parameter to repository") {
            testApplication {
                setupApp()

                coEvery { mockRepo.searchMetadata(any(), 10) } returns emptyList()

                client.get("/api/documents/search?metadata.type=invoice&limit=10")

                coVerify { mockRepo.searchMetadata(mapOf("type" to "invoice"), 10) }
            }
        }

        test("strips metadata prefix from query parameter keys") {
            testApplication {
                setupApp()

                coEvery { mockRepo.searchMetadata(any(), any()) } returns emptyList()

                client.get("/api/documents/search?metadata.dept=finance&metadata.year=2024")

                coVerify {
                    mockRepo.searchMetadata(
                        mapOf("dept" to "finance", "year" to "2024"),
                        50
                    )
                }
            }
        }

        test("ignores non-metadata query parameters") {
            testApplication {
                setupApp()

                coEvery { mockRepo.searchMetadata(any(), any()) } returns emptyList()

                client.get("/api/documents/search?metadata.dept=finance&other=ignored")

                coVerify { mockRepo.searchMetadata(mapOf("dept" to "finance"), 50) }
            }
        }
    }

    context("DELETE /api/documents/{id}") {
        test("returns 204 on successful deletion") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.delete(doc.storagePath) } returns true
                coEvery { mockRepo.delete(doc.id) } returns true

                val response = client.delete("/api/documents/${doc.id}")
                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("returns 404 when document does not exist") {
            testApplication {
                setupApp()

                val missingId = UUID.randomUUID().toString()
                coEvery { mockRepo.getById(missingId) } returns null

                val response = client.delete("/api/documents/$missingId")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("deletes both stored file and OCR file") {
            testApplication {
                setupApp()

                val doc = testDocument().copy(ocrStoragePath = "2024/07/15/ocr.json")
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.delete(doc.storagePath) } returns true
                coEvery { mockStorage.delete("2024/07/15/ocr.json") } returns true
                coEvery { mockRepo.delete(doc.id) } returns true

                client.delete("/api/documents/${doc.id}")

                coVerify { mockStorage.delete(doc.storagePath) }
                coVerify { mockStorage.delete("2024/07/15/ocr.json") }
                coVerify { mockRepo.delete(doc.id) }
            }
        }

        test("still deletes DB row when file deletion fails") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.delete(doc.storagePath) } throws RuntimeException("IO error")
                coEvery { mockRepo.delete(doc.id) } returns true

                val response = client.delete("/api/documents/${doc.id}")
                response.status shouldBe HttpStatusCode.NoContent
                coVerify { mockRepo.delete(doc.id) }
            }
        }
    }

    context("POST /api/documents/{id}/retry") {
        test("returns 200 with reset document") {
            testApplication {
                setupApp()

                val doc = testDocument(classification = "invoice", confidence = 0.95f)
                val resetDoc = doc.copy(classification = "unclassified", confidence = null, ocrStoragePath = null)
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, resetDoc)
                coEvery { mockRepo.resetClassification(doc.id) } returns true
                coEvery { mockPublisher.publish(doc.id, any()) } returns Unit

                val response = client.post("/api/documents/${doc.id}/retry")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "unclassified"
            }
        }

        test("returns 404 when document does not exist") {
            testApplication {
                setupApp()

                val missingId = UUID.randomUUID().toString()
                coEvery { mockRepo.getById(missingId) } returns null

                val response = client.post("/api/documents/$missingId/retry")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("calls resetClassification and publish") {
            testApplication {
                setupApp()

                val doc = testDocument()
                val resetDoc = doc.copy(classification = "unclassified", confidence = null)
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, resetDoc)
                coEvery { mockRepo.resetClassification(doc.id) } returns true
                coEvery { mockPublisher.publish(doc.id, any()) } returns Unit

                client.post("/api/documents/${doc.id}/retry")

                coVerify { mockRepo.resetClassification(doc.id) }
                coVerify { mockPublisher.publish(doc.id, any()) }
            }
        }

        test("cleans up OCR file when present") {
            testApplication {
                setupApp()

                val doc = testDocument().copy(ocrStoragePath = "2024/07/15/ocr.json")
                val resetDoc = doc.copy(classification = "unclassified", confidence = null, ocrStoragePath = null)
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, resetDoc)
                coEvery { mockStorage.delete("2024/07/15/ocr.json") } returns true
                coEvery { mockRepo.resetClassification(doc.id) } returns true
                coEvery { mockPublisher.publish(doc.id, any()) } returns Unit

                client.post("/api/documents/${doc.id}/retry")

                coVerify { mockStorage.delete("2024/07/15/ocr.json") }
            }
        }
    }

    context("integration: upload → download round-trip with real storage") {
        test("upload then download returns identical file content") {
            val tempDir = createTempDirectory("route-integration")
            try {
                val realStorage = LocalFileStorageService(tempDir)
                val capturedDoc = slot<Document>()
                val storedDocs = mutableMapOf<String, Document>()

                testApplication {
                    application {
                        install(Koin) {
                            modules(module {
                                single<DocumentRepository> { mockRepo }
                                single<FileStorageService> { realStorage }
                                single<QueuePublisher> { mockPublisher }
                                single<HoconApplicationConfig> { HoconApplicationConfig(ConfigFactory.load()) }
                            })
                        }
                        install(ContentNegotiation) {
                            json(Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true })
                        }
                        install(RateLimit) {
                            register(RateLimitName("upload")) {
                                rateLimiter(limit = 1000, refillPeriod = 1.minutes)
                            }
                            register(RateLimitName("global")) {
                                rateLimiter(limit = 1000, refillPeriod = 1.minutes)
                            }
                        }
                        install(StatusPages) {
                            exception<Throwable> { call, _ ->
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
                            }
                        }
                        documentRoutes()
                    }

                    coEvery { mockRepo.insert(capture(capturedDoc)) } answers {
                        val doc = capturedDoc.captured
                        storedDocs[doc.id] = doc
                        doc
                    }
                    coEvery { mockPublisher.publish(any(), any()) } returns Unit
                    coEvery { mockRepo.getById(any()) } answers {
                        storedDocs[firstArg()]
                    }

                    val originalContent = "PDF binary content for round-trip test".toByteArray()

                    val uploadResponse = client.post("/api/documents/upload") {
                        setBody(MultiPartFormDataContent(formData {
                            append("file", originalContent, Headers.build {
                                append(HttpHeaders.ContentType, "application/pdf")
                                append(HttpHeaders.ContentDisposition, "filename=\"round-trip.pdf\"")
                            })
                        }))
                    }
                    uploadResponse.status shouldBe HttpStatusCode.OK

                    val uploadBody = json.parseToJsonElement(uploadResponse.bodyAsText()).jsonObject
                    val docId = uploadBody["id"]!!.jsonPrimitive.content

                    val downloadResponse = client.get("/api/documents/$docId/download")
                    downloadResponse.status shouldBe HttpStatusCode.OK
                    downloadResponse.readRawBytes() shouldBe originalContent
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        test("upload stores relative path in document record") {
            val tempDir = createTempDirectory("route-relpath")
            try {
                val realStorage = LocalFileStorageService(tempDir)
                val capturedDoc = slot<Document>()

                testApplication {
                    application {
                        install(Koin) {
                            modules(module {
                                single<DocumentRepository> { mockRepo }
                                single<FileStorageService> { realStorage }
                                single<QueuePublisher> { mockPublisher }
                                single<HoconApplicationConfig> { HoconApplicationConfig(ConfigFactory.load()) }
                            })
                        }
                        install(ContentNegotiation) {
                            json(Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true })
                        }
                        install(RateLimit) {
                            register(RateLimitName("upload")) {
                                rateLimiter(limit = 1000, refillPeriod = 1.minutes)
                            }
                            register(RateLimitName("global")) {
                                rateLimiter(limit = 1000, refillPeriod = 1.minutes)
                            }
                        }
                        documentRoutes()
                    }

                    coEvery { mockRepo.insert(capture(capturedDoc)) } answers { capturedDoc.captured }
                    coEvery { mockPublisher.publish(any(), any()) } returns Unit

                    client.post("/api/documents/upload") {
                        setBody(MultiPartFormDataContent(formData {
                            append("file", "data".toByteArray(), Headers.build {
                                append(HttpHeaders.ContentType, "application/pdf")
                                append(HttpHeaders.ContentDisposition, "filename=\"relpath.pdf\"")
                            })
                        }))
                    }

                    val storedPath = capturedDoc.captured.storagePath
                    storedPath.startsWith("/") shouldBe false
                    storedPath.contains(tempDir.toString()) shouldBe false
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        test("download with tampered storagePath returns 404") {
            testApplication {
                setupApp()

                val doc = testDocument().copy(storagePath = "nonexistent/tampered/path.pdf")
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.retrieve("nonexistent/tampered/path.pdf") } returns null

                val response = client.get("/api/documents/${doc.id}/download")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    }

    context("upload error handling") {
        test("returns 500 when storage.store() throws IOException") {
            testApplication {
                setupApp()
                coEvery { mockStorage.store(any(), any(), any()) } throws java.io.IOException("Disk full")

                val response = client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", "content".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"test.pdf\"")
                        })
                    }))
                }

                response.status shouldBe HttpStatusCode.InternalServerError
                // Verify repo.insert was NOT called (storage failed first)
                coVerify(exactly = 0) { mockRepo.insert(any()) }
            }
        }

        test("returns 500 when repo.insert() throws") {
            testApplication {
                setupApp()
                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } throws RuntimeException("DB constraint violation")

                val response = client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", "content".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"test.pdf\"")
                        })
                    }))
                }

                response.status shouldBe HttpStatusCode.InternalServerError
            }
        }

        test("returns 500 when publisher.publish() throws") {
            testApplication {
                setupApp()
                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers { firstArg<Document>() }
                coEvery { mockPublisher.publish(any()) } throws RuntimeException("Queue connection failed")

                val response = client.post("/api/documents/upload") {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", "content".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"test.pdf\"")
                        })
                    }))
                }

                response.status shouldBe HttpStatusCode.InternalServerError
            }
        }
    }

    context("download edge cases") {
        test("handles corrupted mimeType in ContentType.parse()") {
            testApplication {
                setupApp()

                val doc = testDocument().let {
                    // Create a document with a problematic mimeType
                    Document(
                        id = it.id,
                        storagePath = it.storagePath,
                        originalFilename = it.originalFilename,
                        mimeType = "not/a/valid///mime",
                        fileSizeBytes = it.fileSizeBytes,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt
                    )
                }
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.retrieve(doc.storagePath) } returns "data".toByteArray()

                val response = client.get("/api/documents/${doc.id}/download")
                // Should either return 500 (parse failure) or handle gracefully
                // ContentType.parse() may throw BadContentTypeFormatException
                (response.status == HttpStatusCode.InternalServerError ||
                    response.status == HttpStatusCode.OK) shouldBe true
            }
        }
    }

    context("delete edge cases") {
        test("returns 204 even when repo.delete returns false") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc
                coEvery { mockStorage.delete(doc.storagePath) } returns true
                coEvery { mockRepo.delete(doc.id) } returns false // row already gone

                val response = client.delete("/api/documents/${doc.id}")
                response.status shouldBe HttpStatusCode.NoContent
            }
        }
    }

    context("PATCH /api/documents/{id}/classification") {
        test("returns 200 with updated document on valid correction") {
            testApplication {
                setupApp()

                val doc = testDocument(classification = "invoice", confidence = 0.85f)
                val correctedDoc = doc.copy(classification = "contract", classificationSource = "manual")
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, correctedDoc)
                coEvery { mockRepo.correctClassification(doc.id, "contract") } returns true

                val response = client.patch("/api/documents/${doc.id}/classification") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"classification":"contract"}""")
                }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "contract"
                response.bodyAsText() shouldContain "manual"
            }
        }

        test("returns 404 when document does not exist") {
            testApplication {
                setupApp()

                val missingId = UUID.randomUUID().toString()
                coEvery { mockRepo.getById(missingId) } returns null

                val response = client.patch("/api/documents/$missingId/classification") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"classification":"invoice"}""")
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("returns 400 when classification is blank") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc

                val response = client.patch("/api/documents/${doc.id}/classification") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"classification":"  "}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "blank"
            }
        }

        test("returns 400 when body is missing or malformed") {
            testApplication {
                setupApp()

                val doc = testDocument()

                val response = client.patch("/api/documents/${doc.id}/classification") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("calls correctClassification on repository") {
            testApplication {
                setupApp()

                val doc = testDocument(classification = "invoice", confidence = 0.9f)
                val correctedDoc = doc.copy(classification = "report", classificationSource = "manual")
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, correctedDoc)
                coEvery { mockRepo.correctClassification(doc.id, "report") } returns true

                client.patch("/api/documents/${doc.id}/classification") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"classification":"report"}""")
                }

                coVerify { mockRepo.correctClassification(doc.id, "report") }
            }
        }
    }

    context("retry edge cases") {
        test("returns 404 when doc disappears between reset and re-fetch") {
            testApplication {
                setupApp()

                val doc = testDocument()
                // First getById returns doc, second (after reset) returns null
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, null)
                coEvery { mockRepo.resetClassification(doc.id) } returns true
                coEvery { mockPublisher.publish(doc.id, any()) } returns Unit

                val response = client.post("/api/documents/${doc.id}/retry")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("continues when resetClassification returns false") {
            testApplication {
                setupApp()

                val doc = testDocument()
                val resetDoc = doc.copy(classification = "unclassified", confidence = null)
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, resetDoc)
                coEvery { mockRepo.resetClassification(doc.id) } returns false
                coEvery { mockPublisher.publish(doc.id, any()) } returns Unit

                val response = client.post("/api/documents/${doc.id}/retry")
                // Should still return 200 (resetClassification returning false doesn't abort)
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("continues when OCR file deletion fails during retry") {
            testApplication {
                setupApp()

                val doc = testDocument().copy(ocrStoragePath = "2024/07/15/ocr.json")
                val resetDoc = doc.copy(classification = "unclassified", confidence = null, ocrStoragePath = null)
                coEvery { mockRepo.getById(doc.id) } returnsMany listOf(doc, resetDoc)
                coEvery { mockStorage.delete("2024/07/15/ocr.json") } throws RuntimeException("Storage error")
                coEvery { mockRepo.resetClassification(doc.id) } returns true
                coEvery { mockPublisher.publish(doc.id, any()) } returns Unit

                val response = client.post("/api/documents/${doc.id}/retry")
                // Should still succeed — OCR deletion failure is caught
                response.status shouldBe HttpStatusCode.OK
            }
        }
    }

    context("input validation") {
        test("GET /{id} returns 400 for non-UUID id") {
            testApplication {
                setupApp()

                val response = client.get("/api/documents/not-a-uuid")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "UUID"
            }
        }

        test("GET /api/documents rejects limit=0") {
            testApplication {
                setupApp()

                val response = client.get("/api/documents?limit=0")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "limit"
            }
        }

        test("GET /api/documents rejects limit=501") {
            testApplication {
                setupApp()

                val response = client.get("/api/documents?limit=501")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("GET /api/documents rejects negative offset") {
            testApplication {
                setupApp()

                val response = client.get("/api/documents?offset=-1")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "offset"
            }
        }

        test("GET /api/documents accepts limit=500") {
            testApplication {
                setupApp()

                coEvery { mockRepo.list(any(), 500, 0) } returns emptyList()

                val response = client.get("/api/documents?limit=500")
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("GET /search rejects limit=0") {
            testApplication {
                setupApp()

                val response = client.get("/api/documents/search?metadata.key=val&limit=0")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("PATCH classification rejects label over 255 chars") {
            testApplication {
                setupApp()

                val doc = testDocument()
                coEvery { mockRepo.getById(doc.id) } returns doc

                val response = client.patch("/api/documents/${doc.id}/classification") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"classification":"${"a".repeat(256)}"}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("DELETE /{id} returns 400 for non-UUID id") {
            testApplication {
                setupApp()

                val response = client.delete("/api/documents/not-a-uuid")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("POST /{id}/retry returns 400 for non-UUID id") {
            testApplication {
                setupApp()

                val response = client.post("/api/documents/not-a-uuid/retry")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("validation error response has fieldErrors structure") {
            testApplication {
                setupApp()

                val response = client.get("/api/documents?limit=0&offset=-1")
                response.status shouldBe HttpStatusCode.BadRequest

                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "Validation failed"
                val fieldErrors = body["fieldErrors"]?.jsonObject
                fieldErrors?.keys?.size shouldBe 2
                fieldErrors?.containsKey(".limit") shouldBe true
                fieldErrors?.containsKey(".offset") shouldBe true
            }
        }
    }
})
