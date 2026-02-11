package org.example.pipeline.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.forAll
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

class DocumentTest : FunSpec({

    val json = Json { encodeDefaults = true }
    val now = Clock.System.now()

    fun testDocument(
        id: String = "test-id",
        storagePath: String = "2024/07/15/test-id.pdf",
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

    context("default values") {
        test("classification defaults to unclassified") {
            val doc = testDocument()
            doc.classification shouldBe "unclassified"
        }

        test("confidence defaults to null") {
            val doc = testDocument()
            doc.confidence shouldBe null
        }

        test("metadata defaults to empty map") {
            val doc = testDocument()
            doc.metadata shouldBe emptyMap()
        }

        test("uploadedBy defaults to null") {
            val doc = testDocument()
            doc.uploadedBy shouldBe null
        }
    }

    context("serialization") {
        test("round-trip with all fields populated") {
            val doc = testDocument(
                classification = "invoice",
                confidence = 0.95f,
                metadata = mapOf("department" to "finance", "priority" to "high"),
                uploadedBy = "user-123"
            )
            val serialized = json.encodeToString(doc)
            val deserialized = json.decodeFromString<Document>(serialized)
            deserialized shouldBe doc
        }

        test("round-trip with defaults and nulls") {
            val doc = testDocument()
            val serialized = json.encodeToString(doc)
            val deserialized = json.decodeFromString<Document>(serialized)
            deserialized shouldBe doc
        }

        test("metadata preservation through serialization") {
            val metadata = mapOf(
                "key1" to "value1",
                "key with spaces" to "value with spaces",
                "unicode" to "日本語テスト"
            )
            val doc = testDocument(metadata = metadata)
            val serialized = json.encodeToString(doc)
            val deserialized = json.decodeFromString<Document>(serialized)
            deserialized.metadata shouldBe metadata
        }

        test("Instant precision is preserved") {
            val precise = Instant.parse("2024-07-15T12:30:45.123456789Z")
            val doc = testDocument(createdAt = precise, updatedAt = precise)
            val serialized = json.encodeToString(doc)
            val deserialized = json.decodeFromString<Document>(serialized)
            deserialized.createdAt shouldBe precise
            deserialized.updatedAt shouldBe precise
        }
    }

    context("edge cases") {
        test("empty strings for text fields") {
            val doc = testDocument(
                id = "",
                storagePath = "",
                originalFilename = "",
                mimeType = ""
            )
            doc.id shouldBe ""
            doc.storagePath shouldBe ""
        }

        test("zero fileSizeBytes") {
            val doc = testDocument(fileSizeBytes = 0L)
            doc.fileSizeBytes shouldBe 0L
        }

        test("Long.MAX_VALUE fileSizeBytes") {
            val doc = testDocument(fileSizeBytes = Long.MAX_VALUE)
            doc.fileSizeBytes shouldBe Long.MAX_VALUE
        }

        test("special characters in metadata values") {
            val metadata = mapOf(
                "sql" to "'; DROP TABLE documents; --",
                "html" to "<script>alert('xss')</script>",
                "newline" to "line1\nline2"
            )
            val doc = testDocument(metadata = metadata)
            val serialized = json.encodeToString(doc)
            val deserialized = json.decodeFromString<Document>(serialized)
            deserialized.metadata shouldBe metadata
        }
    }

    context("copy") {
        test("copy produces independent instance with updated fields") {
            val original = testDocument(classification = "invoice", confidence = 0.9f)
            val updated = original.copy(classification = "receipt", confidence = 0.85f)

            original.classification shouldBe "invoice"
            original.confidence shouldBe 0.9f
            updated.classification shouldBe "receipt"
            updated.confidence shouldBe 0.85f
            updated.id shouldBe original.id
        }

        test("copy with new metadata does not affect original") {
            val original = testDocument(metadata = mapOf("a" to "1"))
            val updated = original.copy(metadata = mapOf("b" to "2"))

            original.metadata shouldBe mapOf("a" to "1")
            updated.metadata shouldBe mapOf("b" to "2")
        }
    }

    context("property-based") {
        test("serialization round-trip preserves all fields") {
            forAll(
                Arb.uuid(),
                Arb.string(1..100),
                Arb.string(1..100),
                Arb.long(0L..Long.MAX_VALUE)
            ) { uuid, filename, mimeType, fileSize ->
                val doc = testDocument(
                    id = uuid.toString(),
                    storagePath = "2024/01/01/${uuid}.pdf",
                    originalFilename = filename,
                    mimeType = mimeType,
                    fileSizeBytes = fileSize
                )
                val serialized = json.encodeToString(doc)
                val deserialized = json.decodeFromString<Document>(serialized)
                deserialized == doc
            }
        }

        test("arbitrary confidence values in valid range preserved") {
            forAll(Arb.float(0.0f, 1.0f).filter { !it.isNaN() }) { conf ->
                val doc = testDocument(confidence = conf)
                val serialized = json.encodeToString(doc)
                val deserialized = json.decodeFromString<Document>(serialized)
                deserialized.confidence == conf
            }
        }

        test("Instant values survive round-trip") {
            forAll(Arb.long(0L..4102444800L)) { epochSeconds ->
                val instant = Instant.fromEpochSeconds(epochSeconds)
                val doc = testDocument(createdAt = instant, updatedAt = instant)
                val serialized = json.encodeToString(doc)
                val deserialized = json.decodeFromString<Document>(serialized)
                deserialized.createdAt == instant && deserialized.updatedAt == instant
            }
        }

        test("copy with modified field preserves unmodified fields") {
            forAll(Arb.uuid(), Arb.string(1..50)) { uuid, classification ->
                val doc = testDocument(id = uuid.toString(), metadata = mapOf("key" to "value"))
                val copied = doc.copy(classification = classification)
                copied.id == doc.id && copied.metadata == doc.metadata && copied.classification == classification
            }
        }
    }
})
