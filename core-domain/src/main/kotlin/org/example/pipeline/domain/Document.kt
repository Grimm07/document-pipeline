package org.example.pipeline.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a document in the ingestion pipeline.
 *
 * A document goes through several stages:
 * 1. Upload - file is stored and record created with classification = "unclassified"
 * 2. Processing - worker picks up the document for classification
 * 3. Classified - ML service has assigned a classification and confidence score
 *
 * @property id Unique identifier for the document (UUID as string)
 * @property storagePath Path where the document file is stored (relative to storage root)
 * @property originalFilename The original filename as uploaded by the user
 * @property mimeType MIME type of the document (e.g., "application/pdf", "image/png")
 * @property fileSizeBytes Size of the file in bytes
 * @property classification Document classification assigned by ML service (default: "unclassified")
 * @property confidence Confidence score from ML service (0.0 to 1.0), null if not yet classified
 * @property metadata Additional key-value metadata associated with the document
 * @property uploadedBy Optional identifier of the user who uploaded the document
 * @property createdAt Timestamp when the document was created
 * @property updatedAt Timestamp when the document was last updated
 */
@Serializable
data class Document(
    val id: String,
    val storagePath: String,
    val originalFilename: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val classification: String = "unclassified",
    val confidence: Float? = null,
    val metadata: Map<String, String> = emptyMap(),
    val uploadedBy: String? = null,
    val ocrStoragePath: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
