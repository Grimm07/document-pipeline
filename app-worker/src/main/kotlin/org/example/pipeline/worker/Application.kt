package org.example.pipeline.worker

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import org.example.pipeline.db.DatabaseConfig
import org.example.pipeline.queue.RabbitMQConfig
import org.example.pipeline.queue.RabbitMQConsumer
import org.example.pipeline.worker.di.workerModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.slf4j.LoggerFactory
import org.slf4j.MDC

private val logger = LoggerFactory.getLogger("WorkerApplication")

/** Default RabbitMQ port used when not specified in configuration. */
private const val DEFAULT_RABBITMQ_PORT = 5672

/** Default port for the worker metrics HTTP server. */
private const val DEFAULT_METRICS_PORT = 8081

/**
 * Entry point for the Document Pipeline Worker application.
 *
 * Initializes the database, starts Koin DI, connects to RabbitMQ,
 * and begins consuming document processing messages.
 */
fun main() {
    logger.info("Starting Document Pipeline Worker")

    // Load configuration
    val config = HoconApplicationConfig(ConfigFactory.load())

    // Create Prometheus metrics registry
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val classifiedCounter = meterRegistry.counter("documents_classified_total")
    val errorCounter = meterRegistry.counter("classification_errors_total")
    val processingTimer = meterRegistry.timer("queue_message_processing_duration_seconds")

    // Start metrics server
    val metricsPort = config.propertyOrNull("metrics.port")
        ?.getString()?.toIntOrNull() ?: DEFAULT_METRICS_PORT
    val metricsServer = MetricsServer(meterRegistry, metricsPort)
    metricsServer.start()

    // Initialize Koin DI
    val koin = startKoin {
        modules(workerModule(config, meterRegistry))
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
        port = config.propertyOrNull("rabbitmq.port")?.getString()?.toIntOrNull() ?: DEFAULT_RABBITMQ_PORT,
        username = config.property("rabbitmq.username").getString(),
        password = config.property("rabbitmq.password").getString()
    )

    @Suppress("TooGenericExceptionCaught") // Must count errors before rethrowing
    val consumer = RabbitMQConsumer(rabbitConnection) { documentId, correlationId ->
        if (correlationId != null) MDC.put("correlationId", correlationId)
        try {
            processingTimer.record<Unit> {
                runBlocking {
                    documentProcessor.process(documentId)
                }
            }
            classifiedCounter.increment()
        } catch (e: Exception) {
            errorCounter.increment()
            throw e
        } finally {
            MDC.remove("correlationId")
        }
    }

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down worker...")
        consumer.stop()
        metricsServer.stop()
        rabbitConnection.close()
        stopKoin()
        logger.info("Worker shutdown complete")
    })

    // Start consuming
    logger.info("Worker started, listening for messages...")
    consumer.start()
}
