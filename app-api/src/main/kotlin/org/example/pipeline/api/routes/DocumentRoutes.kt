package org.example.pipeline.api.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import org.example.pipeline.api.dto.*
import org.example.pipeline.domain.Document
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.example.pipeline.domain.QueuePublisher
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import kotlin.time.Clock

private val logger = LoggerFactory.getLogger("DocumentRoutes")

/**
 * Registers document-related API routes.
 */
fun Application.documentRoutes() {
    val documentRepository by inject<DocumentRepository>()
    val fileStorageService by inject<FileStorageService>()
    val queuePublisher by inject<QueuePublisher>()
    val config by inject<HoconApplicationConfig>()

    val maxFileSize = config.propertyOrNull("upload.maxFileSizeBytes")
        ?.getString()?.toLongOrNull() ?: (50L * 1024 * 1024)

    routing {
        route("/api/documents") {

            post("/upload") {
                val multipart = call.receiveMultipart(formFieldLimit = maxFileSize)

                var filename: String? = null
                var mimeType: String? = null
                var fileBytes: ByteArray? = null
                val metadata = mutableMapOf<String, String>()

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            filename = part.originalFileName
                            mimeType = part.contentType?.toString()
                            fileBytes = part.provider().toByteArray()
                        }
                        is PartData.FormItem -> {
                            metadata[part.name ?: "unknown"] = part.value
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                if (fileBytes == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No file provided"))
                    return@post
                }

                val id = java.util.UUID.randomUUID().toString()
                val now = Clock.System.now()
                val actualFilename = filename ?: "unknown"
                val actualMimeType = mimeType ?: "application/octet-stream"
                val bytes = fileBytes!!

                val storagePath = fileStorageService.store(id, actualFilename, bytes)

                val document = Document(
                    id = id,
                    storagePath = storagePath,
                    originalFilename = actualFilename,
                    mimeType = actualMimeType,
                    fileSizeBytes = bytes.size.toLong(),
                    metadata = metadata,
                    createdAt = now,
                    updatedAt = now
                )

                val saved = documentRepository.insert(document)
                queuePublisher.publish(saved.id)

                call.respond(HttpStatusCode.OK, saved.toResponse())
            }

            get("/search") {
                val metadataParams = call.request.queryParameters
                    .entries()
                    .filter { it.key.startsWith("metadata.") }
                    .associate { it.key.removePrefix("metadata.") to it.value.first() }

                if (metadataParams.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one metadata.* query parameter is required"))
                    return@get
                }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val documents = documentRepository.searchMetadata(metadataParams, limit)
                val response = DocumentListResponse(
                    documents = documents.map { it.toResponse() },
                    total = documents.size,
                    limit = limit,
                    offset = 0
                )
                call.respond(HttpStatusCode.OK, response)
            }

            get("/{id}/ocr") {
                val idParam = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing document ID"))

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Document not found: $idParam"))
                    return@get
                }

                val ocrPath = document.ocrStoragePath
                if (ocrPath == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No OCR results for document: $idParam"))
                    return@get
                }

                val bytes = fileStorageService.retrieve(ocrPath)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("OCR file not found for document: $idParam"))
                    return@get
                }

                call.respondBytes(bytes, ContentType.Application.Json)
            }

            delete("/{id}") {
                val idParam = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing document ID"))

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Document not found: $idParam"))
                    return@delete
                }

                // Delete files first (prefer orphaned rows over orphaned files)
                try {
                    fileStorageService.delete(document.storagePath)
                } catch (e: Exception) {
                    logger.warn("Failed to delete stored file for {}: {}", idParam, e.message)
                }
                val ocrPath = document.ocrStoragePath
                if (ocrPath != null) {
                    try {
                        fileStorageService.delete(ocrPath)
                    } catch (e: Exception) {
                        logger.warn("Failed to delete OCR file for {}: {}", idParam, e.message)
                    }
                }

                documentRepository.delete(idParam)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/retry") {
                val idParam = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing document ID"))

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Document not found: $idParam"))
                    return@post
                }

                // Clean up existing OCR file if present
                val ocrPath = document.ocrStoragePath
                if (ocrPath != null) {
                    try {
                        fileStorageService.delete(ocrPath)
                    } catch (e: Exception) {
                        logger.warn("Failed to delete OCR file during retry for {}: {}", idParam, e.message)
                    }
                }

                documentRepository.resetClassification(idParam)
                queuePublisher.publish(idParam)

                val updated = documentRepository.getById(idParam)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Document not found: $idParam"))
                    return@post
                }
                call.respond(HttpStatusCode.OK, updated.toResponse())
            }

            get("/{id}") {
                val idParam = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing document ID"))

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Document not found: $idParam"))
                    return@get
                }

                call.respond(HttpStatusCode.OK, document.toResponse())
            }

            get("/{id}/download") {
                val idParam = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing document ID"))

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Document not found: $idParam"))
                    return@get
                }

                val bytes = fileStorageService.retrieve(document.storagePath)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("File not found for document: $idParam"))
                    return@get
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, document.originalFilename
                    ).toString()
                )
                call.respondBytes(bytes, ContentType.parse(document.mimeType))
            }

            get {
                val classification = call.request.queryParameters["classification"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val documents = documentRepository.list(classification, limit, offset)
                val response = DocumentListResponse(
                    documents = documents.map { it.toResponse() },
                    total = documents.size,
                    limit = limit,
                    offset = offset
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
