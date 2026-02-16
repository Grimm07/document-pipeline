# Konform Input Validation — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace all manual validation in `DocumentRoutes.kt` with declarative Konform validators, unify error responses, and add comprehensive validation tests.

**Architecture:** Standalone Konform `Validation<T>` objects in `app-api/src/main/kotlin/.../api/validation/Validators.kt`. A thin `ValidationSupport.kt` provides a `validate()` extension that throws `ValidationException` on failure, caught by Ktor `StatusPages` and serialized as structured `{ error, fieldErrors }` JSON. Query parameters are parsed into wrapper data classes before validation.

**Tech Stack:** Konform 0.11.0 (Kotlin multiplatform validation DSL), Ktor StatusPages, kotlinx.serialization, Kotest 6

---

### Task 1: Add Konform dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app-api/build.gradle.kts`

**Step 1: Add version and library to version catalog**

In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
konform = "0.11.0"
```

Under `[libraries]`:
```toml
konform = { module = "io.konform:konform", version.ref = "konform" }
```

**Step 2: Add dependency to app-api**

In `app-api/build.gradle.kts`, add in `dependencies` block:
```kotlin
implementation(libs.konform)
```

**Step 3: Verify the build compiles**

Run: `./gradlew :app-api:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
feat(api): add Konform 0.11.0 validation dependency
```

---

### Task 2: Create ValidationSupport foundation

**Files:**
- Create: `app-api/src/main/kotlin/org/example/pipeline/api/validation/ValidationSupport.kt`
- Modify: `app-api/src/main/kotlin/org/example/pipeline/api/dto/DocumentDtos.kt` (add `ValidationErrorResponse`)
- Create: `app-api/src/test/kotlin/org/example/pipeline/api/validation/ValidationSupportTest.kt`

**Step 1: Write the failing test**

Create `ValidationSupportTest.kt`:
```kotlin
package org.example.pipeline.api.validation

import io.konform.validation.Validation
import io.konform.validation.jsonschema.maxLength
import io.konform.validation.jsonschema.minimum
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

data class TestInput(val name: String, val age: Int)

class ValidationSupportTest : FunSpec({

    val testValidator = Validation<TestInput> {
        TestInput::name {
            maxLength(10)
        }
        TestInput::age {
            minimum(0)
        }
    }

    test("validate returns value when validation passes") {
        val input = TestInput("Alice", 25)
        val result = shouldNotThrowAny { input.validate(testValidator) }
        result shouldBe input
    }

    test("validate throws ValidationException on failure") {
        val input = TestInput("A very long name that exceeds", -1)
        val ex = shouldThrow<ValidationException> { input.validate(testValidator) }
        ex.fieldErrors.size shouldBe 2
        ex.fieldErrors shouldContainKey ".name"
        ex.fieldErrors shouldContainKey ".age"
    }

    test("fieldErrors contains correct messages") {
        val input = TestInput("A very long name that exceeds", 5)
        val ex = shouldThrow<ValidationException> { input.validate(testValidator) }
        ex.fieldErrors.size shouldBe 1
        ex.fieldErrors shouldContainKey ".name"
    }

    test("ValidationException message is Validation failed") {
        val input = TestInput("toolong12345", -1)
        val ex = shouldThrow<ValidationException> { input.validate(testValidator) }
        ex.message shouldBe "Validation failed"
    }
})
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app-api:test --tests "org.example.pipeline.api.validation.ValidationSupportTest" --no-configuration-cache`
Expected: FAIL — files don't exist yet

**Step 3: Add `ValidationErrorResponse` to DocumentDtos.kt**

Add at the end of `app-api/src/main/kotlin/org/example/pipeline/api/dto/DocumentDtos.kt`:
```kotlin
/**
 * Structured validation error response with per-field error messages.
 *
 * @property error Summary error message (always "Validation failed")
 * @property fieldErrors Map of field path to list of validation error messages
 */
@Serializable
data class ValidationErrorResponse(
    val error: String = "Validation failed",
    val fieldErrors: Map<String, List<String>>
)
```

**Step 4: Create ValidationSupport.kt**

Create `app-api/src/main/kotlin/org/example/pipeline/api/validation/ValidationSupport.kt`:
```kotlin
package org.example.pipeline.api.validation

import io.konform.validation.Invalid
import io.konform.validation.Validation

/**
 * Exception thrown when Konform validation fails.
 *
 * Carries structured per-field error messages for serialization
 * into a [org.example.pipeline.api.dto.ValidationErrorResponse].
 *
 * @property fieldErrors Map of field path (e.g. ".name") to error messages
 */
class ValidationException(
    val fieldErrors: Map<String, List<String>>
) : RuntimeException("Validation failed")

