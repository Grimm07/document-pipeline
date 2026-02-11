package org.example.pipeline.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.extensions.install
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.testcontainers.containers.RabbitMQContainer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class RabbitMQConsumerStressTest : FunSpec({

    val rabbit = RabbitMQContainer("rabbitmq:3-management-alpine")
    install(TestContainerSpecExtension(rabbit))
    lateinit var connection: Connection

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

    beforeSpec {
        connection = ConnectionFactory().apply {
            host = rabbit.host
            port = rabbit.amqpPort
            username = "guest"
            password = "guest"
        }.newConnection("stress-test-consumer")

        // Declare topology once so the queue exists for purging
        val ch = connection.createChannel()
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
        ch.close()
    }

    afterSpec {
        runCatching { connection.close() }
    }

    beforeEach {
        // Purge queues to isolate tests
        val ch = connection.createChannel()
        runCatching { ch.queuePurge(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE) }
        runCatching { ch.queuePurge(QueueConstants.DLX_QUEUE) }
        ch.close()
    }

    // ── High volume ──────────────────────────────────────────
    context("high volume") {

        test("50 messages consumed") {
            val ids = (1..50).map { UUID.randomUUID().toString() }
            ids.forEach { publishTestMessage(it) }

            val received = CopyOnWriteArrayList<String>()
            val allDone = CompletableDeferred<Unit>()

            val consumer = RabbitMQConsumer(connection) { docId ->
                received.add(docId)
                if (received.size >= 50) allDone.complete(Unit)
            }
            consumer.start()

            withTimeout(15.seconds) { allDone.await() }
            consumer.stop()

            received.shouldHaveSize(50)
            withClue("all 50 IDs should be received") {
                received.toSet() shouldBe ids.toSet()
            }
        }

        test("100 messages consumed") {
            val ids = (1..100).map { UUID.randomUUID().toString() }
            ids.forEach { publishTestMessage(it) }

            val received = CopyOnWriteArrayList<String>()
            val allDone = CompletableDeferred<Unit>()

            val consumer = RabbitMQConsumer(connection) { docId ->
                received.add(docId)
                if (received.size >= 100) allDone.complete(Unit)
            }
            consumer.start()

            withTimeout(30.seconds) { allDone.await() }
            consumer.stop()

            received.shouldHaveSize(100)
            withClue("all 100 IDs should be received") {
                received.toSet() shouldBe ids.toSet()
            }
        }
    }

    // ── Error resilience ─────────────────────────────────────
    context("error resilience") {

        test("handler throws on 10 of 50: remaining 40 still processed") {
            val ids = (1..50).map { UUID.randomUUID().toString() }
            val errorIds = ids.take(10).toSet()
            ids.forEach { publishTestMessage(it) }

            val succeeded = CopyOnWriteArrayList<String>()
            val allSucceeded = CompletableDeferred<Unit>()

            // Error IDs are retried once (requeue) then DLQ'd — 20 handler calls for errors.
            // 40 success IDs are processed normally — 40 handler calls.
            // Total: 60 handler invocations with basicQos(1).
            val consumer = RabbitMQConsumer(connection) { docId ->
                if (docId in errorIds) {
                    throw RuntimeException("Simulated failure for $docId")
                }
                succeeded.add(docId)
                if (succeeded.size >= 40) allSucceeded.complete(Unit)
            }
            consumer.start()

            withTimeout(15.seconds) { allSucceeded.await() }
            consumer.stop()

            withClue("40 non-error messages should succeed") {
                succeeded.size shouldBe 40
            }
            withClue("all 40 successful IDs should be the non-error ones") {
                succeeded.toSet() shouldBe (ids.toSet() - errorIds)
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────
    context("lifecycle") {

        test("start/stop/start cycles: process messages across restarts") {
            // Phase 1: publish 10, consume them
            val ids1 = (1..10).map { UUID.randomUUID().toString() }
            ids1.forEach { publishTestMessage(it) }

            val received1 = CopyOnWriteArrayList<String>()
            val done1 = CompletableDeferred<Unit>()

            val consumer1 = RabbitMQConsumer(connection) { docId ->
                received1.add(docId)
                if (received1.size >= 10) done1.complete(Unit)
            }
            consumer1.start()
            withTimeout(10.seconds) { done1.await() }
            consumer1.stop()

            withClue("phase 1: all 10 messages consumed") {
                received1.shouldHaveSize(10)
            }

            // Small delay to allow cleanup
            delay(500)

            // Phase 2: publish 10 more, new consumer picks them up
            val ids2 = (1..10).map { UUID.randomUUID().toString() }
            ids2.forEach { publishTestMessage(it) }

            val received2 = CopyOnWriteArrayList<String>()
            val done2 = CompletableDeferred<Unit>()

            val consumer2 = RabbitMQConsumer(connection) { docId ->
                received2.add(docId)
                if (received2.size >= 10) done2.complete(Unit)
            }
            consumer2.start()
            withTimeout(10.seconds) { done2.await() }
            consumer2.stop()

            withClue("phase 2: all 10 messages consumed by new consumer") {
                received2.shouldHaveSize(10)
            }

            val totalReceived = (received1 + received2).toSet()
            withClue("20 unique messages across both phases") {
                totalReceived.shouldHaveSize(20)
            }
        }
    }
})
