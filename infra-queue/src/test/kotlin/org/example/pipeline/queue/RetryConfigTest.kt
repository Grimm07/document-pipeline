package org.example.pipeline.queue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RetryConfigTest : FunSpec({

    context("validation") {
        test("default config is valid") {
            val config = RetryConfig()
            config.maxRetries shouldBe 3
            config.baseDelayMs shouldBe 500L
            config.maxDelayMs shouldBe 10_000L
        }

        test("zero retries is valid") {
            val config = RetryConfig(maxRetries = 0)
            config.maxRetries shouldBe 0
        }

        test("negative maxRetries is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                RetryConfig(maxRetries = -1)
            }
            ex.message shouldContain "maxRetries"
        }

        test("zero baseDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                RetryConfig(baseDelayMs = 0)
            }
            ex.message shouldContain "baseDelayMs"
        }

        test("negative baseDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                RetryConfig(baseDelayMs = -100)
            }
            ex.message shouldContain "baseDelayMs"
        }

        test("zero maxDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                RetryConfig(maxDelayMs = 0)
            }
            ex.message shouldContain "maxDelayMs"
        }

        test("maxDelayMs less than baseDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                RetryConfig(baseDelayMs = 1000, maxDelayMs = 500)
            }
            ex.message shouldContain "maxDelayMs"
            ex.message shouldContain "baseDelayMs"
        }

        test("maxDelayMs equal to baseDelayMs is valid") {
            val config = RetryConfig(baseDelayMs = 1000, maxDelayMs = 1000)
            config.baseDelayMs shouldBe 1000L
            config.maxDelayMs shouldBe 1000L
        }

        test("custom valid config is accepted") {
            val config = RetryConfig(maxRetries = 5, baseDelayMs = 200, maxDelayMs = 30_000)
            config.maxRetries shouldBe 5
            config.baseDelayMs shouldBe 200L
            config.maxDelayMs shouldBe 30_000L
        }
    }
})
