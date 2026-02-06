package org.example.pipeline.domain

/**
 * Repository interface for document persistence operations.
 *
 * Implementations should handle database transactions appropriately.
 * All methods are suspending to support non-blocking database access.
 */
interface DocumentRepository {

    /**
     * Inserts a new document record.
     *
     * @param document The document to insert
     * @return The inserted document (may have generated fields populated)
     */
    suspend fun insert(document: Document): Document

    /**
     * Retrieves a document by its ID.
     *
     * @param id The document UUID as string
     * @return The document if found, null otherwise
     */
    suspend fun getById(id: String): Document?

    /**
     * Lists documents with optional filtering and pagination.
     *
     * @param classification Optional filter by classification
     * @param limit Maximum number of results (default: 50)
     * @param offset Number of results to skip (default: 0)
     * @return List of matching documents
     */
    suspend fun list(
        classification: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<Document>

    /**
     * Searches documents by metadata key-value pairs.
     *
     * @param metadataQuery Map of metadata keys to search values
     * @param limit Maximum number of results
     * @return List of documents matching the metadata criteria
     */
    suspend fun searchMetadata(
        metadataQuery: Map<String, String>,
        limit: Int = 50
    ): List<Document>

    /**
     * Updates the classification result for a document.
     *
     * @param id The document UUID as string
     * @param classification The new classification
     * @param confidence The confidence score
     * @return True if the document was updated, false if not found
     */
    suspend fun updateClassification(
        id: String,
        classification: String,
        confidence: Float
    ): Boolean
}
