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
 *
 * @property documentRepository Repository for document persistence
 * @property fileStorageService Service for file retrieval
 * @property classificationService Service for ML classification
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

        TODO("""
            Implement document processing:
            1. Fetch document from repository by ID
            2. If not found, log warning and return (or throw)
            3. Retrieve file content from storage using document.storagePath
            4. If content is null, log error and return
            5. Call classificationService.classify(content, document.mimeType)
            6. Update document with classification result using repository.updateClassification
            7. Log success with classification and confidence
        """)
    }
}
