package org.example.pipeline.api.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.example.pipeline.domain.Document
import kotlin.uuid.ExperimentalUuidApi

/**
 * Response DTO for document data.
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
    val createdAt: String,
    val updatedAt: String
)

/**
 * Response for document upload.
 */
@Serializable
data class UploadResponse(
    val id: String,
    val message: String = "Document uploaded successfully. Processing started."
)

/**
 * Response for document list.
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
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null
)

/**
 * Extension function to convert Document domain object to response DTO.
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
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)
