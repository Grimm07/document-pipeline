package org.example.pipeline.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import org.example.pipeline.domain.FileStorageService
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Local filesystem implementation of [FileStorageService].
 *
 * Organizes files in a date-based directory structure:
 * `{baseDir}/{yyyy}/{MM}/{dd}/{uuid}.{ext}`
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
            val relativePath = generateStoragePath(id, filename)
            val resolved = resolvePath(relativePath)
            resolved.createParentDirectories()
            resolved.writeBytes(content)
            relativePath
        }

    override suspend fun retrieve(storagePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            resolvePath(storagePath).let{
                if(it.exists()) it.readBytes() else null
            }
        }

    override suspend fun delete(storagePath: String): Boolean =
        withContext(Dispatchers.IO) {
            resolvePath(storagePath).deleteIfExists()
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
        val month = now.month.number.toString().padStart(2, '0')
        val day = now.day.toString().padStart(2, '0')

        val extension = filename.substringAfterLast('.', "bin")
        return "$year/$month/$day/$id.$extension"
    }

    /**
     * Resolves a storage path to an absolute filesystem path.
     */
    private fun resolvePath(storagePath: String): Path {
        val resolved = baseDir.resolve(storagePath).normalize()
        require(resolved.startsWith(baseDir.normalize())) {
            "Path traversal detected: $storagePath"
        }
        return resolved
    }
}
