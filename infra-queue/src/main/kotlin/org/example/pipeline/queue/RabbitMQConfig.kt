package org.example.pipeline.queue

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.slf4j.LoggerFactory

/**
 * RabbitMQ connection configuration and factory.
 */
object RabbitMQConfig {

    private val logger = LoggerFactory.getLogger(RabbitMQConfig::class.java)

    /** Interval in milliseconds between network recovery attempts. */
    private const val NETWORK_RECOVERY_INTERVAL_MS = 5000L

    /**
     * Creates a RabbitMQ connection.
     *
     * @param host RabbitMQ host
     * @param port RabbitMQ port (default: 5672)
     * @param username RabbitMQ username
     * @param password RabbitMQ password
     * @param virtualHost Virtual host (default: "/")
     * @return Established connection
     */
    fun createConnection(
        host: String,
        port: Int = 5672,
        username: String,
        password: String,
        virtualHost: String = "/"
    ): Connection {
        logger.info("Connecting to RabbitMQ at $host:$port")

        val factory = ConnectionFactory().apply {
            this.host = host
            this.port = port
            this.username = username
            this.password = password
            this.virtualHost = virtualHost

            // Enable automatic recovery
            this.isAutomaticRecoveryEnabled = true
            this.networkRecoveryInterval = NETWORK_RECOVERY_INTERVAL_MS
        }

        return factory.newConnection("document-pipeline").also {
            logger.info("RabbitMQ connection established")
        }
    }
}
