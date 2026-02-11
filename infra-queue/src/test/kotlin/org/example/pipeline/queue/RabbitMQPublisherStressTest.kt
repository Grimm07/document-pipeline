package org.example.pipeline.queue

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.extensions.install
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.testcontainers.containers.RabbitMQContainer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private data class PublishResult(val clientId: Int, val docId: String)

class RabbitMQPublisherStressTest : FunSpec({

    val rabbit = RabbitMQContainer("rabbitmq:3-management-alpine")
    install(TestContainerSpecExtension(rabbit))
    lateinit var connection: Connection
    lateinit var publisher: RabbitMQPublisher
    val json = Json { ignoreUnknownKeys = true }

    fun drainQueue(expectedCount: Int, timeout: kotlin.time.Duration = 10.seconds): List<DocumentMessage> {
        val received = CopyOnWriteArrayList<DocumentMessage>()
        val ch = connection.createChannel()
        val allReceived = CompletableDeferred<Unit>()

        ch.basicConsume(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE, true, { _, delivery ->
            val msg = json.decodeFromString<DocumentMessage>(String(delivery.body))
            received.add(msg)
            if (received.size >= expectedCount) allReceived.complete(Unit)
        }, { _ -> })

        kotlinx.coroutines.runBlocking {
            withTimeout(timeout) { allReceived.await() }
        }
        ch.close()
        return received.toList()
    }

    beforeSpec {
        connection = ConnectionFactory().apply {
            host = rabbit.host
            port = rabbit.amqpPort
            username = "guest"
            password = "guest"
        }.newConnection("stress-test-publisher")
        publisher = RabbitMQPublisher(connection)
    }

    afterSpec {
        runCatching { publisher.close() }
    }

    beforeEach {
        // Purge the queue to start clean
        runCatching {
            val ch = connection.createChannel()
            ch.queuePurge(QueueConstants.DOCUMENT_CLASSIFICATION_QUEUE)
            ch.close()
        }
    }

    // ── Concurrent publish ───────────────────────────────────
    context("concurrent publish") {

        test("single client: 50 publishes all arrive") {
            val ids = (1..50).map { UUID.randomUUID().toString() }

            coroutineScope {
                ids.mapIndexed { i, docId ->
                    async(CoroutineName("publish-$i")) {
                        publisher.publish(docId)
                        PublishResult(0, docId)
                    }
                }.awaitAll()
            }

            val received = drainQueue(50)
            received.shouldHaveSize(50)

            val receivedIds = received.map { it.documentId }.toSet()
            withClue("all 50 unique IDs should be received") {
                receivedIds.shouldHaveSize(50)
            }
            ids.forEach { id ->
                withClue("docId=$id should be in received set") {
                    receivedIds.contains(id) shouldBe true
                }
            }
        }

        test("multi-client: 10 clients x 20 publishes each") {
            val allIds = CopyOnWriteArrayList<String>()

            coroutineScope {
                (1..10).map { clientId ->
                    async(CoroutineName("client-$clientId")) {
                        (1..20).map { opId ->
                            async(CoroutineName("client-$clientId/op-$opId")) {
                                val docId = UUID.randomUUID().toString()
                                allIds.add(docId)
                                publisher.publish(docId)
                                PublishResult(clientId, docId)
                            }
                        }.awaitAll()
                    }
                }.awaitAll()
            }

            val received = drainQueue(200, 15.seconds)
            received.shouldHaveSize(200)

            val receivedIds = received.map { it.documentId }.toSet()
            withClue("all 200 unique IDs should be received, no duplicates") {
                receivedIds.shouldHaveSize(200)
            }
        }

        test("all messages delivered under contention") {
            val ids = (1..50).map { UUID.randomUUID().toString() }

            coroutineScope {
                ids.mapIndexed { i, docId ->
                    async(CoroutineName("contend-$i")) {
                        publisher.publish(docId)
                    }
                }.awaitAll()
            }

            val received = drainQueue(50)
            val receivedIds = received.map { it.documentId }.toSet()
            withClue("all 50 messages should arrive (order may vary)") {
                receivedIds shouldBe ids.toSet()
            }
        }
    }

    // ── Sustained load ───────────────────────────────────────
    context("sustained load") {

        test("100 rapid sequential publishes all arrive") {
            val ids = (1..100).map { UUID.randomUUID().toString() }

            ids.forEach { docId ->
                publisher.publish(docId)
            }

            val received = drainQueue(100, 15.seconds)
            received.shouldHaveSize(100)

            val receivedIds = received.map { it.documentId }.toSet()
            withClue("all 100 sequential messages should arrive") {
                receivedIds shouldBe ids.toSet()
            }
        }
    }
})
