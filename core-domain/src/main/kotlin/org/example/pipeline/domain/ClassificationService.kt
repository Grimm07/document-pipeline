package org.example.pipeline.domain

/**
 * Service interface for document classification.
 *
 * Implementations call an external ML service to classify documents.
 */
interface ClassificationService {

    /**
     * Classifies a document based on its content.
     *
     * @param content The document content as bytes
     * @param mimeType The MIME type of the document
     * @return Classification result with category and confidence score
     */
    suspend fun classify(content: ByteArray, mimeType: String): ClassificationResult
}
