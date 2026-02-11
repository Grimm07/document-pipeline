package org.example.pipeline.api.dto

import kotlinx.serialization.Serializable
import org.example.pipeline.domain.Document

/**
 * Response DTO for document data.
 *
 * @property id Unique document identifier (UUID)
 * @property originalFilename Original filename as uploaded by the user
 * @property mimeType MIME type of the document
 * @property fileSizeBytes File size in bytes
 * @property classification ML-assigned classification label
 * @property confidence ML confidence score (0.0-1.0), null if not yet classified
 * @property metadata User-provided key-value metadata
 * @property uploadedBy Optional uploader identifier
 * @property hasOcrResults Whether OCR results are available for this document
 * @property createdAt ISO-8601 creation timestamp
 * @property updatedAt ISO-8601 last update timestamp
 */
@Serializable
data class DocumentResponse(
    val id: String,
    val originalFilename: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val classification: String,
    val confidence: Float?,
    val metadata: Map<String, String>,
    val uploadedBy: String?,
    val hasOcrResults: Boolean,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Response DTO for document upload.
 *
 * @property id Newly created document identifier (UUID)
 * @property message Human-readable status message
 */
@Serializable
data class UploadResponse(
    val id: String,
    val message: String = "Document uploaded successfully. Processing started."
)

/**
 * Response DTO for paginated document lists.
 *
 * @property documents List of document responses for the current page
 * @property total Total number of documents returned
 * @property limit Maximum number of results requested
 * @property offset Number of results skipped
 */
@Serializable
data class DocumentListResponse(
    val documents: List<DocumentResponse>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

/**
 * Error response DTO.
 *
 * @property error Human-readable error message
 * @property details Optional additional error details
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null
)

/**
 * Converts a [Document] domain object to its API response DTO.
 *
 * @return [DocumentResponse] with all fields mapped from the domain model
 */
fun Document.toResponse(): DocumentResponse = DocumentResponse(
    id = id,
    originalFilename = originalFilename,
    mimeType = mimeType,
    fileSizeBytes = fileSizeBytes,
    classification = classification,
    confidence = confidence,
    metadata = metadata,
    uploadedBy = uploadedBy,
    hasOcrResults = ocrStoragePath != null,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)