/**
 * Validates this value against the given [Konform validation][validation].
 *
 * @return the original value if validation passes
 * @throws ValidationException if validation fails, with structured field errors
 */
fun <T> T.validate(validation: Validation<T>): T {
    val result = validation(this)
    if (result is Invalid) {
        val fieldErrors = result.errors
            .groupBy({ it.dataPath }, { it.message })
        throw ValidationException(fieldErrors)
    }
    return this
}
```

**Step 5: Run test to verify it passes**

Run: `./gradlew :app-api:test --tests "org.example.pipeline.api.validation.ValidationSupportTest" --no-configuration-cache`
Expected: PASS (all 4 tests)

**Step 6: Commit**

```
feat(api): add validation support foundation with ValidationException
```

---

### Task 3: Create query param wrapper DTOs

**Files:**
- Modify: `app-api/src/main/kotlin/org/example/pipeline/api/dto/DocumentDtos.kt`

**Step 1: Add wrapper data classes**

Add to `DocumentDtos.kt` (these are plain data classes, not `@Serializable` — they're constructed from query params, not deserialized from JSON):
```kotlin
/**
 * Parsed query parameters for the document list endpoint.
 *
 * @property limit Maximum number of results (1–500)
 * @property offset Number of results to skip (>= 0)
 * @property classification Optional classification filter
 */
data class ListQueryParams(
    val limit: Int,
    val offset: Int,
    val classification: String?
)

/**
 * Parsed query parameters for the metadata search endpoint.
 *
 * @property metadata Metadata key-value pairs to match (at least one required)
 * @property limit Maximum number of results (1–500)
 */
data class SearchQueryParams(
    val metadata: Map<String, String>,
    val limit: Int
)

/**
 * Parsed multipart upload fields for validation.
 *
 * @property filename Original filename from the upload
 * @property mimeType MIME type from the upload
 */
data class UploadParams(
    val filename: String,
    val mimeType: String
)
```

**Step 2: Verify compilation**

Run: `./gradlew :app-api:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat(api): add query param wrapper DTOs for validation
```

---

### Task 4: Create Konform validators with unit tests

**Files:**
- Create: `app-api/src/main/kotlin/org/example/pipeline/api/validation/Validators.kt`
- Create: `app-api/src/test/kotlin/org/example/pipeline/api/validation/ValidatorsTest.kt`

**Step 1: Write the failing tests**

Create `ValidatorsTest.kt`:
```kotlin
package org.example.pipeline.api.validation

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
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

        test("rejects filename with path separator") {
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
    }
})
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :app-api:test --tests "org.example.pipeline.api.validation.ValidatorsTest" --no-configuration-cache`
Expected: FAIL — Validators.kt doesn't exist

**Step 3: Create Validators.kt**

Create `app-api/src/main/kotlin/org/example/pipeline/api/validation/Validators.kt`:
```kotlin
package org.example.pipeline.api.validation

import io.konform.validation.Validation
import io.konform.validation.jsonschema.maxLength
import io.konform.validation.jsonschema.maximum
import io.konform.validation.jsonschema.minItems
import io.konform.validation.jsonschema.minimum
import org.example.pipeline.api.dto.CorrectClassificationRequest
import org.example.pipeline.api.dto.ListQueryParams
import org.example.pipeline.api.dto.SearchQueryParams
import org.example.pipeline.api.dto.UploadParams

/** UUID v4 pattern (case-insensitive hex with dashes). */
private val UUID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

/** Pattern for filenames: no forward slash or backslash. */
private val SAFE_FILENAME_PATTERN = Regex("^[^/\\\\]+$")

/**
 * Validates a document ID path parameter as UUID format.
 *
 * Accepts standard UUID strings like `550e8400-e29b-41d4-a716-446655440000`.
 */
val validateDocumentId = Validation<String> {
    constrain("must be a valid UUID") { it.matches(UUID_PATTERN) }
}

/**
 * Validates the [CorrectClassificationRequest] body.
 *
 * Rules: not blank, max 255 characters.
 */
val validateCorrectClassification = Validation<CorrectClassificationRequest> {
    CorrectClassificationRequest::classification {
        constrain("must not be blank") { it.isNotBlank() }
        maxLength(255)
    }
}

/**
 * Validates parsed query parameters for the document list endpoint.
 *
 * Rules: limit 1–500, offset >= 0, optional classification max 255 chars.
 */
val validateListQueryParams = Validation<ListQueryParams> {
    ListQueryParams::limit {
        minimum(1)
        maximum(500)
    }
    ListQueryParams::offset {
        minimum(0)
    }
    ListQueryParams::classification ifPresent {
        maxLength(255)
    }
}

