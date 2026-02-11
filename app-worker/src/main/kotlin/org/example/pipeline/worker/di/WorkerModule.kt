package org.example.pipeline.worker.di

import io.ktor.server.config.*
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
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
 * @param meterRegistry Optional Prometheus registry for ML call timing
 * @return Koin module with all worker dependencies
 */
fun workerModule(config: HoconApplicationConfig, meterRegistry: PrometheusMeterRegistry? = null) = module {

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
        val mlCallTimer = meterRegistry?.timer("ml_service_call_duration_seconds")
        HttpClassificationService(mlServiceUrl, timeoutMs, mlCallTimer)
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
