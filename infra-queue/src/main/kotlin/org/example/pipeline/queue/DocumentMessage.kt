package org.example.pipeline.queue

import kotlinx.serialization.Serializable

/**
 * Message payload for document processing queue.
 *
 * @property documentId The UUID of the document to process (as string for serialization)
 * @property action The processing action to perform
 */
@Serializable
data class DocumentMessage(
    val documentId: String,
    val action: String = "classify"
)
