package org.example.pipeline.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.extensions.install
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.testcontainers.containers.RabbitMQContainer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class RabbitMQConsumerTest : FunSpec({

    val rabbit = RabbitMQContainer("rabbitmq:3-management-alpine")
    install(TestContainerSpecExtension(rabbit))
    lateinit var connection: Connection

    beforeSpec {
        connection = ConnectionFactory().apply {
            host = rabbit.host
            port = rabbit.amqpPort
            username = "guest"
            password = "guest"
        }.newConnection("test-consumer")
    }

    afterSpec {
        runCatching { connection.close() }
    }

    // Helper to publish a message directly to the exchange
    fun publishTestMessage(documentId: String, action: String = "classify") {
        val ch = connection.createChannel()
        val message = DocumentMessage(documentId, action)
        val body = Json.encodeToString(message).toByteArray()
        val props = AMQP.BasicProperties.Builder()
            .contentType(QueueConstants.CONTENT_TYPE_JSON)
            .build()
        ch.basicPublish(
            QueueConstants.DOCUMENT_EXCHANGE,
            QueueConstants.CLASSIFICATION_ROUTING_KEY,
            props,
            body
        )
        ch.close()
    }

    context("declareTopology") {
        test("consumer declares same topology as publisher") {
            // Create a consumer — its start() should declare topology
            val received = CompletableDeferred<String>()
            val consumer = RabbitMQConsumer(connection) { docId -> received.complete(docId) }
            consumer.start()

            val ch = connection.createChannel()
            ch.exchangeDeclarePassive(QueueConstants.DOCUMENT_EXCHANGE)
            ch.queueDeclarePassive(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
            ch.exchangeDeclarePassive(QueueConstants.DLX_EXCHANGE)
            ch.queueDeclarePassive(QueueConstants.DLX_QUEUE)
            ch.close()

            consumer.stop()
        }
    }

    context("consume") {
        test("receives published message and passes documentId to handler") {
            val received = CompletableDeferred<String>()
            val consumer = RabbitMQConsumer(connection) { docId -> received.complete(docId) }
            consumer.start()

            val docId = UUID.randomUUID().toString()
            publishTestMessage(docId)

            val result = withTimeout(5.seconds) { received.await() }
            result shouldBe docId

            consumer.stop()
        }

        test("handles multiple messages in order") {
            val ids = List(3) { UUID.randomUUID().toString() }
            val received = CopyOnWriteArrayList<String>()
            val allDone = CompletableDeferred<Unit>()

            val consumer = RabbitMQConsumer(connection) { docId ->
                received.add(docId)
                if (received.size == ids.size) allDone.complete(Unit)
            }
            consumer.start()

            ids.forEach { publishTestMessage(it) }

            withTimeout(5.seconds) { allDone.await() }
            received shouldContainExactly ids

            consumer.stop()
        }
    }

    // Helper to publish raw bytes directly to the exchange
    fun publishRawMessage(rawBody: String) {
        val ch = connection.createChannel()
        val props = AMQP.BasicProperties.Builder()
            .contentType(QueueConstants.CONTENT_TYPE_JSON)
            .build()
        ch.basicPublish(
            QueueConstants.DOCUMENT_EXCHANGE,
            QueueConstants.CLASSIFICATION_ROUTING_KEY,
            props,
            rawBody.toByteArray()
        )
        ch.close()
    }

    context("error handling") {
        test("nacks malformed JSON message and continues processing valid messages") {
            val validId = UUID.randomUUID().toString()
            val received = CompletableDeferred<String>()

            val consumer = RabbitMQConsumer(connection) { docId ->
                received.complete(docId)
            }
            consumer.start()

            publishRawMessage("this is not json")
            publishTestMessage(validId)

            val result = withTimeout(5.seconds) { received.await() }
            result shouldBe validId

            consumer.stop()
        }

        test("requeues message on first handler failure, succeeds on retry") {
            val docId = UUID.randomUUID().toString()
            val received = CompletableDeferred<String>()
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)

            val consumer = RabbitMQConsumer(connection) { id ->
                if (attempts.incrementAndGet() == 1) {
                    throw RuntimeException("Transient failure")
                }
                received.complete(id)
            }
            consumer.start()

            publishTestMessage(docId)

            val result = withTimeout(5.seconds) { received.await() }
            result shouldBe docId
            attempts.get() shouldBe 2

            consumer.stop()
        }

        test("sends to DLQ after second failure and continues processing") {
            val errorId = UUID.randomUUID().toString()
            val successId = UUID.randomUUID().toString()
            val received = CompletableDeferred<String>()

            val consumer = RabbitMQConsumer(connection) { docId ->
                if (docId == errorId) {
                    throw RuntimeException("Permanent failure")
                }
                received.complete(docId)
            }
            consumer.start()

            publishTestMessage(errorId)
            publishTestMessage(successId)

            val result = withTimeout(5.seconds) { received.await() }
            result shouldBe successId

            consumer.stop()
        }
    }

    context("stop") {
        test("stops consuming after stop is called") {
            val received = CopyOnWriteArrayList<String>()
            val consumer = RabbitMQConsumer(connection) { docId ->
                received.add(docId)
            }
            consumer.start()

            val docId = UUID.randomUUID().toString()
            publishTestMessage(docId)

            // Give some time for the message to be consumed
            kotlinx.coroutines.delay(500)

            consumer.stop()

            // The consumer should have processed at least the first message
            // After stop, no more messages should be consumed
        }

        test("consumer stop prevents processing of subsequent messages") {
            val received = CopyOnWriteArrayList<String>()
            val firstReceived = CompletableDeferred<Unit>()

            val consumer = RabbitMQConsumer(connection) { docId ->
                received.add(docId)
                if (received.size == 1) firstReceived.complete(Unit)
            }
            consumer.start()

            // Send first message and wait for it to be consumed
            val preStopId = UUID.randomUUID().toString()
            publishTestMessage(preStopId)
            withTimeout(5.seconds) { firstReceived.await() }

            // Stop the consumer
            consumer.stop()

            // Purge any remaining messages
            val purge = connection.createChannel()
            purge.queuePurge(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
            purge.close()

            // Send messages after stop
            val postStopId = UUID.randomUUID().toString()
            publishTestMessage(postStopId)

            // Wait a bit to ensure no processing happens
            kotlinx.coroutines.delay(1.seconds)

            // Only the pre-stop message should have been processed
            received shouldContainExactly listOf(preStopId)
        }
    }
})
