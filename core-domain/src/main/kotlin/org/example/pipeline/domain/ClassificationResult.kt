package org.example.pipeline.domain

import kotlinx.serialization.Serializable

/**
 * Result from the ML classification service.
 *
 * @property classification The predicted document classification/category
 * @property confidence Confidence score between 0.0 and 1.0
 * @property labelScores All candidate label scores from classification, null if unavailable
 * @property ocrResultJson Raw OCR result JSON from the ML service, null if OCR was not performed
 */
@Serializable
data class ClassificationResult(
    val classification: String,
    val confidence: Float,
    val labelScores: Map<String, Float>? = null,
    val ocrResultJson: String? = null
) {
    init {
        require(confidence in 0.0f..1.0f) {
            "Confidence must be between 0.0 and 1.0, got: $confidence"
        }
    }
}
