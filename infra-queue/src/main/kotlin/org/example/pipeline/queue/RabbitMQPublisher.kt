package org.example.pipeline.queue

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

    private val channel: Channel by lazy {
        connection.createChannel().also { ch ->
            declareTopology(ch)
        }
    }

    override suspend fun publish(documentId: String): Unit = withContext(Dispatchers.IO) {
        TODO("Implement: Create DocumentMessage with documentId, serialize to JSON, publish to exchange with routing key")
    }

    /**
     * Declares the exchange, queue, and binding.
     */
    private fun declareTopology(channel: Channel) {
        TODO("Implement: Declare exchange (topic), declare queue with DLX, bind queue to exchange with routing key")
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
