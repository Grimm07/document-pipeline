package org.example.pipeline.queue

/**
 * Configuration for retry behavior with exponential backoff.
 *
 * @property maxRetries Maximum number of retry attempts (0 = no retries, first call only)
 * @property baseDelayMs Base delay in milliseconds before first retry (doubled each attempt)
 * @property maxDelayMs Upper bound on delay between retries
 */
data class RetryConfig(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative, got $maxRetries" }
        require(baseDelayMs > 0) { "baseDelayMs must be positive, got $baseDelayMs" }
        require(maxDelayMs > 0) { "maxDelayMs must be positive, got $maxDelayMs" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)" }
    }

    private companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_BASE_DELAY_MS = 500L
        const val DEFAULT_MAX_DELAY_MS = 10_000L
    }
}
