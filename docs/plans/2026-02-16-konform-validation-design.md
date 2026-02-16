# Konform Input Validation — API Layer

**Date:** 2026-02-16
**Status:** Approved
**Scope:** All API inputs in `app-api`

## Summary

Replace scattered manual validation in `DocumentRoutes.kt` with declarative Konform validators. Unify error response format with per-field structured errors. Validators live in `app-api` only — `core-domain` stays dependency-free.

## Approach

Standalone validator objects (Approach A). Each input type gets a `Validation<T>` instance defined in a dedicated file. Routes call a thin helper that throws `ValidationException` on `Invalid`, caught by StatusPages.

Rejected alternatives:
- Ktor plugin/interceptor — over-engineered for 5 endpoints, poor fit for query params
- Extension functions on `ApplicationCall` — mixes validation with deserialization, less transparent

## Dependency

```toml
# gradle/libs.versions.toml
konform = "0.11.0"
[libraries]
konform = { module = "io.konform:konform", version.ref = "konform" }
```

Added to `app-api/build.gradle.kts` only.

## File Layout

```
app-api/src/main/kotlin/org/example/pipeline/api/
  validation/
    Validators.kt          # All Konform validator definitions
    ValidationSupport.kt   # ValidationException + helper + StatusPages registration
  dto/
    DocumentDtos.kt        # Existing DTOs + new query param wrappers
  routes/
    DocumentRoutes.kt      # Routes call validators
```

## Validated Inputs

### 1. CorrectClassificationRequest (PATCH /{id}/classification)
- `classification`: notBlank, maxLength(255), printable characters pattern

### 2. ListQueryParams (GET /api/documents)
- `limit`: minimum(1), maximum(500)
- `offset`: minimum(0)
- `classification`: maxLength(255) if present

### 3. SearchQueryParams (GET /api/documents/search)
- At least one `metadata.*` key required
- `limit`: minimum(1), maximum(500)
- Each metadata key: maxLength(100), each value: maxLength(500)

### 4. Path param id (all /{id} routes)
- UUID format regex

### 5. Upload inputs (POST /api/documents/upload)
- File bytes not null (existing)
- `filename`: maxLength(255), no path separator characters
- `mimeType`: maxLength(127), basic format pattern

## Error Response Format

```kotlin
@Serializable
data class ValidationErrorResponse(
    val error: String = "Validation failed",
    val fieldErrors: Map<String, List<String>>
)
```

Example:
```json
{
  "error": "Validation failed",
  "fieldErrors": {
    ".limit": ["must be at least 1"],
    ".classification": ["must not be blank"]
  }
}
```

## StatusPages Integration

```kotlin
class ValidationException(val fieldErrors: Map<String, List<String>>) : RuntimeException("Validation failed")

// Registered in StatusPages:
exception<ValidationException> { call, cause ->
    call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(fieldErrors = cause.fieldErrors))
}
```

## Helper

```kotlin
fun <T> T.validate(validation: Validation<T>): T {
    val result = validation(this)
    if (result is Invalid) throw ValidationException(result.errors.toFieldErrors())
    return this
}
```

## Testing

- Unit tests for each validator (valid + invalid inputs, correct error paths)
- Existing route tests continue to pass (blank classification, missing file, etc.)
- New route tests for: max lengths, UUID format, limit/offset ranges, filename restrictions
