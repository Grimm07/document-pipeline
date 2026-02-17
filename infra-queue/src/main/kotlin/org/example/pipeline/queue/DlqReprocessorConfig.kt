package org.example.pipeline.queue

/**
 * Configuration for the dead letter queue reprocessor.
 *
 * @property maxRetryCycles Maximum times a message can cycle through the DLQ before parking
 * @property baseDelayMs Base delay in milliseconds before republishing (doubled each cycle)
 * @property maxDelayMs Upper bound on delay between reprocessing attempts
 * @property enabled Whether the DLQ reprocessor should start
 */
data class DlqReprocessorConfig(
    val maxRetryCycles: Int = DEFAULT_MAX_RETRY_CYCLES,
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val enabled: Boolean = true
) {
    init {
        require(maxRetryCycles >= 0) { "maxRetryCycles must be non-negative, got $maxRetryCycles" }
        require(baseDelayMs > 0) { "baseDelayMs must be positive, got $baseDelayMs" }
        require(maxDelayMs > 0) { "maxDelayMs must be positive, got $maxDelayMs" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)" }
    }

    private companion object {
        const val DEFAULT_MAX_RETRY_CYCLES = 3
        const val DEFAULT_BASE_DELAY_MS = 5_000L
        const val DEFAULT_MAX_DELAY_MS = 60_000L
    }
}
