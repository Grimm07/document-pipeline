package org.example.pipeline.api.di

import com.typesafe.config.ConfigFactory
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
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

/** Default connection pool size when not specified in configuration. */
private const val DEFAULT_POOL_SIZE = 10

/** Default RabbitMQ port when not specified in configuration. */
private const val DEFAULT_RABBITMQ_PORT = 5672

/**
 * Koin module for API dependencies.
 *
 * Wires together all infrastructure implementations with domain interfaces:
 * HOCON config, PostgreSQL (HikariCP + Flyway), Exposed repository,
 * local file storage, and RabbitMQ publisher.
 */
val apiModule = module {

    // Configuration
    single {
        HoconApplicationConfig(ConfigFactory.load())
    }

    // Metrics registry
    single { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }

    // Database
    single {
        val config = get<HoconApplicationConfig>()
        DatabaseConfig.init(
            jdbcUrl = config.property("database.jdbcUrl").getString(),
            username = config.property("database.username").getString(),
            password = config.property("database.password").getString(),
            maxPoolSize = config.propertyOrNull("database.maxPoolSize")
                ?.getString()?.toIntOrNull() ?: DEFAULT_POOL_SIZE
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
            port = config.propertyOrNull("rabbitmq.port")
                ?.getString()?.toIntOrNull() ?: DEFAULT_RABBITMQ_PORT,
            username = config.property("rabbitmq.username").getString(),
            password = config.property("rabbitmq.password").getString(),
            virtualHost = config.propertyOrNull("rabbitmq.virtualHost")
                ?.getString() ?: "/"
        )
    }

    // Queue publisher
    single<QueuePublisher> {
        RabbitMQPublisher(get())
    }
}
