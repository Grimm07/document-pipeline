package org.example.pipeline.api.validation

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import org.example.pipeline.api.dto.CorrectClassificationRequest
import org.example.pipeline.api.dto.ListQueryParams
import org.example.pipeline.api.dto.SearchQueryParams
import org.example.pipeline.api.dto.UploadParams

class ValidatorsTest : FunSpec({

    context("validateDocumentId") {
        test("accepts valid UUID") {
            shouldNotThrowAny {
                "550e8400-e29b-41d4-a716-446655440000".validate(validateDocumentId)
            }
        }

        test("rejects non-UUID string") {
            val ex = shouldThrow<ValidationException> {
                "not-a-uuid".validate(validateDocumentId)
            }
            ex.fieldErrors shouldContainKey ""
        }

        test("rejects empty string") {
            shouldThrow<ValidationException> {
                "".validate(validateDocumentId)
            }
        }
    }

    context("validateCorrectClassification") {
        test("accepts valid classification") {
            shouldNotThrowAny {
                CorrectClassificationRequest("invoice").validate(validateCorrectClassification)
            }
        }

        test("rejects blank classification") {
            val ex = shouldThrow<ValidationException> {
                CorrectClassificationRequest("   ").validate(validateCorrectClassification)
            }
            ex.fieldErrors shouldContainKey ".classification"
        }

        test("rejects classification exceeding 255 chars") {
            val ex = shouldThrow<ValidationException> {
                CorrectClassificationRequest("a".repeat(256)).validate(validateCorrectClassification)
            }
            ex.fieldErrors shouldContainKey ".classification"
        }

        test("accepts classification with spaces, dots, hyphens, slashes") {
            shouldNotThrowAny {
                CorrectClassificationRequest("tax-form/W-2 2024.v1").validate(validateCorrectClassification)
            }
        }

        test("accepts classification at exactly 255 chars") {
            shouldNotThrowAny {
                CorrectClassificationRequest("a".repeat(255)).validate(validateCorrectClassification)
            }
        }
    }

    context("validateListQueryParams") {
        test("accepts valid params") {
            shouldNotThrowAny {
                ListQueryParams(limit = 50, offset = 0, classification = null)
                    .validate(validateListQueryParams)
            }
        }

        test("rejects limit below 1") {
            val ex = shouldThrow<ValidationException> {
                ListQueryParams(limit = 0, offset = 0, classification = null)
                    .validate(validateListQueryParams)
            }
            ex.fieldErrors shouldContainKey ".limit"
        }

        test("rejects limit above 500") {
            val ex = shouldThrow<ValidationException> {
                ListQueryParams(limit = 501, offset = 0, classification = null)
                    .validate(validateListQueryParams)
            }
            ex.fieldErrors shouldContainKey ".limit"
        }

        test("rejects negative offset") {
            val ex = shouldThrow<ValidationException> {
                ListQueryParams(limit = 50, offset = -1, classification = null)
                    .validate(validateListQueryParams)
            }
            ex.fieldErrors shouldContainKey ".offset"
        }

        test("accepts null classification") {
            shouldNotThrowAny {
                ListQueryParams(limit = 50, offset = 0, classification = null)
                    .validate(validateListQueryParams)
            }
        }

        test("rejects classification exceeding 255 chars") {
            val ex = shouldThrow<ValidationException> {
                ListQueryParams(limit = 50, offset = 0, classification = "a".repeat(256))
                    .validate(validateListQueryParams)
            }
            ex.fieldErrors shouldContainKey ".classification"
        }

        test("collects multiple errors at once") {
            val ex = shouldThrow<ValidationException> {
                ListQueryParams(limit = 0, offset = -1, classification = null)
                    .validate(validateListQueryParams)
            }
            ex.fieldErrors shouldContainKey ".limit"
            ex.fieldErrors shouldContainKey ".offset"
        }

        test("accepts boundary values: limit=1, offset=0") {
            shouldNotThrowAny {
                ListQueryParams(limit = 1, offset = 0, classification = null)
                    .validate(validateListQueryParams)
            }
        }

        test("accepts boundary value: limit=500") {
            shouldNotThrowAny {
                ListQueryParams(limit = 500, offset = 0, classification = null)
                    .validate(validateListQueryParams)
            }
        }
    }

    context("validateSearchQueryParams") {
        test("accepts valid search params") {
            shouldNotThrowAny {
                SearchQueryParams(metadata = mapOf("dept" to "finance"), limit = 50)
                    .validate(validateSearchQueryParams)
            }
        }

        test("rejects empty metadata map") {
            val ex = shouldThrow<ValidationException> {
                SearchQueryParams(metadata = emptyMap(), limit = 50)
                    .validate(validateSearchQueryParams)
            }
            ex.fieldErrors shouldContainKey ".metadata"
        }

        test("rejects limit below 1") {
            val ex = shouldThrow<ValidationException> {
                SearchQueryParams(metadata = mapOf("k" to "v"), limit = 0)
                    .validate(validateSearchQueryParams)
            }
            ex.fieldErrors shouldContainKey ".limit"
        }

        test("rejects limit above 500") {
            val ex = shouldThrow<ValidationException> {
                SearchQueryParams(metadata = mapOf("k" to "v"), limit = 501)
                    .validate(validateSearchQueryParams)
            }
            ex.fieldErrors shouldContainKey ".limit"
        }

        test("accepts multiple metadata entries") {
            shouldNotThrowAny {
                SearchQueryParams(
                    metadata = mapOf("dept" to "finance", "year" to "2024"),
                    limit = 50
                ).validate(validateSearchQueryParams)
            }
        }
    }

    context("validateUploadParams") {
        test("accepts valid upload params") {
            shouldNotThrowAny {
                UploadParams(filename = "report.pdf", mimeType = "application/pdf")
                    .validate(validateUploadParams)
            }
        }

        test("rejects filename exceeding 255 chars") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "a".repeat(256) + ".pdf", mimeType = "application/pdf")
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".filename"
        }

        test("rejects filename with forward slash") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "../etc/passwd", mimeType = "application/pdf")
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".filename"
        }

        test("rejects filename with backslash") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "..\\etc\\passwd", mimeType = "text/plain")
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".filename"
        }

        test("rejects mimeType exceeding 127 chars") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "file.txt", mimeType = "a/" + "b".repeat(126))
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".mimeType"
        }

        test("accepts filename with spaces and special chars") {
            shouldNotThrowAny {
                UploadParams(filename = "My Report (2024).pdf", mimeType = "application/pdf")
                    .validate(validateUploadParams)
            }
        }

        test("rejects blank filename") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "  ", mimeType = "application/pdf")
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".filename"
        }

        test("rejects empty filename") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "", mimeType = "application/pdf")
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".filename"
        }

        test("rejects blank mimeType") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "file.txt", mimeType = "  ")
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".mimeType"
        }

        test("rejects empty mimeType") {
            val ex = shouldThrow<ValidationException> {
                UploadParams(filename = "file.txt", mimeType = "")
                    .validate(validateUploadParams)
            }
            ex.fieldErrors shouldContainKey ".mimeType"
        }
    }
})
