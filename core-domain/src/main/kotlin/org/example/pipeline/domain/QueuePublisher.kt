package org.example.pipeline.domain

/**
 * Interface for publishing messages to a message queue.
 *
 * Implementations handle the specifics of the messaging system (RabbitMQ, Kafka, etc.).
 */
interface QueuePublisher {

    /**
     * Publishes a document processing message to the queue.
     *
     * The message signals that a document is ready for async processing
     * (e.g., classification by the ML service).
     *
     * @param documentId The UUID (as string) of the document to process
     * @param correlationId Optional request correlation ID for distributed tracing
     */
    suspend fun publish(documentId: String, correlationId: String? = null)
}
