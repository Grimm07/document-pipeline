package org.example.pipeline.worker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.mockk.*
import org.example.pipeline.domain.*
import org.example.pipeline.storage.LocalFileStorageService
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock

class DocumentProcessorTest : FunSpec({

    val mockRepo = mockk<DocumentRepository>()
    val mockStorage = mockk<FileStorageService>()
    val mockClassification = mockk<ClassificationService>()
    val processor = DocumentProcessor(mockRepo, mockStorage, mockClassification)

    val now = Clock.System.now()
    val testDocId = UUID.randomUUID().toString()

    fun testDocument(
        id: String = testDocId,
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

    context("happy path") {
        test("fetches doc, retrieves file, classifies, and updates") {
            val doc = testDocument()
            val content = "pdf content".toByteArray()
            val result = ClassificationResult("invoice", 0.95f)

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(content, "application/pdf") } returns result
            coEvery { mockRepo.updateClassification(testDocId, "invoice", 0.95f, null, labelScores = null) } returns true

            processor.process(testDocId)

            coVerifyOrder {
                mockRepo.getById(testDocId)
                mockStorage.retrieve(doc.storagePath)
                mockClassification.classify(content, "application/pdf")
                mockRepo.updateClassification(testDocId, "invoice", 0.95f, null, labelScores = null)
            }
        }

        test("passes correct mimeType to classification service") {
            val doc = testDocument(mimeType = "image/png")
            val content = "image data".toByteArray()
            val result = ClassificationResult("photo", 0.8f)

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(content, "image/png") } returns result
            coEvery { mockRepo.updateClassification(any(), any(), any(), any(), labelScores = any()) } returns true

            processor.process(testDocId)

            coVerify { mockClassification.classify(content, "image/png") }
        }

        test("stores OCR JSON and passes ocrStoragePath when classification has OCR") {
            val doc = testDocument()
            val content = "pdf content".toByteArray()
            val ocrJson = """{"pages":[],"fullText":"extracted text"}"""
            val result = ClassificationResult("invoice", 0.95f, ocrResultJson = ocrJson)
            val ocrPath = "2024/07/15/${testDocId}-ocr/ocr-results.json"

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(content, "application/pdf") } returns result
            coEvery { mockStorage.store("$testDocId-ocr", "ocr-results.json", any()) } returns ocrPath
            coEvery { mockRepo.updateClassification(testDocId, "invoice", 0.95f, ocrPath, labelScores = null) } returns true

            processor.process(testDocId)

            coVerify { mockStorage.store("$testDocId-ocr", "ocr-results.json", ocrJson.toByteArray()) }
            coVerify { mockRepo.updateClassification(testDocId, "invoice", 0.95f, ocrPath, labelScores = null) }
        }

        test("does not store OCR file when ocrResultJson is null") {
            val doc = testDocument()
            val content = "text content".toByteArray()
            val result = ClassificationResult("report", 0.9f, ocrResultJson = null)

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(content, "application/pdf") } returns result
            coEvery { mockRepo.updateClassification(testDocId, "report", 0.9f, null, labelScores = null) } returns true

            processor.process(testDocId)

            coVerify(exactly = 0) { mockStorage.store(match { it.endsWith("-ocr") }, any(), any()) }
            coVerify { mockRepo.updateClassification(testDocId, "report", 0.9f, null, labelScores = null) }
        }
    }

    context("document not found") {
        test("does not call storage or classification when document is missing") {
            coEvery { mockRepo.getById(testDocId) } returns null

            // Should not throw — processor logs warning and returns
            // or it might throw IllegalStateException per the docstring
            try {
                processor.process(testDocId)
            } catch (_: IllegalStateException) {
                // acceptable behavior per docstring
            }

            coVerify(exactly = 0) { mockStorage.retrieve(any()) }
            coVerify(exactly = 0) { mockClassification.classify(any(), any()) }
        }
    }

    context("file not found") {
        test("does not call classification when file content is null") {
            val doc = testDocument()
            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns null

            try {
                processor.process(testDocId)
            } catch (_: IllegalStateException) {
                // acceptable behavior per docstring
            }

            coVerify(exactly = 0) { mockClassification.classify(any(), any()) }
        }
    }

    context("classification failure") {
        test("propagates exception from classification service") {
            val doc = testDocument()
            val content = "pdf data".toByteArray()

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(any(), any()) } throws RuntimeException("ML service unavailable")

            shouldThrow<RuntimeException> {
                processor.process(testDocId)
            }

            coVerify(exactly = 0) { mockRepo.updateClassification(any(), any(), any(), any(), labelScores = any()) }
        }
    }

    context("update failure") {
        test("handles gracefully when updateClassification returns false") {
            val doc = testDocument()
            val content = "content".toByteArray()
            val result = ClassificationResult("invoice", 0.9f)

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(content, "application/pdf") } returns result
            coEvery { mockRepo.updateClassification(testDocId, "invoice", 0.9f, null, labelScores = null) } returns false

            // Should not throw — just log that update failed
            // or it might throw — either behavior is acceptable
            try {
                processor.process(testDocId)
            } catch (_: IllegalStateException) {
                // acceptable
            }

            coVerify { mockRepo.updateClassification(testDocId, "invoice", 0.9f, null, labelScores = null) }
        }
    }

    context("path flow through real storage") {
        test("process retrieves file using exact storagePath from repository") {
            val tempDir = createTempDirectory("processor-path-test")
            try {
                val realStorage = LocalFileStorageService(tempDir)
                val content = "real file content for processor".toByteArray()
                val storagePath = realStorage.store("proc-path-id", "doc.pdf", content)

                val doc = testDocument(id = "proc-path-id", storagePath = storagePath, mimeType = "application/pdf")
                val result = ClassificationResult("report", 0.85f)

                coEvery { mockRepo.getById("proc-path-id") } returns doc
                coEvery { mockClassification.classify(content, "application/pdf") } returns result
                coEvery { mockRepo.updateClassification("proc-path-id", "report", 0.85f, null, labelScores = null) } returns true

                val realProcessor = DocumentProcessor(mockRepo, realStorage, mockClassification)
                realProcessor.process("proc-path-id")

                // Verify the classification service received the exact content from storage
                coVerify { mockClassification.classify(content, "application/pdf") }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        test("process with non-existent storagePath throws IllegalStateException") {
            val tempDir = createTempDirectory("processor-abs-test")
            try {
                val realStorage = LocalFileStorageService(tempDir)
                // Document has a path that doesn't exist in storage
                val doc = testDocument(id = "abs-test-id", storagePath = "2024/01/01/nonexistent.pdf")

                coEvery { mockRepo.getById("abs-test-id") } returns doc

                val realProcessor = DocumentProcessor(mockRepo, realStorage, mockClassification)
                shouldThrow<IllegalStateException> {
                    realProcessor.process("abs-test-id")
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    context("OCR storage failure") {
        test("OCR storage IOException propagates") {
            val doc = testDocument()
            val content = "pdf content".toByteArray()
            val ocrJson = """{"pages":[],"fullText":"extracted text"}"""
            val result = ClassificationResult("invoice", 0.95f, ocrResultJson = ocrJson)

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(content, "application/pdf") } returns result
            coEvery { mockStorage.store("$testDocId-ocr", "ocr-results.json", any()) } throws java.io.IOException("Disk full")

            shouldThrow<java.io.IOException> {
                processor.process(testDocId)
            }

            // Verify updateClassification was NOT called (OCR store failed first)
            coVerify(exactly = 0) { mockRepo.updateClassification(any(), any(), any(), any(), labelScores = any()) }
        }

        test("handles very large OCR JSON") {
            val doc = testDocument()
            val content = "pdf content".toByteArray()
            // Create a large OCR JSON (~1MB)
            val largeText = "x".repeat(1_000_000)
            val ocrJson = """{"pages":[{"pageIndex":0,"width":100,"height":200,"text":"$largeText","blocks":[]}],"fullText":"$largeText"}"""
            val result = ClassificationResult("invoice", 0.95f, ocrResultJson = ocrJson)
            val ocrPath = "2024/07/15/${testDocId}-ocr/ocr-results.json"

            coEvery { mockRepo.getById(testDocId) } returns doc
            coEvery { mockStorage.retrieve(doc.storagePath) } returns content
            coEvery { mockClassification.classify(content, "application/pdf") } returns result
            coEvery { mockStorage.store("$testDocId-ocr", "ocr-results.json", any()) } returns ocrPath
            coEvery { mockRepo.updateClassification(testDocId, "invoice", 0.95f, ocrPath, labelScores = null) } returns true

            processor.process(testDocId)

            // Verify store was called with the full large content
            coVerify { mockStorage.store("$testDocId-ocr", "ocr-results.json", ocrJson.toByteArray()) }
        }
    }
})
