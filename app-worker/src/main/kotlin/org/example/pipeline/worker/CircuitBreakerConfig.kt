package org.example.pipeline.worker

/**
 * Configuration for the classification service circuit breaker.
 *
 * @property failureThreshold Consecutive failures before opening the circuit
 * @property openDurationMs Time in milliseconds the circuit stays open before transitioning to half-open
 * @property halfOpenMaxAttempts Number of trial requests allowed in half-open state
 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    val openDurationMs: Long = DEFAULT_OPEN_DURATION_MS,
    val halfOpenMaxAttempts: Int = DEFAULT_HALF_OPEN_MAX_ATTEMPTS
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(openDurationMs > 0) { "openDurationMs must be positive" }
        require(halfOpenMaxAttempts > 0) { "halfOpenMaxAttempts must be positive" }
    }

    private companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 5
        const val DEFAULT_OPEN_DURATION_MS = 30_000L
        const val DEFAULT_HALF_OPEN_MAX_ATTEMPTS = 1
    }
}
