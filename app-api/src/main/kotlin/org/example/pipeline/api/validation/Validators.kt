package org.example.pipeline.api.validation

import io.konform.validation.Validation
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.maximum
import io.konform.validation.constraints.minItems
import io.konform.validation.constraints.minimum
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
 * Rules: limit 1-500, offset >= 0, optional classification max 255 chars.
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
 * Rules: at least one metadata entry, limit 1-500.
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
        constrain("must not be blank") { it.isNotBlank() }
        maxLength(255)
        constrain("must not contain path separators") { it.matches(SAFE_FILENAME_PATTERN) }
    }
    UploadParams::mimeType {
        constrain("must not be blank") { it.isNotBlank() }
        maxLength(127)
    }
}
