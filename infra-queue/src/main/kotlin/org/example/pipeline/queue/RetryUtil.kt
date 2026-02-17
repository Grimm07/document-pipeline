package org.example.pipeline.queue

import kotlinx.coroutines.delay
import org.slf4j.Logger
import kotlin.math.min
import kotlin.math.pow

/**
 * Executes [block] with exponential-backoff retries on failure.
 *
 * On each failed attempt that passes the [retryOn] predicate, delays by
 * `min(baseDelayMs * 2^attempt, maxDelayMs)` before retrying. If [retryOn]
 * returns false or all retries are exhausted, the last exception is rethrown.
 */
@Suppress("TooGenericExceptionCaught") // Must catch all to implement generic retry
internal suspend fun <T> withRetry(
    config: RetryConfig,
    logger: Logger,
    operationName: String,
    retryOn: (Exception) -> Boolean = { true },
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    for (attempt in 0..config.maxRetries) {
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            val isLastAttempt = attempt == config.maxRetries
            if (isLastAttempt || !retryOn(e)) {
                throw e
            }
            val delayMs = calculateDelay(config.baseDelayMs, config.maxDelayMs, attempt)
            logger.warn(
                "{} failed (attempt {}/{}), retrying in {}ms: {}",
                operationName, attempt + 1, config.maxRetries + 1, delayMs, e.message
            )
            delay(delayMs)
        }
    }
    // Unreachable, but satisfies compiler
    error("withRetry exhausted without exception")
}

/**
 * Calculates exponential backoff delay, capped at [maxDelayMs].
 *
 * Formula: `min(baseDelayMs * 2^attempt, maxDelayMs)`
 */
internal fun calculateDelay(baseDelayMs: Long, maxDelayMs: Long, attempt: Int): Long {
    val exponentialDelay = baseDelayMs * 2.0.pow(attempt).toLong()
    return min(exponentialDelay, maxDelayMs)
}
