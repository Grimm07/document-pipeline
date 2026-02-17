package org.example.pipeline.queue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RetryUtilTest : FunSpec({

    val logger = LoggerFactory.getLogger(RetryUtilTest::class.java)

    context("withRetry") {
        test("succeeds on first try without retrying") {
            val attempts = AtomicInteger(0)
            val result = withRetry(RetryConfig(maxRetries = 3), logger, "test-op") {
                attempts.incrementAndGet()
                "success"
            }
            result shouldBe "success"
            attempts.get() shouldBe 1
        }

        test("retries on transient error then succeeds") {
            val attempts = AtomicInteger(0)
            val config = RetryConfig(maxRetries = 3, baseDelayMs = 1, maxDelayMs = 10)
            val result = withRetry(config, logger, "test-op") {
                if (attempts.incrementAndGet() < 3) throw IOException("transient")
                "recovered"
            }
            result shouldBe "recovered"
            attempts.get() shouldBe 3
        }

        test("exhausts all retries then throws last exception") {
            val attempts = AtomicInteger(0)
            val config = RetryConfig(maxRetries = 2, baseDelayMs = 1, maxDelayMs = 10)
            val ex = shouldThrow<IOException> {
                withRetry(config, logger, "test-op") {
                    attempts.incrementAndGet()
                    throw IOException("persistent failure")
                }
            }
            ex.message shouldBe "persistent failure"
            attempts.get() shouldBe 3 // initial + 2 retries
        }

        test("zero retries means no retry, throws immediately") {
            val attempts = AtomicInteger(0)
            val config = RetryConfig(maxRetries = 0, baseDelayMs = 1, maxDelayMs = 10)
            shouldThrow<IOException> {
                withRetry(config, logger, "test-op") {
                    attempts.incrementAndGet()
                    throw IOException("fail")
                }
            }
            attempts.get() shouldBe 1
        }

        test("retryOn predicate filters non-retryable exceptions") {
            val attempts = AtomicInteger(0)
            val config = RetryConfig(maxRetries = 3, baseDelayMs = 1, maxDelayMs = 10)
            shouldThrow<IllegalStateException> {
                withRetry(config, logger, "test-op", retryOn = { it is IOException }) {
                    attempts.incrementAndGet()
                    error("not retryable")
                }
            }
            attempts.get() shouldBe 1 // no retry because predicate returned false
        }

        test("retryOn predicate allows retryable exceptions") {
            val attempts = AtomicInteger(0)
            val config = RetryConfig(maxRetries = 2, baseDelayMs = 1, maxDelayMs = 10)
            val result = withRetry(config, logger, "test-op", retryOn = { it is IOException }) {
                if (attempts.incrementAndGet() < 2) throw IOException("retryable")
                "ok"
            }
            result shouldBe "ok"
            attempts.get() shouldBe 2
        }
    }

    context("calculateDelay") {
        test("first attempt uses base delay") {
            calculateDelay(baseDelayMs = 500, maxDelayMs = 10_000, attempt = 0) shouldBe 500L
        }

        test("delay doubles each attempt") {
            calculateDelay(baseDelayMs = 500, maxDelayMs = 100_000, attempt = 1) shouldBe 1000L
            calculateDelay(baseDelayMs = 500, maxDelayMs = 100_000, attempt = 2) shouldBe 2000L
            calculateDelay(baseDelayMs = 500, maxDelayMs = 100_000, attempt = 3) shouldBe 4000L
        }

        test("delay is capped at maxDelayMs") {
            calculateDelay(baseDelayMs = 500, maxDelayMs = 3000, attempt = 10) shouldBe 3000L
        }

        test("delay equals maxDelayMs when base equals max") {
            calculateDelay(baseDelayMs = 1000, maxDelayMs = 1000, attempt = 5) shouldBe 1000L
        }

        test("all calculated delays are within bounds") {
            for (attempt in 0..20) {
                val delay = calculateDelay(baseDelayMs = 100, maxDelayMs = 5000, attempt = attempt)
                delay shouldBeLessThanOrEqual 5000L
            }
        }
    }
})