/**
 * Validates parsed query parameters for the metadata search endpoint.
 *
 * Rules: at least one metadata entry, limit 1–500.
 */
val validateSearchQueryParams = Validation<SearchQueryParams> {
    SearchQueryParams::metadata {
        minItems(1) hint "at least one metadata.* query parameter is required"
    }
    SearchQueryParams::limit {
        minimum(1)
        maximum(500)
    }
}

/**
 * Validates parsed multipart upload fields.
 *
 * Rules: filename max 255 chars with no path separators,
 * mimeType max 127 chars.
 */
val validateUploadParams = Validation<UploadParams> {
    UploadParams::filename {
        maxLength(255)
        constrain("must not contain path separators") { it.matches(SAFE_FILENAME_PATTERN) }
    }
    UploadParams::mimeType {
        maxLength(127)
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :app-api:test --tests "org.example.pipeline.api.validation.ValidatorsTest" --no-configuration-cache`
Expected: PASS (all tests)

**Step 5: Run all existing tests to check no regression**

Run: `./gradlew :app-api:test --no-configuration-cache`
Expected: PASS (all existing tests unaffected)

**Step 6: Commit**

```
feat(api): add Konform validators for all API inputs
```

---

### Task 5: Wire validation into StatusPages and routes

This is the integration task. We modify `Application.kt` to handle `ValidationException`, then update `DocumentRoutes.kt` to use validators. Existing tests must be updated to register the `ValidationException` handler in `setupApp()`.

**Files:**
- Modify: `app-api/src/main/kotlin/org/example/pipeline/api/Application.kt`
- Modify: `app-api/src/main/kotlin/org/example/pipeline/api/routes/DocumentRoutes.kt`
- Modify: `app-api/src/test/kotlin/org/example/pipeline/api/routes/DocumentRoutesTest.kt`

**Step 1: Register ValidationException handler in StatusPages**

In `Application.kt`, add import:
```kotlin
import org.example.pipeline.api.dto.ValidationErrorResponse
import org.example.pipeline.api.validation.ValidationException
```

Inside the `install(StatusPages)` block, add **before** the `IllegalArgumentException` handler:
```kotlin
exception<ValidationException> { call, cause ->
    logger.warn("Validation failed: {}", cause.fieldErrors)
    call.respond(
        HttpStatusCode.BadRequest,
        ValidationErrorResponse(fieldErrors = cause.fieldErrors)
    )
}
```

**Step 2: Update DocumentRoutes.kt to use validators**

Add imports:
```kotlin
import org.example.pipeline.api.dto.ListQueryParams
import org.example.pipeline.api.dto.SearchQueryParams
import org.example.pipeline.api.dto.UploadParams
import org.example.pipeline.api.validation.*
```

**Replace GET / (list) endpoint** — parse params into `ListQueryParams` and validate:
```kotlin
get {
    val limit = call.request.queryParameters["limit"]?.toIntOrNull()
        ?: DEFAULT_PAGE_SIZE
    val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
    val classification = call.request.queryParameters["classification"]

    val params = ListQueryParams(limit, offset, classification).validate(validateListQueryParams)

    val documents = documentRepository.list(params.classification, params.limit, params.offset)
    val response = DocumentListResponse(
        documents = documents.map { it.toResponse() },
        total = documents.size,
        limit = params.limit,
        offset = params.offset
    )
    call.respond(HttpStatusCode.OK, response)
}
```

**Replace GET /search** — parse into `SearchQueryParams` and validate:
```kotlin
get("/search") {
    val metadataParams = call.request.queryParameters
        .entries()
        .filter { it.key.startsWith("metadata.") }
        .associate { it.key.removePrefix("metadata.") to it.value.first() }

    val limit = call.request.queryParameters["limit"]?.toIntOrNull()
        ?: DEFAULT_PAGE_SIZE

    val params = SearchQueryParams(metadataParams, limit).validate(validateSearchQueryParams)

    val documents = documentRepository.searchMetadata(params.metadata, params.limit)
    val response = DocumentListResponse(
        documents = documents.map { it.toResponse() },
        total = documents.size,
        limit = params.limit,
        offset = 0
    )
    call.respond(HttpStatusCode.OK, response)
}
```

**Replace PATCH /{id}/classification** — validate both path param and body:
```kotlin
patch("/{id}/classification") {
    val idParam = call.parameters["id"]
        ?: return@patch call.respond(
            HttpStatusCode.BadRequest, ErrorResponse("Missing document ID")
        )
    idParam.validate(validateDocumentId)

    val body = try {
        call.receive<CorrectClassificationRequest>()
    } catch (_: Exception) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Invalid request body")
        )
        return@patch
    }

    body.validate(validateCorrectClassification)

    val existing = documentRepository.getById(idParam)
    if (existing == null) {
        call.respond(
            HttpStatusCode.NotFound,
            ErrorResponse("Document not found: $idParam")
        )
        return@patch
    }

    documentRepository.correctClassification(idParam, body.classification)

    val updated = documentRepository.getById(idParam)
    if (updated == null) {
        call.respond(
            HttpStatusCode.NotFound,
            ErrorResponse("Document not found: $idParam")
        )
        return@patch
    }
    call.respond(HttpStatusCode.OK, updated.toResponse())
}
```

**Add ID validation to all other `/{id}` routes** — add after the null check on `call.parameters["id"]`:
```kotlin
idParam.validate(validateDocumentId)
```

Add this line in: `get("/{id}")`, `get("/{id}/download")`, `get("/{id}/ocr")`, `delete("/{id}")`, `post("/{id}/retry")`.

**Add upload validation** — in `post("/upload")`, after setting `actualFilename` and `actualMimeType`, add:
```kotlin
UploadParams(actualFilename, actualMimeType).validate(validateUploadParams)
```

**Step 3: Update test `setupApp()` to register ValidationException handler**

In `DocumentRoutesTest.kt`, add imports:
```kotlin
import org.example.pipeline.api.dto.ValidationErrorResponse
import org.example.pipeline.api.validation.ValidationException
```

In the `setupApp()` function, add the `ValidationException` handler inside `install(StatusPages)`, before the existing `IllegalArgumentException` handler:
```kotlin
exception<ValidationException> { call, cause ->
    call.respond(
        HttpStatusCode.BadRequest,
        ValidationErrorResponse(fieldErrors = cause.fieldErrors)
    )
}
```

Also update the test `"returns 400 when classification is blank"` — the error message now comes from Konform via `ValidationErrorResponse`, not `ErrorResponse`. The response body will contain `"must not be blank"` inside the `fieldErrors` structure, so `shouldContain "blank"` still passes.

Also update test `"returns 400 when no metadata params provided"` — now the error message comes from Konform. Check that the response still contains `"metadata"` (the hint message includes `"metadata"`).

**Step 4: Run all tests**

Run: `./gradlew :app-api:test --no-configuration-cache`
Expected: PASS (all tests, including existing ones)

**Step 5: Commit**

```
feat(api): wire Konform validation into routes and StatusPages
```

---

### Task 6: Add new validation boundary tests

**Files:**
- Modify: `app-api/src/test/kotlin/org/example/pipeline/api/routes/DocumentRoutesTest.kt`

**Step 1: Add route-level validation tests**

Add these new test contexts to `DocumentRoutesTest.kt`:

```kotlin
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

            coEvery { mockRepo.list(any(), any(), any()) } returns emptyList()

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
            body["fieldErrors"]?.jsonObject?.keys?.size shouldBe 2
        }
    }
}
```

**Step 2: Run the new tests**

Run: `./gradlew :app-api:test --tests "org.example.pipeline.api.routes.DocumentRoutesTest" --no-configuration-cache`
Expected: PASS (all tests)

**Step 3: Run full project test suite**

Run: `./gradlew test --no-configuration-cache`
Expected: PASS (all modules)

**Step 4: Run detekt linter**

Run: `./gradlew detekt --no-configuration-cache`
Expected: PASS (no lint violations)

**Step 5: Commit**

```
test(api): add validation boundary tests for all API endpoints
```

---

### Task 7: Final verification and cleanup

**Files:** None (verification only)

**Step 1: Full build**

Run: `./gradlew build --no-configuration-cache`
Expected: BUILD SUCCESSFUL (compiles, tests pass, detekt passes)

**Step 2: Verify no regressions across all modules**

Run: `./gradlew test --no-configuration-cache`
Expected: All modules PASS

**Step 3: Spot-check: count validation-related tests**

Run: `grep -c "test(" app-api/src/test/kotlin/org/example/pipeline/api/validation/ValidatorsTest.kt app-api/src/test/kotlin/org/example/pipeline/api/validation/ValidationSupportTest.kt`
Expected: ~20+ validation tests across both files

**Step 4: Spot-check: no manual validation remains in routes**

Run: `grep -n "isBlank\|toIntOrNull\|metadataParams.isEmpty" app-api/src/main/kotlin/org/example/pipeline/api/routes/DocumentRoutes.kt`
Expected: `toIntOrNull` still present (for parsing query strings into ints — that's parsing, not validation). `isBlank` and `metadataParams.isEmpty` should be GONE (replaced by Konform).
