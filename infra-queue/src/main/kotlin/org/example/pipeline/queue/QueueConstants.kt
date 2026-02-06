package org.example.pipeline.queue

/**
 * Constants for RabbitMQ queue and exchange configuration.
 */
object QueueConstants {

    /** Exchange for document processing events */
    const val DOCUMENT_EXCHANGE = "document.exchange"

    /** Queue for document classification jobs */
    const val DOCUMENT_CLASSIFICATION_QUEUE = "document.classification.queue"

    /** Routing key for classification messages */
    const val CLASSIFICATION_ROUTING_KEY = "document.classify"

    /** Dead letter exchange for failed messages */
    const val DLX_EXCHANGE = "document.dlx.exchange"

    /** Dead letter queue for failed messages */
    const val DLX_QUEUE = "document.dlx.queue"

    /** Content type for JSON messages */
    const val CONTENT_TYPE_JSON = "application/json"
}
