package org.example.pipeline.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.pipeline.domain.QueuePublisher
import org.slf4j.LoggerFactory

/**
 * RabbitMQ implementation of [QueuePublisher].
 *
 * Publishes document processing messages to the classification queue.
 *
 * @property connection RabbitMQ connection
 */
class RabbitMQPublisher(
    private val connection: Connection
) : QueuePublisher {

    private val logger = LoggerFactory.getLogger(RabbitMQPublisher::class.java)
    private val json = Json { encodeDefaults = true }

    // Channel is lazily created on first publish. Auto-recovery is handled by the
    // RabbitMQ Java client when isAutomaticRecoveryEnabled=true (set in RabbitMQConfig).
    // On connection loss, the client transparently reconnects and recreates channels.
    private val channel: Channel by lazy {
        connection.createChannel().also { ch ->
            declareTopology(ch)
        }
    }

    override suspend fun publish(documentId: String): Unit = withContext(Dispatchers.IO) {
        val message = DocumentMessage(documentId = documentId)
        val body = json.encodeToString(message).toByteArray()
        val props = AMQP.BasicProperties.Builder()
            .contentType(QueueConstants.CONTENT_TYPE_JSON)
            .deliveryMode(2)
            .build()
        channel.basicPublish(
            QueueConstants.DOCUMENT_EXCHANGE,
            QueueConstants.CLASSIFICATION_ROUTING_KEY,
            props,
            body
        )
        logger.debug("Published message for document: {}", documentId)
    }

    /**
     * Declares the exchange, queue, and binding.
     */
    private fun declareTopology(channel: Channel) {
        channel.exchangeDeclare(QueueConstants.DOCUMENT_EXCHANGE, "topic", true)
        channel.exchangeDeclare(QueueConstants.DLX_EXCHANGE, "fanout", true)
        channel.queueDeclare(QueueConstants.DLX_QUEUE, true, false, false, null)
        channel.queueBind(QueueConstants.DLX_QUEUE, QueueConstants.DLX_EXCHANGE, "")
        val queueArgs = mapOf<String, Any>("x-dead-letter-exchange" to QueueConstants.DLX_EXCHANGE)
        channel.queueDeclare(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, false, false, queueArgs)
        channel.queueBind(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, QueueConstants.DOCUMENT_EXCHANGE, QueueConstants.CLASSIFICATION_ROUTING_KEY)
        logger.info("RabbitMQ topology declared")
    }

    /**
     * Closes the channel and connection.
     */
    fun close() {
        runCatching { channel.close() }
        runCatching { connection.close() }
        logger.info("RabbitMQ publisher closed")
    }
}
