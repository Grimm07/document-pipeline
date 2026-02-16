@file:Suppress("MatchingDeclarationName")

package org.example.pipeline.api.validation

import io.konform.validation.Invalid
import io.konform.validation.Validation

/**
 * Exception thrown when Konform validation fails.
 *
 * Carries structured per-field error messages for serialization
 * into a [org.example.pipeline.api.dto.ValidationErrorResponse].
 *
 * @property fieldErrors Map of field path (e.g. ".name") to error messages
 */
class ValidationException(
    val fieldErrors: Map<String, List<String>>
) : RuntimeException("Validation failed")

/**
 * Validates this value against the given [Konform validation][validation].
 *
 * @return the original value if validation passes
 * @throws ValidationException if validation fails, with structured field errors
 */
fun <T> T.validate(validation: Validation<T>): T {
    val result = validation(this)
    if (result is Invalid) {
        val fieldErrors = result.errors
            .groupBy({ it.dataPath }, { it.message })
        throw ValidationException(fieldErrors)
    }
    return this
}
