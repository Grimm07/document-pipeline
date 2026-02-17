package org.example.pipeline.worker

import com.rabbitmq.client.Connection
import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import org.example.pipeline.db.DatabaseConfig
import org.example.pipeline.queue.DlqReprocessor
import org.example.pipeline.queue.DlqReprocessorConfig
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

/** Default maximum DLQ retry cycles before parking. */
private const val DLQ_DEFAULT_MAX_RETRY_CYCLES = 3

/** Default base delay in milliseconds for DLQ reprocessing backoff. */
private const val DLQ_DEFAULT_BASE_DELAY_MS = 5_000L

/** Default maximum delay in milliseconds for DLQ reprocessing backoff. */
private const val DLQ_DEFAULT_MAX_DELAY_MS = 60_000L

/**
 * Entry point for the Document Pipeline Worker application.
 *
 * Initializes the database, starts Koin DI, connects to RabbitMQ,
 * and begins consuming document processing messages.
 */
fun main() {
    logger.info("Starting Document Pipeline Worker")

    val config = HoconApplicationConfig(ConfigFactory.load())

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val classifiedCounter = meterRegistry.counter("documents_classified_total")
    val errorCounter = meterRegistry.counter("classification_errors_total")
    val processingTimer = meterRegistry.timer("queue_message_processing_duration_seconds")

    val koin = startKoin {
        modules(workerModule(config, meterRegistry))
    }.koin

    DatabaseConfig.init(
        jdbcUrl = config.property("database.jdbcUrl").getString(),
        username = config.property("database.username").getString(),
        password = config.property("database.password").getString()
    )

    val documentProcessor = koin.get<DocumentProcessor>()

    val rabbitConnection = RabbitMQConfig.createConnection(
        host = config.property("rabbitmq.host").getString(),
        port = config.propertyOrNull("rabbitmq.port")?.getString()?.toIntOrNull() ?: DEFAULT_RABBITMQ_PORT,
        username = config.property("rabbitmq.username").getString(),
        password = config.property("rabbitmq.password").getString()
    )

    val metricsPort = config.propertyOrNull("metrics.port")
        ?.getString()?.toIntOrNull() ?: DEFAULT_METRICS_PORT
    val metricsServer = MetricsServer(meterRegistry, metricsPort) {
        rabbitConnection.isOpen
    }
    metricsServer.start()

    @Suppress("TooGenericExceptionCaught") // Must count errors before rethrowing
    val consumer = RabbitMQConsumer(rabbitConnection) { documentId, correlationId ->
        if (correlationId != null) MDC.put("correlationId", correlationId)
        try {
            processingTimer.record<Unit> { runBlocking { documentProcessor.process(documentId) } }
            classifiedCounter.increment()
        } catch (e: Exception) {
            errorCounter.increment()
            throw e
        } finally {
            MDC.remove("correlationId")
        }
    }

    val dlqReprocessor = createDlqReprocessor(config, rabbitConnection, meterRegistry)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down worker...")
        consumer.stop()
        dlqReprocessor?.stop()
        metricsServer.stop()
        rabbitConnection.close()
        stopKoin()
        logger.info("Worker shutdown complete")
    })

    logger.info("Worker started, listening for messages...")
    consumer.start()
    dlqReprocessor?.start()
}

/**
 * Creates a [DlqReprocessor] from HOCON config, or null if disabled.
 *
 * Reads `rabbitmq.dlq.*` configuration properties and wires Prometheus
 * counters for reprocessed and parked messages.
 */
private fun createDlqReprocessor(
    config: HoconApplicationConfig,
    connection: Connection,
    meterRegistry: PrometheusMeterRegistry
): DlqReprocessor? {
    val enabled = config.propertyOrNull("rabbitmq.dlq.enabled")
        ?.getString()?.toBooleanStrictOrNull() ?: true
    if (!enabled) {
        logger.info("DLQ reprocessor disabled")
        return null
    }

    val dlqConfig = DlqReprocessorConfig(
        maxRetryCycles = config.propertyOrNull("rabbitmq.dlq.maxRetryCycles")
            ?.getString()?.toIntOrNull() ?: DLQ_DEFAULT_MAX_RETRY_CYCLES,
        baseDelayMs = config.propertyOrNull("rabbitmq.dlq.baseDelayMs")
            ?.getString()?.toLongOrNull() ?: DLQ_DEFAULT_BASE_DELAY_MS,
        maxDelayMs = config.propertyOrNull("rabbitmq.dlq.maxDelayMs")
            ?.getString()?.toLongOrNull() ?: DLQ_DEFAULT_MAX_DELAY_MS
    )
    val reprocessedCounter = meterRegistry.counter("dlq_messages_reprocessed_total")
    val parkedCounter = meterRegistry.counter("dlq_messages_parked_total")

    logger.info("DLQ reprocessor enabled: {}", dlqConfig)
    return DlqReprocessor(
        connection, dlqConfig,
        onReprocessed = { reprocessedCounter.increment() },
        onParked = { parkedCounter.increment() }
    )
}
