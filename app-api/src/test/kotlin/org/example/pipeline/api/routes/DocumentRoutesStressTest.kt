package org.example.pipeline.api.routes

import io.kotest.assertions.withClue
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.example.pipeline.api.dto.toResponse
import org.example.pipeline.domain.Document
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.example.pipeline.domain.QueuePublisher
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID
import kotlin.time.Clock

private data class UploadResult(val clientId: Int, val status: Int)
private data class GetResult(val clientId: Int, val docId: String, val status: Int)
private data class ListResult(val clientId: Int, val status: Int)
private data class MixedResult(val clientId: Int, val op: String, val status: Int)

class DocumentRoutesStressTest : FunSpec({

    val mockRepo = mockk<DocumentRepository>()
    val mockStorage = mockk<FileStorageService>()
    val mockPublisher = mockk<QueuePublisher>()

    val now = Clock.System.now()

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

    fun ApplicationTestBuilder.setupApp() {
        application {
            install(Koin) {
                modules(module {
                    single<DocumentRepository> { mockRepo }
                    single<FileStorageService> { mockStorage }
                    single<QueuePublisher> { mockPublisher }
                    single { HoconApplicationConfig(ConfigFactory.load()) }
                })
            }
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(StatusPages) {
                exception<IllegalArgumentException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
                }
                exception<NoSuchElementException> { call, cause ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "Not found")))
                }
                exception<Throwable> { call, _ ->
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
                }
            }
            documentRoutes()
        }
    }

    beforeEach {
        clearAllMocks()
    }

    // ── Concurrent upload ────────────────────────────────────
    context("concurrent upload") {

        test("50 concurrent POST /upload all return 200") {
            testApplication {
                setupApp()

                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers { firstArg<Document>() }
                coEvery { mockPublisher.publish(any()) } returns Unit

                coroutineScope {
                    val results = (1..50).map { i ->
                        async(CoroutineName("upload-$i")) {
                            val response = client.post("/api/documents/upload") {
                                setBody(MultiPartFormDataContent(formData {
                                    append("file", "content-$i".toByteArray(), Headers.build {
                                        append(HttpHeaders.ContentType, "application/pdf")
                                        append(HttpHeaders.ContentDisposition, "filename=\"file-$i.pdf\"")
                                    })
                                }))
                            }
                            UploadResult(0, response.status.value)
                        }
                    }.awaitAll()

                    results.forEach { r ->
                        withClue("upload should return 200") {
                            r.status shouldBe 200
                        }
                    }
                }

                coVerify(atLeast = 50) { mockStorage.store(any(), any(), any()) }
                coVerify(atLeast = 50) { mockRepo.insert(any()) }
                coVerify(atLeast = 50) { mockPublisher.publish(any()) }
            }
        }

        test("10 clients x 10 uploads each, all succeed") {
            testApplication {
                setupApp()

                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers { firstArg<Document>() }
                coEvery { mockPublisher.publish(any()) } returns Unit

                coroutineScope {
                    val results = (1..10).map { clientId ->
                        async(CoroutineName("client-$clientId")) {
                            (1..10).map { opId ->
                                async(CoroutineName("client-$clientId/op-$opId")) {
                                    val response = client.post("/api/documents/upload") {
                                        setBody(MultiPartFormDataContent(formData {
                                            append("file", "c$clientId-o$opId".toByteArray(), Headers.build {
                                                append(HttpHeaders.ContentType, "application/pdf")
                                                append(HttpHeaders.ContentDisposition, "filename=\"c$clientId-$opId.pdf\"")
                                            })
                                        }))
                                    }
                                    UploadResult(clientId, response.status.value)
                                }
                            }.awaitAll()
                        }
                    }.awaitAll().flatten()

                    results.forEach { r ->
                        withClue("client=${r.clientId} upload should return 200") {
                            r.status shouldBe 200
                        }
                    }
                }
            }
        }
    }

    // ── Concurrent read ──────────────────────────────────────
    context("concurrent read") {

        test("50 concurrent GET /{id} all return 200") {
            testApplication {
                setupApp()

                val docs = (1..50).map { testDocument() }
                docs.forEach { doc ->
                    coEvery { mockRepo.getById(doc.id) } returns doc
                }

                coroutineScope {
                    val results = docs.mapIndexed { i, doc ->
                        async(CoroutineName("get-$i")) {
                            val response = client.get("/api/documents/${doc.id}")
                            GetResult(0, doc.id, response.status.value)
                        }
                    }.awaitAll()

                    results.forEach { r ->
                        withClue("docId=${r.docId}") {
                            r.status shouldBe 200
                        }
                    }
                }
            }
        }
    }

    // ── Mixed operations ─────────────────────────────────────
    context("mixed operations") {

        test("25 uploads + 25 reads simultaneously") {
            testApplication {
                setupApp()

                coEvery { mockStorage.store(any(), any(), any()) } returns "2024/07/15/test.pdf"
                coEvery { mockRepo.insert(any()) } answers { firstArg<Document>() }
                coEvery { mockPublisher.publish(any()) } returns Unit

                val readDocs = (1..25).map { testDocument() }
                readDocs.forEach { doc ->
                    coEvery { mockRepo.getById(doc.id) } returns doc
                }

                coroutineScope {
                    val uploadJobs = (1..25).map { i ->
                        async(CoroutineName("mixed-upload-$i")) {
                            val response = client.post("/api/documents/upload") {
                                setBody(MultiPartFormDataContent(formData {
                                    append("file", "mixed-$i".toByteArray(), Headers.build {
                                        append(HttpHeaders.ContentType, "application/pdf")
                                        append(HttpHeaders.ContentDisposition, "filename=\"mixed-$i.pdf\"")
                                    })
                                }))
                            }
                            MixedResult(0, "upload", response.status.value)
                        }
                    }

                    val readJobs = readDocs.mapIndexed { i, doc ->
                        async(CoroutineName("mixed-read-$i")) {
                            val response = client.get("/api/documents/${doc.id}")
                            MixedResult(0, "read", response.status.value)
                        }
                    }

                    val results = (uploadJobs + readJobs).awaitAll()

                    results.forEach { r ->
                        withClue("op=${r.op} should return 200") {
                            r.status shouldBe 200
                        }
                    }
                }
            }
        }
    }

    // ── Concurrent list ──────────────────────────────────────
    context("concurrent list") {

        test("50 concurrent GET /documents with varied query params") {
            testApplication {
                setupApp()

                coEvery { mockRepo.list(any(), any(), any()) } returns listOf(testDocument(), testDocument())

                coroutineScope {
                    val results = (1..50).map { i ->
                        async(CoroutineName("list-$i")) {
                            val queryParams = when (i % 3) {
                                0 -> ""
                                1 -> "?classification=invoice"
                                else -> "?limit=10&offset=${i * 2}"
                            }
                            val response = client.get("/api/documents$queryParams")
                            ListResult(0, response.status.value)
                        }
                    }.awaitAll()

                    results.forEach { r ->
                        withClue("list request should return 200") {
                            r.status shouldBe 200
                        }
                    }
                }
            }
        }
    }
})
