package org.example.pipeline.queue

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.extensions.install
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.testcontainers.containers.RabbitMQContainer
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class RabbitMQPublisherTest : FunSpec({

    val rabbit = RabbitMQContainer("rabbitmq:3-management-alpine")
    install(TestContainerSpecExtension(rabbit))
    lateinit var connection: Connection
    lateinit var publisher: RabbitMQPublisher

    beforeSpec {
        connection = ConnectionFactory().apply {
            host = rabbit.host
            port = rabbit.amqpPort
            username = "guest"
            password = "guest"
        }.newConnection("test-publisher")
        publisher = RabbitMQPublisher(connection)
        // Trigger lazy channel init to declare topology before topology tests
        publisher.publish(UUID.randomUUID().toString())
    }

    afterSpec {
        runCatching { publisher.close() }
    }

    beforeEach {
        // Purge queue to isolate tests
        runCatching {
            val ch = connection.createChannel()
            ch.queuePurge(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
            ch.close()
        }
    }

    context("declareTopology") {
        test("main exchange exists") {
            val ch = connection.createChannel()
            // passiveDeclare will throw if exchange doesn't exist
            ch.exchangeDeclarePassive(QueueConstants.DOCUMENT_EXCHANGE)
            ch.close()
        }

        test("main queue exists") {
            val ch = connection.createChannel()
            ch.queueDeclarePassive(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
            ch.close()
        }

        test("DLX exchange exists") {
            val ch = connection.createChannel()
            ch.exchangeDeclarePassive(QueueConstants.DLX_EXCHANGE)
            ch.close()
        }

        test("DLX queue exists") {
            val ch = connection.createChannel()
            ch.queueDeclarePassive(QueueConstants.DLX_QUEUE)
            ch.close()
        }
    }

    context("publish") {
        test("message is consumable from the queue") {
            val docId = UUID.randomUUID().toString()
            publisher.publish(docId)

            val ch = connection.createChannel()
            val deferred = CompletableDeferred<String>()

            ch.basicConsume(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, { _, delivery ->
                deferred.complete(String(delivery.body))
            }, { _ -> })

            val body = withTimeout(5.seconds) { deferred.await() }
            body shouldContain docId
            ch.close()
        }

        test("message contains correct JSON with documentId") {
            val docId = UUID.randomUUID().toString()
            publisher.publish(docId)

            val ch = connection.createChannel()
            val deferred = CompletableDeferred<String>()

            ch.basicConsume(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, { _, delivery ->
                deferred.complete(String(delivery.body))
            }, { _ -> })

            val body = withTimeout(5.seconds) { deferred.await() }
            val message = Json.decodeFromString<DocumentMessage>(body)
            message.documentId shouldBe docId
            ch.close()
        }

        test("message has default action of classify") {
            val docId = UUID.randomUUID().toString()
            publisher.publish(docId)

            val ch = connection.createChannel()
            val deferred = CompletableDeferred<String>()

            ch.basicConsume(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, { _, delivery ->
                deferred.complete(String(delivery.body))
            }, { _ -> })

            val body = withTimeout(5.seconds) { deferred.await() }
            val message = Json.decodeFromString<DocumentMessage>(body)
            message.action shouldBe "classify"
            ch.close()
        }

        test("documentId survives publish then consume round-trip unchanged") {
            val exactId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            publisher.publish(exactId)

            val ch = connection.createChannel()
            val deferred = CompletableDeferred<String>()

            ch.basicConsume(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, { _, delivery ->
                val msg = Json.decodeFromString<DocumentMessage>(String(delivery.body))
                deferred.complete(msg.documentId)
            }, { _ -> })

            val received = withTimeout(5.seconds) { deferred.await() }
            received shouldBe exactId
            ch.close()
        }

        test("malformed message published directly is routed to DLX queue") {
            // Purge DLX queue first
            val purge = connection.createChannel()
            purge.queuePurge(QueueConstants.DLX_QUEUE)
            purge.close()

            // Start a consumer so malformed message gets NACK'd to DLX
            val processed = CompletableDeferred<Unit>()
            val consumer = RabbitMQConsumer(connection) { processed.complete(Unit) }
            consumer.start()

            // Publish raw non-JSON directly to the exchange
            val ch = connection.createChannel()
            ch.basicPublish(
                QueueConstants.DOCUMENT_EXCHANGE,
                QueueConstants.CLASSIFICATION_ROUTING_KEY,
                com.rabbitmq.client.AMQP.BasicProperties.Builder()
                    .contentType(QueueConstants.CONTENT_TYPE_JSON)
                    .build(),
                "not valid json {{{{".toByteArray()
            )
            ch.close()

            // Wait briefly for the consumer to NACK the malformed message
            kotlinx.coroutines.delay(2.seconds)
            consumer.stop()

            // Check the DLX queue for the rejected message
            val dlxCh = connection.createChannel()
            val dlxMsg = dlxCh.basicGet(QueueConstants.DLX_QUEUE, true)
            dlxMsg shouldNotBe null
            String(dlxMsg.body) shouldContain "not valid json"
            dlxCh.close()
        }

        test("multiple messages are all delivered") {
            val ids = List(3) { UUID.randomUUID().toString() }
            ids.forEach { publisher.publish(it) }

            val ch = connection.createChannel()
            val received = mutableListOf<String>()
            val allReceived = CompletableDeferred<Unit>()

            ch.basicConsume(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, { _, delivery ->
                val msg = Json.decodeFromString<DocumentMessage>(String(delivery.body))
                received.add(msg.documentId)
                if (received.size == 3) allReceived.complete(Unit)
            }, { _ -> })

            withTimeout(5.seconds) { allReceived.await() }
            received.toSet() shouldBe ids.toSet()
            ch.close()
        }
    }
})
