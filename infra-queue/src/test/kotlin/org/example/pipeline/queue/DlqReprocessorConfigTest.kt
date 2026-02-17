package org.example.pipeline.queue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DlqReprocessorConfigTest : FunSpec({

    context("validation") {
        test("default config is valid") {
            val config = DlqReprocessorConfig()
            config.maxRetryCycles shouldBe 3
            config.baseDelayMs shouldBe 5_000L
            config.maxDelayMs shouldBe 60_000L
            config.enabled shouldBe true
        }

        test("zero retry cycles is valid (park immediately)") {
            val config = DlqReprocessorConfig(maxRetryCycles = 0)
            config.maxRetryCycles shouldBe 0
        }

        test("disabled config is valid") {
            val config = DlqReprocessorConfig(enabled = false)
            config.enabled shouldBe false
        }

        test("negative maxRetryCycles is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                DlqReprocessorConfig(maxRetryCycles = -1)
            }
            ex.message shouldContain "maxRetryCycles"
        }

        test("zero baseDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                DlqReprocessorConfig(baseDelayMs = 0)
            }
            ex.message shouldContain "baseDelayMs"
        }

        test("negative baseDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                DlqReprocessorConfig(baseDelayMs = -100)
            }
            ex.message shouldContain "baseDelayMs"
        }

        test("zero maxDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                DlqReprocessorConfig(maxDelayMs = 0)
            }
            ex.message shouldContain "maxDelayMs"
        }

        test("maxDelayMs less than baseDelayMs is rejected") {
            val ex = shouldThrow<IllegalArgumentException> {
                DlqReprocessorConfig(baseDelayMs = 10_000, maxDelayMs = 5_000)
            }
            ex.message shouldContain "maxDelayMs"
            ex.message shouldContain "baseDelayMs"
        }

        test("maxDelayMs equal to baseDelayMs is valid") {
            val config = DlqReprocessorConfig(baseDelayMs = 5_000, maxDelayMs = 5_000)
            config.baseDelayMs shouldBe 5_000L
            config.maxDelayMs shouldBe 5_000L
        }

        test("custom valid config is accepted") {
            val config = DlqReprocessorConfig(
                maxRetryCycles = 5,
                baseDelayMs = 2_000,
                maxDelayMs = 120_000,
                enabled = true
            )
            config.maxRetryCycles shouldBe 5
            config.baseDelayMs shouldBe 2_000L
            config.maxDelayMs shouldBe 120_000L
        }
    }
})
