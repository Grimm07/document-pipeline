package org.example.pipeline.worker

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
import kotlinx.coroutines.runBlocking
import org.example.pipeline.db.DatabaseConfig
import org.example.pipeline.queue.RabbitMQConfig
import org.example.pipeline.queue.RabbitMQConsumer
import org.example.pipeline.worker.di.workerModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WorkerApplication")

fun main() {
    logger.info("Starting Document Pipeline Worker")

    // Load configuration
    val config = HoconApplicationConfig(ConfigFactory.load())

    // Initialize Koin DI
    val koin = startKoin {
        modules(workerModule(config))
    }.koin

    // Initialize database
    DatabaseConfig.init(
        jdbcUrl = config.property("database.jdbcUrl").getString(),
        username = config.property("database.username").getString(),
        password = config.property("database.password").getString()
    )

    // Get dependencies
    val documentProcessor = koin.get<DocumentProcessor>()

    // Create RabbitMQ connection and consumer
    val rabbitConnection = RabbitMQConfig.createConnection(
        host = config.property("rabbitmq.host").getString(),
        port = config.propertyOrNull("rabbitmq.port")?.getString()?.toIntOrNull() ?: 5672,
        username = config.property("rabbitmq.username").getString(),
        password = config.property("rabbitmq.password").getString()
    )

    val consumer = RabbitMQConsumer(rabbitConnection) { documentId ->
        runBlocking {
            documentProcessor.process(documentId)
        }
    }

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down worker...")
        consumer.stop()
        rabbitConnection.close()
        stopKoin()
        logger.info("Worker shutdown complete")
    })

    // Start consuming
    logger.info("Worker started, listening for messages...")
    consumer.start()
}
