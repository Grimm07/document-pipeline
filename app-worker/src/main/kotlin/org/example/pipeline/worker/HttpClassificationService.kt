package org.example.pipeline.worker

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.pipeline.domain.ClassificationResult
import org.example.pipeline.domain.ClassificationService
import org.slf4j.LoggerFactory

/**
 * HTTP-based implementation of [ClassificationService].
 *
 * Calls an external ML service (e.g., Python/FastAPI) to classify documents.
 *
 * @property baseUrl Base URL of the ML classification service
 */
class HttpClassificationService(
    private val baseUrl: String
) : ClassificationService {

    private val logger = LoggerFactory.getLogger(HttpClassificationService::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }

        engine {
            requestTimeout = 30_000 // 30 seconds for ML inference
        }
    }

    override suspend fun classify(content: ByteArray, mimeType: String): ClassificationResult {
        TODO("""
            Implement ML service call:
            1. Create request payload (base64 encoded content + mimeType, or multipart)
            2. POST to $baseUrl/classify
            3. Parse response into ClassificationResult
            4. Handle errors (timeout, service unavailable, etc.)
            5. Log classification result
        """)
    }

    /**
     * Request payload for the ML service.
     */
    @Serializable
    private data class ClassifyRequest(
        val content: String, // Base64 encoded
        val mimeType: String
    )

    /**
     * Response from the ML service.
     */
    @Serializable
    private data class ClassifyResponse(
        val classification: String,
        val confidence: Float
    )

    /**
     * Closes the HTTP client.
     */
    fun close() {
        client.close()
    }
}
