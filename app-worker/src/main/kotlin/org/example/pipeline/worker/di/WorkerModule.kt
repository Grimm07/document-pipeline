package org.example.pipeline.worker.di

import io.ktor.server.config.*
import org.example.pipeline.db.ExposedDocumentRepository
import org.example.pipeline.domain.ClassificationService
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.example.pipeline.storage.LocalFileStorageService
import org.example.pipeline.worker.DocumentProcessor
import org.example.pipeline.worker.HttpClassificationService
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.nio.file.Paths

/**
 * Creates the Koin module for worker dependencies.
 *
 * @param config Application configuration
 * @return Koin module with all worker dependencies
 */
fun workerModule(config: HoconApplicationConfig) = module {

    // Repository
    single<DocumentRepository> {
        ExposedDocumentRepository()
    }

    // File storage (for reading files to classify)
    single<FileStorageService> {
        val baseDir = config.property("storage.baseDir").getString()
        LocalFileStorageService(Paths.get(baseDir))
    }

    // ML Classification service
    single<ClassificationService> {
        val mlServiceUrl = config.property("mlService.baseUrl").getString()
        val timeoutMs = config.property("mlService.timeoutMs").getString().toLong()
        HttpClassificationService(mlServiceUrl, timeoutMs)
    } onClose { service ->
        (service as? HttpClassificationService)?.close()
    }

    // Document processor
    single {
        DocumentProcessor(
            documentRepository = get(),
            fileStorageService = get(),
            classificationService = get()
        )
    }
}
