package org.example.pipeline.worker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.example.pipeline.domain.ClassificationResult
import org.example.pipeline.domain.ClassificationService
import java.io.IOException

/** Tests for the circuit breaker classification service decorator. */
class CircuitBreakerClassificationServiceTest : FunSpec({

    val testContent = "test".toByteArray()
    val testMimeType = "text/plain"
    val successResult = ClassificationResult("invoice", 0.95f)

    test("passes through when circuit is closed") {
        val delegate = mockk<ClassificationService>()
        coEvery { delegate.classify(any(), any()) } returns successResult
        val cb = CircuitBreakerClassificationService(delegate, CircuitBreakerConfig(failureThreshold = 3))

        val result = cb.classify(testContent, testMimeType)

        result shouldBe successResult
        coVerify(exactly = 1) { delegate.classify(testContent, testMimeType) }
    }

    test("opens circuit after failureThreshold consecutive failures") {
        val delegate = mockk<ClassificationService>()
        coEvery { delegate.classify(any(), any()) } throws IOException("Connection refused")
        val cb = CircuitBreakerClassificationService(delegate, CircuitBreakerConfig(failureThreshold = 3))

        // Trigger 3 failures to open the circuit
        repeat(3) {
            shouldThrow<IOException> { cb.classify(testContent, testMimeType) }
        }

        // 4th call should fail fast without calling delegate
        shouldThrow<CircuitBreakerOpenException> { cb.classify(testContent, testMimeType) }
        coVerify(exactly = 3) { delegate.classify(any(), any()) }
    }

    test("fail-fast with CircuitBreakerOpenException when open") {
        val delegate = mockk<ClassificationService>()
        coEvery { delegate.classify(any(), any()) } throws IOException("Connection refused")
        val cb = CircuitBreakerClassificationService(delegate, CircuitBreakerConfig(failureThreshold = 1))

        // Open the circuit
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }

        // Subsequent calls fail fast
        val ex = shouldThrow<CircuitBreakerOpenException> { cb.classify(testContent, testMimeType) }
        ex.shouldBeInstanceOf<CircuitBreakerOpenException>()
        coVerify(exactly = 1) { delegate.classify(any(), any()) }
    }

    test("transitions from OPEN to HALF_OPEN after openDurationMs") {
        val delegate = mockk<ClassificationService>()
        var currentTime = 1000L
        coEvery { delegate.classify(any(), any()) } throws IOException("fail") andThen successResult

        val cb = CircuitBreakerClassificationService(
            delegate,
            CircuitBreakerConfig(failureThreshold = 1, openDurationMs = 500),
            clock = { currentTime }
        )

        // Open the circuit
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }
        shouldThrow<CircuitBreakerOpenException> { cb.classify(testContent, testMimeType) }

        // Advance time past open duration
        currentTime = 1600L

        // Should now pass through (half-open state)
        val result = cb.classify(testContent, testMimeType)
        result shouldBe successResult
    }

    test("resets to CLOSED on successful half-open attempt") {
        val delegate = mockk<ClassificationService>()
        var currentTime = 1000L
        var callCount = 0
        coEvery { delegate.classify(any(), any()) } answers {
            callCount++
            if (callCount == 1) throw IOException("fail")
            successResult
        }

        val cb = CircuitBreakerClassificationService(
            delegate,
            CircuitBreakerConfig(failureThreshold = 1, openDurationMs = 500),
            clock = { currentTime }
        )

        // Open the circuit
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }

        // Advance time to trigger half-open
        currentTime = 1600L

        // Successful half-open request → should reset to closed
        cb.classify(testContent, testMimeType) shouldBe successResult

        // Further calls should pass through normally (circuit is closed)
        cb.classify(testContent, testMimeType) shouldBe successResult
        coVerify(exactly = 3) { delegate.classify(any(), any()) }
    }

    test("returns to OPEN on failed half-open attempt") {
        val delegate = mockk<ClassificationService>()
        var currentTime = 1000L
        coEvery { delegate.classify(any(), any()) } throws IOException("still failing")

        val cb = CircuitBreakerClassificationService(
            delegate,
            CircuitBreakerConfig(failureThreshold = 1, openDurationMs = 500),
            clock = { currentTime }
        )

        // Open the circuit
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }

        // Advance time to trigger half-open
        currentTime = 1600L

        // Half-open probe fails → back to OPEN
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }

        // Should fail fast again (circuit re-opened)
        shouldThrow<CircuitBreakerOpenException> { cb.classify(testContent, testMimeType) }
        coVerify(exactly = 2) { delegate.classify(any(), any()) }
    }

    test("success resets failure count in closed state") {
        val delegate = mockk<ClassificationService>()
        var callCount = 0
        coEvery { delegate.classify(any(), any()) } answers {
            callCount++
            if (callCount <= 2) throw IOException("fail")
            successResult
        }

        val cb = CircuitBreakerClassificationService(delegate, CircuitBreakerConfig(failureThreshold = 3))

        // 2 failures
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }

        // 1 success — resets failure count
        cb.classify(testContent, testMimeType) shouldBe successResult

        // Need 3 more consecutive failures to open (not just 1 more)
        coEvery { delegate.classify(any(), any()) } throws IOException("fail again")
        repeat(2) {
            shouldThrow<IOException> { cb.classify(testContent, testMimeType) }
        }
        // Still closed — only 2 failures since reset
        shouldThrow<IOException> { cb.classify(testContent, testMimeType) }

        // NOW it should be open (3 consecutive failures)
        shouldThrow<CircuitBreakerOpenException> { cb.classify(testContent, testMimeType) }
    }

    context("CircuitBreakerConfig validation") {
        test("rejects zero failureThreshold") {
            shouldThrow<IllegalArgumentException> {
                CircuitBreakerConfig(failureThreshold = 0)
            }
        }

        test("rejects negative openDurationMs") {
            shouldThrow<IllegalArgumentException> {
                CircuitBreakerConfig(openDurationMs = -1)
            }
        }

        test("rejects zero halfOpenMaxAttempts") {
            shouldThrow<IllegalArgumentException> {
                CircuitBreakerConfig(halfOpenMaxAttempts = 0)
            }
        }

        test("accepts valid config") {
            val config = CircuitBreakerConfig(failureThreshold = 10, openDurationMs = 60_000, halfOpenMaxAttempts = 2)
            config.failureThreshold shouldBe 10
            config.openDurationMs shouldBe 60_000L
            config.halfOpenMaxAttempts shouldBe 2
        }
    }
})
