package org.example.pipeline.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.DeliverCallback
import org.slf4j.LoggerFactory

/**
 * Reprocesses messages from the dead letter queue with exponential backoff.
 *
 * Messages that have cycled through the DLQ fewer than [DlqReprocessorConfig.maxRetryCycles]
 * times are republished to the main exchange after a backoff delay. Messages exceeding the
 * limit are routed to the parking lot queue for manual inspection.
 *
 * Uses `basicQos(1)` for single-message processing. The backoff delay blocks the consumer
 * callback thread, naturally throttling DLQ consumption — RabbitMQ won't deliver the next
 * message until the current one is acked.
 *
 * @param connection RabbitMQ connection
 * @param config Reprocessing parameters
 * @param onReprocessed Optional callback invoked when a message is republished (for metrics)
 * @param onParked Optional callback invoked when a message is parked (for metrics)
 */
class DlqReprocessor(
    private val connection: Connection,
    private val config: DlqReprocessorConfig = DlqReprocessorConfig(),
    private val onReprocessed: () -> Unit = {},
    private val onParked: () -> Unit = {}
) {
    private val logger = LoggerFactory.getLogger(DlqReprocessor::class.java)

    private var channel: Channel? = null
    private var consumerTag: String? = null

    /**
     * Starts consuming from the dead letter queue.
     *
     * Processing is synchronous per message with `basicQos(1)`.
     * The backoff [Thread.sleep] blocks the consumer callback thread,
     * which is safe because DLQ throughput is intentionally low.
     */
    @Suppress("TooGenericExceptionCaught") // Must catch all to nack and prevent consumer death
    fun start() {
        val ch = connection.createChannel().also { declareTopology(it) }
        channel = ch
        ch.basicQos(1)

        val deliverCallback = DeliverCallback { _, delivery ->
            try {
                val deathCount = extractDeathCount(delivery.properties)
                val body = delivery.body

                if (deathCount <= config.maxRetryCycles) {
                    val delayMs = calculateDelay(
                        config.baseDelayMs, config.maxDelayMs,
                        maxOf(0, deathCount - 1)
                    )
                    logger.info(
                        "Reprocessing DLQ message (death count={}/{}, delay={}ms): {}",
                        deathCount, config.maxRetryCycles, delayMs, String(body).take(MAX_LOG_BODY_LENGTH)
                    )
                    Thread.sleep(delayMs)
                    ch.basicPublish(
                        QueueConstants.DOCUMENT_EXCHANGE,
                        QueueConstants.CLASSIFICATION_ROUTING_KEY,
                        rebuildProperties(delivery.properties),
                        body
                    )
                    ch.basicAck(delivery.envelope.deliveryTag, false)
                    onReprocessed()
                } else {
                    logger.warn(
                        "Parking DLQ message after {} deaths (limit={}): {}",
                        deathCount, config.maxRetryCycles, String(body).take(MAX_LOG_BODY_LENGTH)
                    )
                    ch.basicPublish(
                        QueueConstants.PARKING_LOT_EXCHANGE,
                        "",
                        rebuildProperties(delivery.properties),
                        body
                    )
                    ch.basicAck(delivery.envelope.deliveryTag, false)
                    onParked()
                }
            } catch (e: Exception) {
                logger.error("Error reprocessing DLQ message, nacking", e)
                runCatching { ch.basicNack(delivery.envelope.deliveryTag, false, true) }
            }
        }

        consumerTag = ch.basicConsume(
            QueueConstants.DLX_QUEUE,
            false,
            deliverCallback,
            CancelCallback { tag -> logger.warn("DLQ consumer {} cancelled by broker", tag) }
        )
        logger.info("DLQ reprocessor started with tag: {} (maxRetryCycles={})", consumerTag, config.maxRetryCycles)
    }

    /**
     * Stops the DLQ reprocessor and releases resources.
     */
    fun stop() {
        logger.info("Stopping DLQ reprocessor...")
        consumerTag?.let { tag ->
            runCatching { channel?.basicCancel(tag) }
        }
        runCatching { channel?.close() }
        logger.info("DLQ reprocessor stopped")
    }

    /**
     * Declares the full topology (must match publisher and consumer).
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
        logger.info("RabbitMQ topology declared (DLQ reprocessor)")
    }

    /**
     * Rebuilds message properties preserving content type and delivery mode.
     *
     * Does NOT carry forward `x-death` headers — RabbitMQ adds fresh x-death
     * entries when the message gets dead-lettered again.
     */
    private fun rebuildProperties(original: AMQP.BasicProperties): AMQP.BasicProperties =
        AMQP.BasicProperties.Builder()
            .contentType(original.contentType ?: QueueConstants.CONTENT_TYPE_JSON)
            .deliveryMode(original.deliveryMode ?: 2)
            .build()

    private companion object {
        const val MAX_LOG_BODY_LENGTH = 200
    }
}

/**
 * Extracts the total death count from RabbitMQ `x-death` headers.
 *
 * RabbitMQ stores `x-death` as `List<Map<String, Any>>`. Each entry
 * has a `count` field (Long) representing how many times the message
 * was dead-lettered for that reason from that queue. We sum all counts.
 *
 * Returns 0 if no `x-death` header is present.
 */
@Suppress("UNCHECKED_CAST") // RabbitMQ x-death header structure is well-defined
internal fun extractDeathCount(properties: AMQP.BasicProperties): Int {
    val headers = properties.headers ?: return 0
    val xDeath = headers["x-death"] as? List<Map<String, Any>> ?: return 0
    return xDeath.sumOf { entry ->
        when (val count = entry["count"]) {
            is Long -> count.toInt()
            is Int -> count
            else -> 0
        }
    }
}
