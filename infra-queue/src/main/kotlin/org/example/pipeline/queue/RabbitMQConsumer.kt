package org.example.pipeline.queue

import com.rabbitmq.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * RabbitMQ consumer for document processing messages.
 *
 * Listens on the classification queue and dispatches messages to a handler.
 */
class RabbitMQConsumer(
    private val connection: Connection,
    private val messageHandler: suspend (String, String?) -> Unit
) {
    private val logger = LoggerFactory.getLogger(RabbitMQConsumer::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var channel: Channel? = null
    private var consumerTag: String? = null

    /**
     * Starts consuming messages from the classification queue.
     *
     * Messages are acknowledged after successful processing.
     * Failed messages are nack'd and sent to the dead letter queue.
     */
    @Suppress("TooGenericExceptionCaught") // RabbitMQ handlers must catch all to nack
    fun start() {
        val ch = connection.createChannel().also { declareTopology(it) }
        channel = ch
        ch.basicQos(1)

        val deliverCallback = DeliverCallback { _, delivery ->
            val body = String(delivery.body)
            val docMessage = try {
                json.decodeFromString<DocumentMessage>(body)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("Failed to parse message, nacking: {}", body.take(MAX_LOG_BODY_LENGTH), e)
                ch.basicNack(delivery.envelope.deliveryTag, false, false)
                return@DeliverCallback
            }
            scope.launch {
                try {
                    messageHandler(docMessage.documentId, docMessage.correlationId)
                    ch.basicAck(delivery.envelope.deliveryTag, false)
                } catch (e: Exception) {
                    val requeue = !delivery.envelope.isRedeliver
                    logger.error(
                        "Failed to process message: {} (requeue={})",
                        docMessage.documentId, requeue, e
                    )
                    ch.basicNack(delivery.envelope.deliveryTag, false, requeue)
                }
            }
        }

        consumerTag = ch.basicConsume(
            QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE,
            false,
            deliverCallback,
            CancelCallback { tag -> logger.warn("Consumer {} cancelled by broker", tag) }
        )
        logger.info("RabbitMQ consumer started with tag: {}", consumerTag)
    }

    /**
     * Stops the consumer and closes resources.
     */
    fun stop() {
        logger.info("Stopping RabbitMQ consumer...")
        scope.cancel()

        consumerTag?.let { tag ->
            runCatching { channel?.basicCancel(tag) }
        }
        runCatching { channel?.close() }

        logger.info("RabbitMQ consumer stopped")
    }

    /**
     * Declares the queue topology (must match publisher and DLQ reprocessor).
     */
    private fun declareTopology(channel: Channel) {
        channel.exchangeDeclare(QueueConstants.DOCUMENT_EXCHANGE, "topic", true)
        channel.exchangeDeclare(QueueConstants.DLX_EXCHANGE, "fanout", true)
        channel.queueDeclare(QueueConstants.DLX_QUEUE, true, false, false, null)
        channel.queueBind(QueueConstants.DLX_QUEUE, QueueConstants.DLX_EXCHANGE, "")
        val queueArgs = mapOf<String, Any>(
            "x-dead-letter-exchange" to QueueConstants.DLX_EXCHANGE
        )
        channel.queueDeclare(
            QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, false, false, queueArgs
        )
        channel.queueBind(
            QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE,
            QueueConstants.DOCUMENT_EXCHANGE,
            QueueConstants.CLASSIFICATION_ROUTING_KEY
        )
        channel.exchangeDeclare(QueueConstants.PARKING_LOT_EXCHANGE, "fanout", true)
        channel.queueDeclare(QueueConstants.PARKING_LOT_QUEUE, true, false, false, null)
        channel.queueBind(QueueConstants.PARKING_LOT_QUEUE, QueueConstants.PARKING_LOT_EXCHANGE, "")
        logger.info("RabbitMQ topology declared (consumer)")
    }

    private companion object {
        const val MAX_LOG_BODY_LENGTH = 200
    }
}
