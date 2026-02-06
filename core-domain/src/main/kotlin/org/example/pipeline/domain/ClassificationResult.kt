package org.example.pipeline.domain

import kotlinx.serialization.Serializable

/**
 * Result from the ML classification service.
 *
 * @property classification The predicted document classification/category
 * @property confidence Confidence score between 0.0 and 1.0
 */
@Serializable
data class ClassificationResult(
    val classification: String,
    val confidence: Float
) {
    init {
        require(confidence in 0.0f..1.0f) {
            "Confidence must be between 0.0 and 1.0, got: $confidence"
        }
    }
}
