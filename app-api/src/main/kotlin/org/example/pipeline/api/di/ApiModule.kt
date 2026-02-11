package org.example.pipeline.api.di

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
import org.example.pipeline.db.DatabaseConfig
import org.example.pipeline.db.ExposedDocumentRepository
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.example.pipeline.domain.QueuePublisher
import org.example.pipeline.queue.RabbitMQConfig
import org.example.pipeline.queue.RabbitMQPublisher
import org.example.pipeline.storage.LocalFileStorageService
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.nio.file.Paths

/**
 * Koin module for API dependencies.
 *
 * Wires together all infrastructure implementations with domain interfaces.
 */
val apiModule = module {

    // Configuration
    single {
        HoconApplicationConfig(ConfigFactory.load())
    }

    // Database
    single {
        val config = get<HoconApplicationConfig>()
        DatabaseConfig.init(
            jdbcUrl = config.property("database.jdbcUrl").getString(),
            username = config.property("database.username").getString(),
            password = config.property("database.password").getString(),
            maxPoolSize = config.propertyOrNull("database.maxPoolSize")?.getString()?.toIntOrNull() ?: 10
        )
    } onClose { dataSource ->
        (dataSource as? java.io.Closeable)?.close()
    }

    // Repository
    single<DocumentRepository> {
        // Ensure database is initialized first
        get<javax.sql.DataSource>()
        ExposedDocumentRepository()
    }

    // File storage
    single<FileStorageService> {
        val config = get<HoconApplicationConfig>()
        val baseDir = config.property("storage.baseDir").getString()
        LocalFileStorageService(Paths.get(baseDir))
    }

    // RabbitMQ connection
    single {
        val config = get<HoconApplicationConfig>()
        RabbitMQConfig.createConnection(
            host = config.property("rabbitmq.host").getString(),
            port = config.propertyOrNull("rabbitmq.port")?.getString()?.toIntOrNull() ?: 5672,
            username = config.property("rabbitmq.username").getString(),
            password = config.property("rabbitmq.password").getString(),
            virtualHost = config.propertyOrNull("rabbitmq.virtualHost")?.getString() ?: "/"
        )
    }

    // Queue publisher
    single<QueuePublisher> {
        RabbitMQPublisher(get())
    }
}
