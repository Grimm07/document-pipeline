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
import kotlinx.serialization.json.JsonElement
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
    private val baseUrl: String,
    private val requestTimeoutMs: Long = 300_000
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
            requestTimeout = requestTimeoutMs
        }
    }

    override suspend fun classify(content: ByteArray, mimeType: String): ClassificationResult {
        val base64Content = java.util.Base64.getEncoder().encodeToString(content)
        val request = ClassifyRequest(content = base64Content, mimeType = mimeType)

        val response = client.post("$baseUrl/classify-with-ocr") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            throw RuntimeException("Classification service returned ${response.status}")
        }

        val classifyResponse = response.body<ClassifyWithOcrResponse>()
        logger.info("Classification result: {} (confidence: {})", classifyResponse.classification, classifyResponse.confidence)

        val ocrJson = classifyResponse.ocr?.let { Json.encodeToString(it) }

        return ClassificationResult(
            classification = classifyResponse.classification,
            confidence = classifyResponse.confidence,
            ocrResultJson = ocrJson
        )
    }

    @Serializable
    private data class ClassifyRequest(
        val content: String,
        val mimeType: String
    )

    @Serializable
    private data class ClassifyWithOcrResponse(
        val classification: String,
        val confidence: Float,
        val ocr: JsonElement? = null
    )

    /**
     * Closes the HTTP client.
     */
    fun close() {
        client.close()
    }
}
