package org.example.pipeline.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.pipeline.domain.FileStorageService
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Local filesystem implementation of [FileStorageService].
 *
 * Organizes files in a date-based directory structure:
 * {baseDir}/{yyyy}/{MM}/{dd}/{uuid}.{ext}
 *
 * @property baseDir The root directory for file storage
 */
class LocalFileStorageService(
    private val baseDir: Path
) : FileStorageService {

    private val logger = LoggerFactory.getLogger(LocalFileStorageService::class.java)

    init {
        // Ensure base directory exists
        if (!baseDir.exists()) {
            baseDir.createDirectories()
            logger.info("Created storage base directory: $baseDir")
        }
    }

    override suspend fun store(id: String, filename: String, content: ByteArray): String =
        withContext(Dispatchers.IO) {
            TODO("Implement: Generate date-based path using generateStoragePath, create parent dirs, write content to file, return relative storage path")
        }

    override suspend fun retrieve(storagePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            TODO("Implement: Resolve full path from storagePath, read file bytes if exists, return null if not found")
        }

    override suspend fun delete(storagePath: String): Boolean =
        withContext(Dispatchers.IO) {
            TODO("Implement: Resolve full path, delete file if exists, return true if deleted")
        }

    /**
     * Generates a date-based storage path for a file.
     *
     * @param id The document UUID as string
     * @param filename Original filename for extension extraction
     * @return Relative path in format: yyyy/MM/dd/uuid.ext
     */
    private fun generateStoragePath(id: String, filename: String): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val year = now.year.toString()
        val month = now.monthNumber.toString().padStart(2, '0')
        val day = now.dayOfMonth.toString().padStart(2, '0')

        val extension = filename.substringAfterLast('.', "bin")
        return "$year/$month/$day/$id.$extension"
    }

    /**
     * Resolves a storage path to an absolute filesystem path.
     */
    private fun resolvePath(storagePath: String): Path = baseDir.resolve(storagePath)
}
