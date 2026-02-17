package org.example.pipeline.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.testcontainers.containers.RabbitMQContainer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class DlqReprocessorIntegrationTest : FunSpec({

    val rabbit = RabbitMQContainer("rabbitmq:3-management-alpine")
    install(TestContainerSpecExtension(rabbit))

    fun createConnection(name: String): Connection = ConnectionFactory().apply {
        host = rabbit.host
        port = rabbit.amqpPort
        username = "guest"
        password = "guest"
    }.newConnection(name)

    fun setupTopology(conn: Connection) {
        val ch = conn.createChannel()
        ch.exchangeDeclare(QueueConstants.DOCUMENT_EXCHANGE, "topic", true)
        ch.exchangeDeclare(QueueConstants.DLX_EXCHANGE, "fanout", true)
        ch.queueDeclare(QueueConstants.DLX_QUEUE, true, false, false, null)
        ch.queueBind(QueueConstants.DLX_QUEUE, QueueConstants.DLX_EXCHANGE, "")
        val queueArgs = mapOf<String, Any>("x-dead-letter-exchange" to QueueConstants.DLX_EXCHANGE)
        ch.queueDeclare(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, false, false, queueArgs)
        ch.queueBind(
            QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE,
            QueueConstants.DOCUMENT_EXCHANGE,
            QueueConstants.CLASSIFICATION_ROUTING_KEY
        )
        ch.exchangeDeclare(QueueConstants.PARKING_LOT_EXCHANGE, "fanout", true)
        ch.queueDeclare(QueueConstants.PARKING_LOT_QUEUE, true, false, false, null)
        ch.queueBind(QueueConstants.PARKING_LOT_QUEUE, QueueConstants.PARKING_LOT_EXCHANGE, "")
        ch.queuePurge(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
        ch.queuePurge(QueueConstants.DLX_QUEUE)
        ch.queuePurge(QueueConstants.PARKING_LOT_QUEUE)
        ch.close()
    }

    fun pollQueue(conn: Connection, queueName: String, timeoutMs: Long = 10_000): GetResponse? {
        val ch = conn.createChannel()
        val deadline = System.currentTimeMillis() + timeoutMs
        var msg: GetResponse? = null
        while (System.currentTimeMillis() < deadline) {
            msg = ch.basicGet(queueName, true)
            if (msg != null) break
            Thread.sleep(200)
        }
        ch.close()
        return msg
    }

    /**
     * Publishes a message to the main queue then rejects it, causing RabbitMQ
     * to dead-letter it to the DLQ with real x-death headers.
     */
    fun deadLetterMessage(conn: Connection, body: ByteArray, props: AMQP.BasicProperties) {
        val ch = conn.createChannel()
        ch.basicPublish(
            QueueConstants.DOCUMENT_EXCHANGE,
            QueueConstants.CLASSIFICATION_ROUTING_KEY,
            props,
            body
        )
        // Poll until message arrives on main queue, then reject it
        val deadline = System.currentTimeMillis() + 5_000
        var msg: GetResponse? = null
        while (System.currentTimeMillis() < deadline) {
            msg = ch.basicGet(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, false)
            if (msg != null) break
            Thread.sleep(50)
        }
        check(msg != null) { "Message not found on main queue within timeout" }
        ch.basicNack(msg.envelope.deliveryTag, false, false)
        ch.close()
    }

    test("message under retry limit is republished to main queue") {
        val conn = createConnection("test-reprocess")
        setupTopology(conn)
        val reprocessedCount = AtomicInteger(0)

        val docId = UUID.randomUUID().toString()
        val body = Json.encodeToString(DocumentMessage.serializer(), DocumentMessage(docId))
        val props = AMQP.BasicProperties.Builder()
            .contentType(QueueConstants.CONTENT_TYPE_JSON)
            .deliveryMode(2)
            .build()

        // Dead-letter the message via real RabbitMQ rejection (creates genuine x-death headers)
        deadLetterMessage(conn, body.toByteArray(), props)

        // Start DlqReprocessor — deathCount=1 <= maxRetryCycles=3 → reprocess
        val config = DlqReprocessorConfig(maxRetryCycles = 3, baseDelayMs = 50, maxDelayMs = 200)
        val reprocessor = DlqReprocessor(
            conn, config,
            onReprocessed = { reprocessedCount.incrementAndGet() }
        )
        reprocessor.start()

        val mainMsg = pollQueue(conn, QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
        mainMsg shouldNotBe null
        val receivedMsg = Json.decodeFromString<DocumentMessage>(String(mainMsg!!.body))
        receivedMsg.documentId shouldBe docId

        Thread.sleep(200)
        reprocessedCount.get() shouldBe 1

        reprocessor.stop()
        conn.close()
    }

    test("message over retry limit routes to parking lot queue") {
        val conn = createConnection("test-parking")
        setupTopology(conn)
        val parkedCount = AtomicInteger(0)

        val docId = UUID.randomUUID().toString()
        val body = Json.encodeToString(DocumentMessage.serializer(), DocumentMessage(docId))
        val props = AMQP.BasicProperties.Builder()
            .contentType(QueueConstants.CONTENT_TYPE_JSON)
            .deliveryMode(2)
            .build()

        // Dead-letter the message (creates x-death with count=1)
        deadLetterMessage(conn, body.toByteArray(), props)

        // Start DlqReprocessor with maxRetryCycles=0 → deathCount=1 > 0 → park immediately
        val config = DlqReprocessorConfig(maxRetryCycles = 0, baseDelayMs = 50, maxDelayMs = 200)
        val reprocessor = DlqReprocessor(
            conn, config,
            onParked = { parkedCount.incrementAndGet() }
        )
        reprocessor.start()

        val parkedMsg = pollQueue(conn, QueueConstants.PARKING_LOT_QUEUE, 5_000)
        val mainMsg = pollQueue(conn, QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, 1_000)

        parkedMsg shouldNotBe null
        mainMsg shouldBe null
        val receivedMsg = Json.decodeFromString<DocumentMessage>(String(parkedMsg!!.body))
        receivedMsg.documentId shouldBe docId

        Thread.sleep(200)
        parkedCount.get() shouldBe 1

        reprocessor.stop()
        conn.close()
    }

    test("parking lot topology exists after reprocessor starts") {
        val conn = createConnection("test-topology")
        val reprocessor = DlqReprocessor(
            conn,
            DlqReprocessorConfig(baseDelayMs = 50, maxDelayMs = 200)
        )
        reprocessor.start()

        val ch = conn.createChannel()
        ch.exchangeDeclarePassive(QueueConstants.PARKING_LOT_EXCHANGE)
        ch.queueDeclarePassive(QueueConstants.PARKING_LOT_QUEUE)
        ch.close()

        reprocessor.stop()
        conn.close()
    }

    test("reprocessor stop prevents further processing") {
        val conn = createConnection("test-stop")
        setupTopology(conn)
        val reprocessedCount = AtomicInteger(0)

        val config = DlqReprocessorConfig(maxRetryCycles = 3, baseDelayMs = 50, maxDelayMs = 200)
        val reprocessor = DlqReprocessor(conn, config, onReprocessed = { reprocessedCount.incrementAndGet() })
        reprocessor.start()
        reprocessor.stop()

        // Publish directly to DLQ after stopping — should not be consumed
        val ch = conn.createChannel()
        ch.basicPublish("", QueueConstants.DLX_QUEUE, null, "test".toByteArray())
        ch.close()

        delay(1.seconds)
        reprocessedCount.get() shouldBe 0
        conn.close()
    }

    test("message with no x-death header is treated as first death") {
        val conn = createConnection("test-no-xdeath")
        setupTopology(conn)
        val reprocessedCount = AtomicInteger(0)

        // Start reprocessor FIRST, then publish — avoids race where message sits unconsumed
        val config = DlqReprocessorConfig(maxRetryCycles = 3, baseDelayMs = 50, maxDelayMs = 200)
        val reprocessor = DlqReprocessor(
            conn, config,
            onReprocessed = { reprocessedCount.incrementAndGet() }
        )
        reprocessor.start()

        // Publish directly to DLQ with no x-death headers
        val ch = conn.createChannel()
        val docId = UUID.randomUUID().toString()
        val body = Json.encodeToString(DocumentMessage.serializer(), DocumentMessage(docId))
        val props = AMQP.BasicProperties.Builder()
            .contentType(QueueConstants.CONTENT_TYPE_JSON)
            .deliveryMode(2)
            .build()
        ch.basicPublish("", QueueConstants.DLX_QUEUE, props, body.toByteArray())
        ch.close()

        val mainMsg = pollQueue(conn, QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
        mainMsg shouldNotBe null
        val receivedMsg = Json.decodeFromString<DocumentMessage>(String(mainMsg!!.body))
        receivedMsg.documentId shouldBe docId

        Thread.sleep(500)
        reprocessedCount.get() shouldBe 1

        reprocessor.stop()
        conn.close()
    }
})
