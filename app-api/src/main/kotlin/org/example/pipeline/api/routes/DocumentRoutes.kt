package org.example.pipeline.api.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.pipeline.api.dto.*
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.example.pipeline.domain.QueuePublisher
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DocumentRoutes")

/**
 * Registers document-related API routes.
 */
fun Application.documentRoutes() {
    val documentRepository by inject<DocumentRepository>()
    val fileStorageService by inject<FileStorageService>()
    val queuePublisher by inject<QueuePublisher>()

    routing {
        route("/api/documents") {

            /**
             * POST /api/documents/upload
             *
             * Handles multipart file upload.
             * Stores the file, creates a database record, and publishes a message for async processing.
             */
            post("/upload") {
                TODO("Implement: Receive multipart data, extract file and metadata, store file, insert DB record, publish to queue, return DocumentResponse")
            }

            /**
             * GET /api/documents/{id}
             *
             * Retrieves a document by ID.
             */
            get("/{id}") {
                val idParam = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing document ID"))

                TODO("Implement: Parse UUID, fetch from repository, return 404 if not found, else return DocumentResponse")
            }

            /**
             * GET /api/documents
             *
             * Lists documents with optional filtering.
             * Query params: classification, limit, offset
             */
            get {
                val classification = call.request.queryParameters["classification"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                TODO("Implement: Call repository.list with filters, map to DocumentResponse list, return")
            }
        }
    }
}
