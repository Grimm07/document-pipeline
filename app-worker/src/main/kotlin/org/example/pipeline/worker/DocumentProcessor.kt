package org.example.pipeline.worker

import org.example.pipeline.domain.ClassificationService
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.slf4j.LoggerFactory

/**
 * Processes documents from the queue.
 *
 * Orchestrates the classification workflow:
 * 1. Fetch document metadata from database
 * 2. Retrieve file content from storage
 * 3. Call ML classification service
 * 4. Update document with classification result
 */
class DocumentProcessor(
    private val documentRepository: DocumentRepository,
    private val fileStorageService: FileStorageService,
    private val classificationService: ClassificationService
) {
    private val logger = LoggerFactory.getLogger(DocumentProcessor::class.java)

    /**
     * Processes a document by ID.
     *
     * @param documentId The UUID (as string) of the document to process
     * @throws IllegalStateException if document not found or processing fails
     */
    suspend fun process(documentId: String) {
        logger.info("Processing document: $documentId")

        val document = documentRepository.getById(documentId)
        if (document == null) {
            logger.warn("Document not found: {}", documentId)
            return
        }

        val content = fileStorageService.retrieve(document.storagePath)
        checkNotNull(content) { "File content not found for document $documentId at ${document.storagePath}" }

        val result = classificationService.classify(content, document.mimeType)

        val ocrJson = result.ocrResultJson
        var ocrStoragePath: String? = null
        if (ocrJson != null) {
            ocrStoragePath = fileStorageService.store(
                "$documentId-ocr", "ocr-results.json", ocrJson.toByteArray()
            )
            logger.info("Stored OCR results for document {} at {}", documentId, ocrStoragePath)
        }

        val updated = documentRepository.updateClassification(
            documentId, result.classification, result.confidence, ocrStoragePath,
            labelScores = result.labelScores
        )
        if (!updated) {
            logger.warn("Skipped classification update for document {} (already classified or manually corrected)", documentId)
        } else {
            logger.info("Document {} classified as {} (confidence: {})", documentId, result.classification, result.confidence)
        }
    }
}
