package org.example.pipeline.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.AlreadyClosedException
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ShutdownSignalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.pipeline.domain.QueuePublisher
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * RabbitMQ implementation of [QueuePublisher].
 *
 * Publishes document processing messages to the classification queue with
 * automatic retry and channel recreation on transient failures.
 *
 * @param connection RabbitMQ connection (should have automatic recovery enabled)
 * @param retryConfig Retry parameters for publish operations (defaults to 3 retries)
 */
class RabbitMQPublisher(
    private val connection: Connection,
    private val retryConfig: RetryConfig = RetryConfig()
) : QueuePublisher {

    private val logger = LoggerFactory.getLogger(RabbitMQPublisher::class.java)
    private val json = Json { encodeDefaults = true }

    @Volatile
    private var channel: Channel? = null

    /**
     * Returns the current open channel or creates a new one.
     *
     * Thread-safe: `@Synchronized` is safe here because callers are pinned
     * to real threads via `withContext(Dispatchers.IO)`.
     */
    @Synchronized
    private fun getOrCreateChannel(): Channel {
        val existing = channel
        if (existing != null && existing.isOpen) return existing
        return connection.createChannel().also { ch ->
            declareTopology(ch)
            channel = ch
        }
    }

    /**
     * Determines if an exception is transient and worth retrying.
     *
     * On retryable exceptions, the current channel is invalidated so the
     * next attempt creates a fresh one.
     */
    private fun isRetryableException(e: Exception): Boolean {
        val retryable = e is AlreadyClosedException || e is IOException || e is ShutdownSignalException
        if (retryable) {
            runCatching { channel?.close() }
            channel = null
        }
        return retryable
    }

    override suspend fun publish(documentId: String, correlationId: String?): Unit = withContext(Dispatchers.IO) {
        val message = DocumentMessage(documentId = documentId, correlationId = correlationId)
        val body = json.encodeToString(message).toByteArray()
        val props = AMQP.BasicProperties.Builder()
            .contentType(QueueConstants.CONTENT_TYPE_JSON)
            .deliveryMode(2)
            .build()

        withRetry(retryConfig, logger, "publish(documentId=$documentId)", ::isRetryableException) {
            getOrCreateChannel().basicPublish(
                QueueConstants.DOCUMENT_EXCHANGE,
                QueueConstants.CLASSIFICATION_ROUTING_KEY,
                props,
                body
            )
        }
        logger.debug("Published message for document: {}", documentId)
    }

    /**
     * Declares the exchange, queue, and binding topology.
     *
     * Includes main exchange/queue, dead letter exchange/queue, and parking lot for
     * permanently failed messages.
     */
    private fun declareTopology(channel: Channel) {
        channel.exchangeDeclare(QueueConstants.DOCUMENT_EXCHANGE, "topic", true)
        channel.exchangeDeclare(QueueConstants.DLX_EXCHANGE, "fanout", true)
        channel.queueDeclare(QueueConstants.DLX_QUEUE, true, false, false, null)
        channel.queueBind(QueueConstants.DLX_QUEUE, QueueConstants.DLX_EXCHANGE, "")
        val queueArgs = mapOf<String, Any>("x-dead-letter-exchange" to QueueConstants.DLX_EXCHANGE)
        channel.queueDeclare(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, false, false, queueArgs)
        channel.queueBind(
            QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE,
            QueueConstants.DOCUMENT_EXCHANGE,
            QueueConstants.CLASSIFICATION_ROUTING_KEY
        )
        channel.exchangeDeclare(QueueConstants.PARKING_LOT_EXCHANGE, "fanout", true)
        channel.queueDeclare(QueueConstants.PARKING_LOT_QUEUE, true, false, false, null)
        channel.queueBind(QueueConstants.PARKING_LOT_QUEUE, QueueConstants.PARKING_LOT_EXCHANGE, "")
        logger.info("RabbitMQ topology declared")
    }

    /**
     * Closes the channel and connection.
     */
    fun close() {
        runCatching { channel?.close() }
        runCatching { connection.close() }
        logger.info("RabbitMQ publisher closed")
    }
}
