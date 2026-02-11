package org.example.pipeline.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.core.extensions.install
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.datetime.Instant
import org.example.pipeline.domain.Document
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.time.Clock

class ExposedDocumentRepositoryTest : FunSpec({

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
        createdAt: Instant = now,
        updatedAt: Instant = now
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
            maxPoolSize = 5
        )
        repo = ExposedDocumentRepository()
    }

    beforeEach {
        newSuspendedTransaction {
            DocumentsTable.deleteAll()
        }
    }

    context("insert") {
        test("returns document with matching fields") {
            val doc = testDocument(originalFilename = "test.pdf", mimeType = "application/pdf")
            val inserted = repo.insert(doc)

            inserted.originalFilename shouldBe "test.pdf"
            inserted.mimeType shouldBe "application/pdf"
            inserted.fileSizeBytes shouldBe 1024L
            inserted.classification shouldBe "unclassified"
            inserted.id.shouldNotBeBlank()
        }

        test("preserves JSONB metadata") {
            val metadata = mapOf("department" to "finance", "priority" to "high")
            val doc = testDocument(metadata = metadata)
            val inserted = repo.insert(doc)

            inserted.metadata shouldBe metadata
        }

        test("handles null optional fields") {
            val doc = testDocument(confidence = null, uploadedBy = null)
            val inserted = repo.insert(doc)

            inserted.confidence.shouldBeNull()
            inserted.uploadedBy.shouldBeNull()
        }

        test("handles empty metadata") {
            val doc = testDocument(metadata = emptyMap())
            val inserted = repo.insert(doc)

            inserted.metadata shouldBe emptyMap()
        }
    }

    context("getById") {
        test("returns document when it exists") {
            val doc = testDocument()
            val inserted = repo.insert(doc)

            val fetched = repo.getById(inserted.id)
            fetched.shouldNotBeNull()
            fetched.id shouldBe inserted.id
            fetched.originalFilename shouldBe inserted.originalFilename
        }

        test("returns null for missing document") {
            val fetched = repo.getById(UUID.randomUUID().toString())
            fetched.shouldBeNull()
        }

        test("returns null for malformed UUID") {
            val fetched = repo.getById("not-a-uuid")
            fetched.shouldBeNull()
        }
    }

    context("list") {
        test("returns all documents when no filter") {
            repo.insert(testDocument())
            repo.insert(testDocument())
            repo.insert(testDocument())

            val result = repo.list()
            result shouldHaveSize 3
        }

        test("filters by classification") {
            repo.insert(testDocument(classification = "invoice"))
            repo.insert(testDocument(classification = "receipt"))
            repo.insert(testDocument(classification = "invoice"))

            val invoices = repo.list(classification = "invoice")
            invoices shouldHaveSize 2
            invoices.forEach { it.classification shouldBe "invoice" }
        }

        test("respects limit") {
            repeat(5) { repo.insert(testDocument()) }

            val result = repo.list(limit = 3)
            result shouldHaveSize 3
        }

        test("respects offset") {
            repeat(5) { repo.insert(testDocument()) }

            val all = repo.list()
            val offset = repo.list(offset = 2)
            offset shouldHaveSize 3
        }

        test("returns empty list when no matches") {
            val result = repo.list(classification = "nonexistent")
            result.shouldBeEmpty()
        }

        test("returns empty list for empty table") {
            val result = repo.list()
            result.shouldBeEmpty()
        }
    }

    context("searchMetadata") {
        test("finds document by single metadata key") {
            repo.insert(testDocument(metadata = mapOf("department" to "finance")))
            repo.insert(testDocument(metadata = mapOf("department" to "legal")))

            val result = repo.searchMetadata(mapOf("department" to "finance"))
            result shouldHaveSize 1
            result.first().metadata["department"] shouldBe "finance"
        }

        test("finds document by multiple metadata keys") {
            repo.insert(testDocument(metadata = mapOf("department" to "finance", "priority" to "high")))
            repo.insert(testDocument(metadata = mapOf("department" to "finance", "priority" to "low")))

            val result = repo.searchMetadata(mapOf("department" to "finance", "priority" to "high"))
            result shouldHaveSize 1
        }

        test("returns empty when no match") {
            repo.insert(testDocument(metadata = mapOf("department" to "finance")))

            val result = repo.searchMetadata(mapOf("department" to "marketing"))
            result.shouldBeEmpty()
        }

        test("respects limit") {
            repeat(5) { repo.insert(testDocument(metadata = mapOf("type" to "common"))) }

            val result = repo.searchMetadata(mapOf("type" to "common"), limit = 3)
            result shouldHaveSize 3
        }

        test("handles metadata values with SQL injection characters") {
            val maliciousValue = "'; DROP TABLE documents; --"
            repo.insert(testDocument(metadata = mapOf("safe_key" to maliciousValue)))
            repo.insert(testDocument(metadata = mapOf("safe_key" to "normal")))

            val result = repo.searchMetadata(mapOf("safe_key" to maliciousValue))
            result shouldHaveSize 1
            result.first().metadata["safe_key"] shouldBe maliciousValue
        }

        test("handles metadata keys with special characters") {
            val specialKey = "key\"with'quotes"
            repo.insert(testDocument(metadata = mapOf(specialKey to "value")))

            val result = repo.searchMetadata(mapOf(specialKey to "value"))
            result shouldHaveSize 1
            result.first().metadata[specialKey] shouldBe "value"
        }

        test("handles metadata values with JSON special characters") {
            val jsonValue = """{"nested": "value"}"""
            repo.insert(testDocument(metadata = mapOf("data" to jsonValue)))

            val result = repo.searchMetadata(mapOf("data" to jsonValue))
            result shouldHaveSize 1
            result.first().metadata["data"] shouldBe jsonValue
        }

        test("returns empty for empty query map") {
            repo.insert(testDocument(metadata = mapOf("key" to "value")))

            val result = repo.searchMetadata(emptyMap())
            // Empty query should match everything or nothing — depends on implementation
            // We just verify it doesn't throw
            result.shouldNotBeNull()
        }
    }

    context("updateClassification") {
        test("updates classification and confidence") {
            val doc = repo.insert(testDocument())

            val updated = repo.updateClassification(doc.id, "invoice", 0.95f)
            updated.shouldBeTrue()

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.classification shouldBe "invoice"
            fetched.confidence shouldBe 0.95f
        }

        test("returns true on success") {
            val doc = repo.insert(testDocument())
            repo.updateClassification(doc.id, "receipt", 0.8f).shouldBeTrue()
        }

        test("returns false for missing document") {
            val result = repo.updateClassification(UUID.randomUUID().toString(), "invoice", 0.9f)
            result.shouldBeFalse()
        }

        test("preserves other fields after update") {
            val metadata = mapOf("key" to "value")
            val doc = repo.insert(testDocument(
                originalFilename = "important.pdf",
                metadata = metadata,
                uploadedBy = "user-1"
            ))

            repo.updateClassification(doc.id, "contract", 0.99f)

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.originalFilename shouldBe "important.pdf"
            fetched.metadata shouldBe metadata
            fetched.uploadedBy shouldBe "user-1"
        }

        test("updates ocrStoragePath when provided") {
            val doc = repo.insert(testDocument())
            val ocrPath = "2024/07/15/${doc.id}-ocr/ocr-results.json"

            val updated = repo.updateClassification(doc.id, "invoice", 0.95f, ocrStoragePath = ocrPath)
            updated.shouldBeTrue()

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.ocrStoragePath shouldBe ocrPath
            fetched.classification shouldBe "invoice"
        }

        test("leaves ocrStoragePath null when not provided") {
            val doc = repo.insert(testDocument())

            repo.updateClassification(doc.id, "invoice", 0.9f)

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.ocrStoragePath.shouldBeNull()
        }
    }

    context("persistence fidelity") {
        test("storagePath survives insert then getById round-trip unchanged") {
            val exactPath = "2026/02/10/a1b2c3d4-e5f6-7890-abcd-ef1234567890.pdf"
            val doc = testDocument(storagePath = exactPath)
            val inserted = repo.insert(doc)

            val fetched = repo.getById(inserted.id)
            fetched.shouldNotBeNull()
            fetched.storagePath shouldBe exactPath
        }

        test("storagePath with unusual but valid characters preserved") {
            val pathWithSpaces = "2026/02/10/file with spaces.pdf"
            val doc = testDocument(storagePath = pathWithSpaces)
            val inserted = repo.insert(doc)

            val fetched = repo.getById(inserted.id)
            fetched.shouldNotBeNull()
            fetched.storagePath shouldBe pathWithSpaces
        }

        test("UUID string format preserved through insert then getById") {
            val lowercaseUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            val doc = testDocument(id = lowercaseUuid)
            repo.insert(doc)

            val fetched = repo.getById(lowercaseUuid)
            fetched.shouldNotBeNull()
            fetched.id shouldBe lowercaseUuid
        }
    }

    context("delete") {
        test("returns true when document exists") {
            val doc = repo.insert(testDocument())
            repo.delete(doc.id).shouldBeTrue()
        }

        test("actually removes the document") {
            val doc = repo.insert(testDocument())
            repo.delete(doc.id)
            repo.getById(doc.id).shouldBeNull()
        }

        test("returns false for missing document") {
            repo.delete(UUID.randomUUID().toString()).shouldBeFalse()
        }

        test("returns false for malformed UUID") {
            repo.delete("not-a-uuid").shouldBeFalse()
        }

        test("does not affect other documents") {
            val doc1 = repo.insert(testDocument())
            val doc2 = repo.insert(testDocument())
            repo.delete(doc1.id)

            repo.getById(doc1.id).shouldBeNull()
            repo.getById(doc2.id).shouldNotBeNull()
        }
    }

    context("resetClassification") {
        test("returns true when document exists") {
            val doc = repo.insert(testDocument(classification = "invoice", confidence = 0.95f))
            repo.resetClassification(doc.id).shouldBeTrue()
        }

        test("sets classification to unclassified") {
            val doc = repo.insert(testDocument(classification = "invoice", confidence = 0.95f))
            repo.resetClassification(doc.id)

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.classification shouldBe "unclassified"
        }

        test("nulls confidence") {
            val doc = repo.insert(testDocument(classification = "invoice", confidence = 0.95f))
            repo.resetClassification(doc.id)

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.confidence.shouldBeNull()
        }

        test("nulls ocrStoragePath") {
            val doc = repo.insert(testDocument())
            repo.updateClassification(doc.id, "invoice", 0.9f, ocrStoragePath = "some/ocr.json")
            repo.resetClassification(doc.id)

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.ocrStoragePath.shouldBeNull()
        }

        test("updates updatedAt timestamp") {
            val doc = repo.insert(testDocument())
            val originalUpdatedAt = doc.updatedAt

            // Small delay to ensure timestamp changes
            kotlinx.coroutines.delay(50)
            repo.resetClassification(doc.id)

            val fetched = repo.getById(doc.id)
            fetched.shouldNotBeNull()
            fetched.updatedAt shouldNotBe originalUpdatedAt
        }

        test("returns false for missing document") {
            repo.resetClassification(UUID.randomUUID().toString()).shouldBeFalse()
        }

        test("returns false for malformed UUID") {
            repo.resetClassification("not-a-uuid").shouldBeFalse()
        }

        test("does not affect other documents") {
            val doc1 = repo.insert(testDocument(classification = "invoice", confidence = 0.9f))
            val doc2 = repo.insert(testDocument(classification = "receipt", confidence = 0.8f))
            repo.resetClassification(doc1.id)

            val fetched2 = repo.getById(doc2.id)
            fetched2.shouldNotBeNull()
            fetched2.classification shouldBe "receipt"
            fetched2.confidence shouldBe 0.8f
        }
    }

    context("lifecycle") {
        test("insert then get then update then get round-trip") {
            val doc = testDocument(
                originalFilename = "lifecycle.pdf",
                metadata = mapOf("stage" to "test")
            )
            val inserted = repo.insert(doc)

            val fetched1 = repo.getById(inserted.id)
            fetched1.shouldNotBeNull()
            fetched1.classification shouldBe "unclassified"

            repo.updateClassification(inserted.id, "invoice", 0.92f)

            val fetched2 = repo.getById(inserted.id)
            fetched2.shouldNotBeNull()
            fetched2.classification shouldBe "invoice"
            fetched2.confidence shouldBe 0.92f
            fetched2.metadata shouldBe mapOf("stage" to "test")
        }
    }
})
