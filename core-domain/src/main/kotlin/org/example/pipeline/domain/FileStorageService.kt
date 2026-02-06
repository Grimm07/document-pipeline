package org.example.pipeline.domain

/**
 * Service interface for file storage operations.
 *
 * Implementations handle the actual storage mechanism (local filesystem, S3, etc.).
 * Files are organized and referenced by a storage path returned from [store].
 */
interface FileStorageService {

    /**
     * Stores a file and returns the storage path.
     *
     * The storage path is a relative path that can be used to retrieve or delete the file.
     * Implementations should organize files appropriately (e.g., by date, by ID).
     *
     * @param id Unique identifier for the file (UUID as string, used in path generation)
     * @param filename Original filename (used for extension extraction)
     * @param content The file content as bytes
     * @return The relative storage path where the file was stored
     */
    suspend fun store(id: String, filename: String, content: ByteArray): String

    /**
     * Retrieves file content by storage path.
     *
     * @param storagePath The path returned from [store]
     * @return The file content as bytes, or null if not found
     */
    suspend fun retrieve(storagePath: String): ByteArray?

    /**
     * Deletes a file by storage path.
     *
     * @param storagePath The path returned from [store]
     * @return True if the file was deleted, false if not found
     */
    suspend fun delete(storagePath: String): Boolean
}
