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
        TODO("Implement: Create channel, declare topology, set up consumer with DeliverCallback, handle ack/nack")
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
        TODO("Implement: Declare exchange, queue with DLX, and binding - same as publisher")
    }
}
