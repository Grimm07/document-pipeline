package org.example.pipeline.queue

import com.rabbitmq.client.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * RabbitMQ consumer for document processing messages.
 *
 * Listens on the classification queue and dispatches messages to a handler.
 *
 * @property connection RabbitMQ connection
 * @property messageHandler Callback invoked for each received document ID (as string)
 */
class RabbitMQConsumer(
    private val connection: Connection,
    private val messageHandler: suspend (String) -> Unit
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
    fun start() {
        channel = connection.createChannel().also { declareTopology(it) }
        channel!!.basicQos(1)

        val deliverCallback = DeliverCallback { _, delivery ->
            val body = String(delivery.body)
            val docMessage = try {
                json.decodeFromString<DocumentMessage>(body)
            } catch (e: Exception) {
                logger.error("Failed to parse message, nacking: {}", body.take(200), e)
                channel!!.basicNack(delivery.envelope.deliveryTag, false, false)
                return@DeliverCallback
            }
            scope.launch {
                try {
                    messageHandler(docMessage.documentId)
                    channel!!.basicAck(delivery.envelope.deliveryTag, false)
                } catch (e: Exception) {
                    val requeue = !delivery.envelope.isRedeliver
                    logger.error(
                        "Failed to process message: {} (requeue={})",
                        docMessage.documentId, requeue, e
                    )
                    channel!!.basicNack(delivery.envelope.deliveryTag, false, requeue)
                }
            }
        }

        consumerTag = channel!!.basicConsume(
            QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE,
            false,
            deliverCallback,
            CancelCallback { tag -> logger.warn("Consumer $tag cancelled by broker") }
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
     * Declares the queue topology (should match publisher).
     */
    private fun declareTopology(channel: Channel) {
        channel.exchangeDeclare(QueueConstants.DOCUMENT_EXCHANGE, "topic", true)
        channel.exchangeDeclare(QueueConstants.DLX_EXCHANGE, "fanout", true)
        channel.queueDeclare(QueueConstants.DLX_QUEUE, true, false, false, null)
        channel.queueBind(QueueConstants.DLX_QUEUE, QueueConstants.DLX_EXCHANGE, "")
        val queueArgs = mapOf<String, Any>("x-dead-letter-exchange" to QueueConstants.DLX_EXCHANGE)
        channel.queueDeclare(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, false, false, queueArgs)
        channel.queueBind(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, QueueConstants.DOCUMENT_EXCHANGE, QueueConstants.CLASSIFICATION_ROUTING_KEY)
        logger.info("RabbitMQ topology declared (consumer)")
    }
}
