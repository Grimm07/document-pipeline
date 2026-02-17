package org.example.pipeline.queue

import com.rabbitmq.client.AMQP
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DlqReprocessorTest : FunSpec({

    context("extractDeathCount") {
        test("returns 0 when no headers present") {
            val props = AMQP.BasicProperties.Builder().build()
            extractDeathCount(props) shouldBe 0
        }

        test("returns 0 when headers present but no x-death") {
            val props = AMQP.BasicProperties.Builder()
                .headers(mapOf("some-header" to "value"))
                .build()
            extractDeathCount(props) shouldBe 0
        }

        test("returns 0 when x-death is not a list") {
            val props = AMQP.BasicProperties.Builder()
                .headers(mapOf("x-death" to "not-a-list"))
                .build()
            extractDeathCount(props) shouldBe 0
        }

        test("extracts count from single x-death entry") {
            val xDeath = listOf(
                mapOf<String, Any>(
                    "queue" to "document.classification.queue",
                    "reason" to "rejected",
                    "count" to 1L,
                    "exchange" to "document.dlx.exchange"
                )
            )
            val props = AMQP.BasicProperties.Builder()
                .headers(mapOf("x-death" to xDeath))
                .build()
            extractDeathCount(props) shouldBe 1
        }

        test("sums counts from multiple x-death entries") {
            val xDeath = listOf(
                mapOf<String, Any>(
                    "queue" to "document.classification.queue",
                    "reason" to "rejected",
                    "count" to 2L
                ),
                mapOf<String, Any>(
                    "queue" to "document.classification.queue",
                    "reason" to "expired",
                    "count" to 1L
                )
            )
            val props = AMQP.BasicProperties.Builder()
                .headers(mapOf("x-death" to xDeath))
                .build()
            extractDeathCount(props) shouldBe 3
        }

        test("handles Int count values") {
            val xDeath = listOf(
                mapOf<String, Any>(
                    "queue" to "document.classification.queue",
                    "reason" to "rejected",
                    "count" to 5 // Int, not Long
                )
            )
            val props = AMQP.BasicProperties.Builder()
                .headers(mapOf("x-death" to xDeath))
                .build()
            extractDeathCount(props) shouldBe 5
        }

        test("ignores entries with missing count field") {
            val xDeath = listOf(
                mapOf<String, Any>(
                    "queue" to "document.classification.queue",
                    "reason" to "rejected"
                    // no count field
                ),
                mapOf<String, Any>(
                    "queue" to "document.classification.queue",
                    "reason" to "expired",
                    "count" to 2L
                )
            )
            val props = AMQP.BasicProperties.Builder()
                .headers(mapOf("x-death" to xDeath))
                .build()
            extractDeathCount(props) shouldBe 2
        }

        test("ignores entries with non-numeric count") {
            val xDeath = listOf(
                mapOf<String, Any>(
                    "queue" to "document.classification.queue",
                    "reason" to "rejected",
                    "count" to "not-a-number"
                )
            )
            val props = AMQP.BasicProperties.Builder()
                .headers(mapOf("x-death" to xDeath))
                .build()
            extractDeathCount(props) shouldBe 0
        }
    }

    context("calculateDelay") {
        test("first DLQ cycle (deathCount-1 = 0) uses base delay") {
            calculateDelay(baseDelayMs = 5000, maxDelayMs = 60_000, attempt = 0) shouldBe 5000L
        }

        test("second cycle doubles the delay") {
            calculateDelay(baseDelayMs = 5000, maxDelayMs = 60_000, attempt = 1) shouldBe 10_000L
        }

        test("delay is capped at maxDelayMs") {
            calculateDelay(baseDelayMs = 5000, maxDelayMs = 60_000, attempt = 10) shouldBe 60_000L
        }
    }
})
