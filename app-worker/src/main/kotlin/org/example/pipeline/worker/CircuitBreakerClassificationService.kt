package org.example.pipeline.worker

import org.example.pipeline.domain.ClassificationResult
import org.example.pipeline.domain.ClassificationService
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit breaker states for the classification service.
 *
 * Tracks failure counts, open timestamps, and half-open attempt counts.
 */
private sealed interface CircuitState {
    /** Normal operation — requests pass through to the delegate. */
    data class Closed(val failureCount: Int = 0) : CircuitState

    /** Circuit is open — requests fail fast without calling the delegate. */
    data class Open(val openedAt: Long) : CircuitState

    /** Probing state — a limited number of requests are allowed through. */
    data class HalfOpen(val attemptCount: Int = 0) : CircuitState
}

/**
 * Decorator that wraps a [ClassificationService] with circuit-breaker protection.
 *
 * State transitions:
 * - **CLOSED**: Requests pass through. After [CircuitBreakerConfig.failureThreshold]
 *   consecutive failures, transitions to OPEN.
 * - **OPEN**: Requests fail-fast with [CircuitBreakerOpenException].
 *   After [CircuitBreakerConfig.openDurationMs], transitions to HALF_OPEN.
 * - **HALF_OPEN**: Allows [CircuitBreakerConfig.halfOpenMaxAttempts] trial requests.
 *   Success → CLOSED, Failure → OPEN.
 *
 * @param delegate The underlying classification service to protect
 * @param config Circuit breaker configuration
 * @param clock Time source for testability (defaults to system clock)
 */
class CircuitBreakerClassificationService(
    private val delegate: ClassificationService,
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val clock: () -> Long = System::currentTimeMillis
) : ClassificationService {

    private val logger = LoggerFactory.getLogger(CircuitBreakerClassificationService::class.java)
    private val state = AtomicReference<CircuitState>(CircuitState.Closed())

    override suspend fun classify(content: ByteArray, mimeType: String): ClassificationResult {
        checkAndTransition()

        val currentState = state.get()
        if (currentState is CircuitState.Open) {
            throw CircuitBreakerOpenException(
                "Circuit breaker is OPEN — ML service unavailable. Will retry after ${config.openDurationMs}ms."
            )
        }

        return try {
            val result = delegate.classify(content, mimeType)
            onSuccess()
            result
        } catch (e: CircuitBreakerOpenException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            onFailure()
            throw e
        }
    }

    /**
     * Transitions OPEN → HALF_OPEN if the open duration has elapsed.
     */
    private fun checkAndTransition() {
        val current = state.get()
        if (current is CircuitState.Open) {
            val elapsed = clock() - current.openedAt
            if (elapsed >= config.openDurationMs) {
                if (state.compareAndSet(current, CircuitState.HalfOpen())) {
                    logger.info("Circuit breaker transitioning from OPEN to HALF_OPEN after {}ms", elapsed)
                }
            }
        }
    }

    /**
     * Handles a successful request — resets to CLOSED state.
     */
    private fun onSuccess() {
        val previous = state.getAndSet(CircuitState.Closed())
        if (previous !is CircuitState.Closed || previous.failureCount > 0) {
            logger.info("Circuit breaker reset to CLOSED after successful request")
        }
    }

    /**
     * Handles a failed request — increments failure count and potentially opens the circuit.
     */
    private fun onFailure() {
        while (true) {
            when (val current = state.get()) {
                is CircuitState.Closed -> if (handleClosedFailure(current)) return
                is CircuitState.HalfOpen -> {
                    if (state.compareAndSet(current, CircuitState.Open(clock()))) {
                        logger.warn("Circuit breaker returned to OPEN after half-open failure")
                    }
                    return
                }
                is CircuitState.Open -> return
            }
        }
    }

    /**
     * Processes a failure in CLOSED state. Returns true if the CAS succeeded and the caller should return.
     */
    private fun handleClosedFailure(current: CircuitState.Closed): Boolean {
        val newCount = current.failureCount + 1
        return if (newCount >= config.failureThreshold) {
            state.compareAndSet(current, CircuitState.Open(clock())).also { swapped ->
                if (swapped) logger.warn("Circuit breaker OPENED after {} consecutive failures", newCount)
            }
        } else {
            state.compareAndSet(current, CircuitState.Closed(newCount)).also { swapped ->
                if (swapped) logger.debug("Circuit breaker failure count: {}/{}", newCount, config.failureThreshold)
            }
        }
    }
}

/**
 * Thrown when the circuit breaker is open and requests are being rejected.
 *
 * Consumer should nack the message so it dead-letters, giving the ML service
 * time to recover before the DLQ reprocessor retries.
 */
class CircuitBreakerOpenException(message: String) : RuntimeException(message)
