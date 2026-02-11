package org.example.pipeline.worker

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.pipeline.domain.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock

private data class ProcessResult(val clientId: Int, val docId: String, val success: Boolean)

class DocumentProcessorStressTest : FunSpec({

    val mockRepo = mockk<DocumentRepository>()
    val mockStorage = mockk<FileStorageService>()
    val mockClassification = mockk<ClassificationService>()
    val processor = DocumentProcessor(mockRepo, mockStorage, mockClassification)

    val now = Clock.System.now()

    fun testDocument(
        id: String = UUID.randomUUID().toString(),
        storagePath: String = "2024/07/15/$id.pdf",
        mimeType: String = "application/pdf"
    ) = Document(
        id = id,
        storagePath = storagePath,
        originalFilename = "report.pdf",
        mimeType = mimeType,
        fileSizeBytes = 1024L,
        createdAt = now,
        updatedAt = now
    )

    beforeEach {
        clearAllMocks()
    }

    // ── Concurrent process ───────────────────────────────────
    context("concurrent process") {

        test("50 concurrent process() calls with different doc IDs") {
            val ids = (1..50).map { UUID.randomUUID().toString() }

            ids.forEach { id ->
                val doc = testDocument(id = id)
                coEvery { mockRepo.getById(id) } returns doc
                coEvery { mockStorage.retrieve(doc.storagePath) } returns "content-$id".toByteArray()
            }
            coEvery { mockClassification.classify(any(), any()) } returns ClassificationResult("invoice", 0.95f)
            coEvery { mockRepo.updateClassification(any(), any(), any()) } returns true

            coroutineScope {
                val results = ids.mapIndexed { i, docId ->
                    async(CoroutineName("process-$i")) {
                        processor.process(docId)
                        ProcessResult(0, docId, true)
                    }
                }.awaitAll()

                results.forEach { r ->
                    withClue("docId=${r.docId}") {
                        r.success shouldBe true
                    }
                }
            }

            // Verify all 50 went through the full 4-step pipeline
            coVerify(exactly = 50) { mockRepo.getById(any()) }
            coVerify(exactly = 50) { mockStorage.retrieve(any()) }
            coVerify(exactly = 50) { mockClassification.classify(any(), any()) }
            coVerify(exactly = 50) { mockRepo.updateClassification(any(), any(), any()) }
        }

        test("10 clients x 10 process() calls each") {
            coEvery { mockRepo.getById(any()) } answers {
                val id: String = firstArg()
                testDocument(id = id)
            }
            coEvery { mockStorage.retrieve(any()) } returns "file-content".toByteArray()
            coEvery { mockClassification.classify(any(), any()) } returns ClassificationResult("receipt", 0.88f)
            coEvery { mockRepo.updateClassification(any(), any(), any()) } returns true

            coroutineScope {
                val results = (1..10).map { clientId ->
                    async(CoroutineName("client-$clientId")) {
                        (1..10).map { opId ->
                            async(CoroutineName("client-$clientId/op-$opId")) {
                                val docId = UUID.randomUUID().toString()
                                processor.process(docId)
                                ProcessResult(clientId, docId, true)
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                results.forEach { r ->
                    withClue("client=${r.clientId}, docId=${r.docId}") {
                        r.success shouldBe true
                    }
                }
            }

            coVerify(exactly = 100) { mockRepo.getById(any()) }
            coVerify(exactly = 100) { mockClassification.classify(any(), any()) }
            coVerify(exactly = 100) { mockRepo.updateClassification(any(), any(), any()) }
        }
    }

    // ── Mixed success/failure ────────────────────────────────
    context("mixed success/failure") {

        test("25 succeed + 25 missing docs: no interference") {
            val successIds = (1..25).map { UUID.randomUUID().toString() }
            val missingIds = (1..25).map { UUID.randomUUID().toString() }

            successIds.forEach { id ->
                val doc = testDocument(id = id)
                coEvery { mockRepo.getById(id) } returns doc
                coEvery { mockStorage.retrieve(doc.storagePath) } returns "data-$id".toByteArray()
            }
            missingIds.forEach { id ->
                coEvery { mockRepo.getById(id) } returns null
            }
            coEvery { mockClassification.classify(any(), any()) } returns ClassificationResult("invoice", 0.9f)
            coEvery { mockRepo.updateClassification(any(), any(), any()) } returns true

            val succeeded = CopyOnWriteArrayList<String>()
            val skipped = CopyOnWriteArrayList<String>()

            coroutineScope {
                val allIds = successIds + missingIds
                allIds.mapIndexed { i, docId ->
                    async(CoroutineName("mixed-$i")) {
                        processor.process(docId)
                        if (docId in successIds) {
                            succeeded.add(docId)
                        } else {
                            skipped.add(docId)
                        }
                    }
                }.awaitAll()
            }

            withClue("25 successful docs should complete the pipeline") {
                succeeded.size shouldBe 25
            }
            withClue("25 missing docs should be handled gracefully") {
                skipped.size shouldBe 25
            }

            // Classification should only be called for found docs
            coVerify(exactly = 25) { mockClassification.classify(any(), any()) }
            coVerify(exactly = 25) { mockRepo.updateClassification(any(), any(), any()) }
        }
    }

    // ── Same document ────────────────────────────────────────
    context("same document") {

        test("10 concurrent process(sameId): no crash, all complete") {
            val sameId = UUID.randomUUID().toString()
            val doc = testDocument(id = sameId)

            coEvery { mockRepo.getById(sameId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns "shared-content".toByteArray()
            coEvery { mockClassification.classify(any(), any()) } returns ClassificationResult("contract", 0.92f)
            coEvery { mockRepo.updateClassification(any(), any(), any()) } returns true

            coroutineScope {
                val results = (1..10).map { i ->
                    async(CoroutineName("same-doc-$i")) {
                        processor.process(sameId)
                        ProcessResult(i, sameId, true)
                    }
                }.awaitAll()

                results.forEach { r ->
                    withClue("client=${r.clientId}, docId=${r.docId}") {
                        r.success shouldBe true
                    }
                }
            }

            coVerify(exactly = 10) { mockRepo.getById(sameId) }
            coVerify(exactly = 10) { mockStorage.retrieve(doc.storagePath) }
            coVerify(exactly = 10) { mockClassification.classify(any(), any()) }
            coVerify(exactly = 10) { mockRepo.updateClassification(sameId, "contract", 0.92f) }
        }
    }
})
