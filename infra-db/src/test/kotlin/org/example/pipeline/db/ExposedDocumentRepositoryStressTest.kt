package org.example.pipeline.db

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.extensions.install
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.pipeline.domain.Document
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.time.Clock

private data class InsertResult(val clientId: Int, val docId: String)
private data class ReadResult(val clientId: Int, val docId: String, val found: Boolean)
private data class UpdateResult(val clientId: Int, val docId: String, val success: Boolean)
private data class PipelineResult(
    val clientId: Int,
    val docId: String,
    val insertedId: String,
    val fetchedClassification: String?,
    val updatedSuccess: Boolean,
    val finalClassification: String?,
)

class ExposedDocumentRepositoryStressTest : FunSpec({

    val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    install(TestContainerSpecExtension(postgres))
    lateinit var repo: ExposedDocumentRepository

    val now = Clock.System.now()

    fun testDocument(
        id: String = UUID.randomUUID().toString(),
        storagePath: String = "2024/07/15/$id.pdf",
        originalFilename: String = "report.pdf",
        mimeType: String = "application/pdf",
        fileSizeBytes: Long = 1024L,
        classification: String = "unclassified",
        confidence: Float? = null,
        metadata: Map<String, String> = emptyMap(),
        uploadedBy: String? = null,
        createdAt: kotlin.time.Instant = now,
        updatedAt: kotlin.time.Instant = now
    ) = Document(
        id = id,
        storagePath = storagePath,
        originalFilename = originalFilename,
        mimeType = mimeType,
        fileSizeBytes = fileSizeBytes,
        classification = classification,
        confidence = confidence,
        metadata = metadata,
        uploadedBy = uploadedBy,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    beforeSpec {
        DatabaseConfig.init(
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
            maxPoolSize = 10
        )
        repo = ExposedDocumentRepository()
    }

    beforeEach {
        newSuspendedTransaction {
            DocumentsTable.deleteAll()
        }
    }

    // ── Per-operation stress: insert ─────────────────────────
    context("concurrent insert") {

        test("single client: 50 inserts complete without error") {
            coroutineScope {
                val results = (1..50).map { i ->
                    async(CoroutineName("insert-$i")) {
                        val id = UUID.randomUUID().toString()
                        val doc = testDocument(id = id)
                        val inserted = repo.insert(doc)
                        InsertResult(0, inserted.id)
                    }
                }.awaitAll()

                results.shouldHaveSize(50)
                results.forEach { r ->
                    withClue("docId=${r.docId}") {
                        r.docId.shouldNotBeBlank()
                    }
                }

                // Verify all 50 exist in DB
                val allDocs = repo.list(limit = 100)
                withClue("all 50 docs should be in DB") {
                    allDocs.shouldHaveSize(50)
                }
            }
        }

        test("multi-client: 10 clients x 20 inserts each") {
            coroutineScope {
                val results = (1..10).map { clientId ->
                    async(CoroutineName("client-$clientId")) {
                        (1..20).map { opId ->
                            async(CoroutineName("client-$clientId/op-$opId")) {
                                val id = UUID.randomUUID().toString()
                                val doc = testDocument(id = id)
                                val inserted = repo.insert(doc)
                                InsertResult(clientId, inserted.id)
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                results.shouldHaveSize(200)
                results.forEach { r ->
                    withClue("client=${r.clientId}, docId=${r.docId}") {
                        r.docId.shouldNotBeBlank()
                    }
                }

                val allDocs = repo.list(limit = 300)
                withClue("all 200 docs should be in DB") {
                    allDocs.shouldHaveSize(200)
                }
            }
        }
    }

    // ── Per-operation stress: read ───────────────────────────
    context("concurrent read") {

        test("50 reads of different docs all return correct data") {
            // Pre-seed 50 docs
            val seeded = coroutineScope {
                (1..50).map { i ->
                    async(CoroutineName("seed-$i")) {
                        val id = UUID.randomUUID().toString()
                        val doc = testDocument(id = id, originalFilename = "file-$i.pdf")
                        repo.insert(doc)
                        id
                    }
                }.awaitAll()
            }

            coroutineScope {
                val results = seeded.mapIndexed { i, docId ->
                    async(CoroutineName("read-$i")) {
                        val fetched = repo.getById(docId)
                        ReadResult(0, docId, fetched != null)
                    }
                }.awaitAll()

                results.shouldHaveSize(50)
                results.forEach { r ->
                    withClue("docId=${r.docId}") {
                        r.found.shouldBeTrue()
                    }
                }
            }
        }

        test("multi-client: 10 clients x 20 reads each") {
            // Pre-seed 200 docs
            val seeded = coroutineScope {
                (1..200).map { i ->
                    async(CoroutineName("seed-$i")) {
                        val id = UUID.randomUUID().toString()
                        repo.insert(testDocument(id = id))
                        id
                    }
                }.awaitAll()
            }

            coroutineScope {
                val results = (1..10).map { clientId ->
                    async(CoroutineName("client-$clientId")) {
                        val slice = seeded.subList((clientId - 1) * 20, clientId * 20)
                        slice.mapIndexed { opId, docId ->
                            async(CoroutineName("client-$clientId/op-$opId")) {
                                val fetched = repo.getById(docId)
                                ReadResult(clientId, docId, fetched != null)
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                results.shouldHaveSize(200)
                results.forEach { r ->
                    withClue("client=${r.clientId}, docId=${r.docId}") {
                        r.found.shouldBeTrue()
                    }
                }
            }
        }
    }

    // ── Per-operation stress: update ─────────────────────────
    context("concurrent update") {

        test("50 updates to different docs all succeed") {
            // Pre-seed 50 docs
            val seeded = coroutineScope {
                (1..50).map { i ->
                    async(CoroutineName("seed-$i")) {
                        val id = UUID.randomUUID().toString()
                        repo.insert(testDocument(id = id))
                        id
                    }
                }.awaitAll()
            }

            coroutineScope {
                val results = seeded.mapIndexed { i, docId ->
                    async(CoroutineName("update-$i")) {
                        val success = repo.updateClassification(docId, "invoice", 0.9f)
                        UpdateResult(0, docId, success)
                    }
                }.awaitAll()

                results.shouldHaveSize(50)
                results.forEach { r ->
                    withClue("docId=${r.docId}") {
                        r.success.shouldBeTrue()
                    }
                }
            }

            // Verify all updated
            val allDocs = repo.list(classification = "invoice", limit = 100)
            withClue("all 50 should be classified as invoice") {
                allDocs.shouldHaveSize(50)
            }
        }

        test("10 concurrent updates to same doc: exactly one wins (idempotency guard)") {
            val id = UUID.randomUUID().toString()
            repo.insert(testDocument(id = id))

            val classifications = listOf(
                "invoice", "receipt", "contract", "memo", "report",
                "letter", "form", "notice", "statement", "summary"
            )

            coroutineScope {
                val results = classifications.mapIndexed { i, cls ->
                    async(CoroutineName("update-$i")) {
                        val success = repo.updateClassification(id, cls, (i + 1) * 0.1f)
                        UpdateResult(i, id, success)
                    }
                }.awaitAll()

                // Idempotency guard: exactly one update should succeed (the first to run)
                val successes = results.filter { it.success }
                withClue("exactly one concurrent update should win") {
                    successes.shouldHaveSize(1)
                }
            }

            // Final state: one of the classifications should be present
            val doc = repo.getById(id)
            doc.shouldNotBeNull()
            withClue("final classification should be one of the written values") {
                classifications.contains(doc.classification).shouldBeTrue()
            }
        }
    }

    // ── Mixed pipeline ───────────────────────────────────────
    context("mixed pipeline") {

        test("10 clients: insert -> get -> update -> get concurrently") {
            coroutineScope {
                val results = (1..10).map { clientId ->
                    async(CoroutineName("pipeline-client-$clientId")) {
                        (1..10).map { opId ->
                            async(CoroutineName("pipeline-client-$clientId/op-$opId")) {
                                val id = UUID.randomUUID().toString()
                                val doc = testDocument(id = id)

                                // 1. Insert
                                val inserted = repo.insert(doc)

                                // 2. Get — should be unclassified
                                val fetched1 = repo.getById(inserted.id)
                                val initialClassification = fetched1?.classification

                                // 3. Update classification
                                val updated = repo.updateClassification(
                                    inserted.id,
                                    "invoice-$clientId",
                                    0.8f + (opId * 0.01f)
                                )

                                // 4. Get again — should be updated
                                val fetched2 = repo.getById(inserted.id)
                                val finalClassification = fetched2?.classification

                                PipelineResult(
                                    clientId = clientId,
                                    docId = id,
                                    insertedId = inserted.id,
                                    fetchedClassification = initialClassification,
                                    updatedSuccess = updated,
                                    finalClassification = finalClassification
                                )
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                results.shouldHaveSize(100)
                results.forEach { r ->
                    withClue("client=${r.clientId}, docId=${r.docId}") {
                        r.insertedId.shouldNotBeBlank()
                        r.fetchedClassification shouldBe "unclassified"
                        r.updatedSuccess.shouldBeTrue()
                        r.finalClassification shouldBe "invoice-${r.clientId}"
                    }
                }
            }
        }
    }
})
