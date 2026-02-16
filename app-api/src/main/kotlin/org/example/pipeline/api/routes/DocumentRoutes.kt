package org.example.pipeline.api.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import org.example.pipeline.api.dto.*
import org.example.pipeline.api.validation.validate
import org.example.pipeline.api.validation.validateCorrectClassification
import org.example.pipeline.api.validation.validateDocumentId
import org.example.pipeline.api.validation.validateListQueryParams
import org.example.pipeline.api.validation.validateSearchQueryParams
import org.example.pipeline.api.validation.validateUploadParams
import org.example.pipeline.domain.Document
import org.example.pipeline.domain.DocumentRepository
import org.example.pipeline.domain.FileStorageService
import org.example.pipeline.domain.QueuePublisher
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import kotlin.time.Clock

private val logger = LoggerFactory.getLogger("DocumentRoutes")

/** Default maximum upload file size: 50 MB. */
private const val DEFAULT_MAX_FILE_SIZE = 50L * 1024 * 1024

/** Default page size for document list queries. */
private const val DEFAULT_PAGE_SIZE = 50

/**
 * Registers document-related API routes under `/api/documents`.
 *
 * Provides endpoints for upload, list, search, detail, download, OCR results,
 * delete, and retry classification.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Route registration is inherently long and branchy
fun Application.documentRoutes() {
    val documentRepository by inject<DocumentRepository>()
    val fileStorageService by inject<FileStorageService>()
    val queuePublisher by inject<QueuePublisher>()
    val config by inject<HoconApplicationConfig>()

    val maxFileSize = config.propertyOrNull("upload.maxFileSizeBytes")
        ?.getString()?.toLongOrNull() ?: DEFAULT_MAX_FILE_SIZE

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

                val bytes = fileBytes
                if (bytes == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No file provided"))
                    return@post
                }

                val id = java.util.UUID.randomUUID().toString()
                val now = Clock.System.now()
                val actualFilename = filename ?: "unknown"
                val actualMimeType = mimeType ?: "application/octet-stream"
                UploadParams(actualFilename, actualMimeType).validate(validateUploadParams)

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
                queuePublisher.publish(saved.id, call.callId)

                call.respond(HttpStatusCode.OK, saved.toResponse())
            }

            get("/search") {
                val metadataParams = call.request.queryParameters
                    .entries()
                    .filter { it.key.startsWith("metadata.") }
                    .associate { it.key.removePrefix("metadata.") to it.value.first() }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?: DEFAULT_PAGE_SIZE

                val params = SearchQueryParams(metadataParams, limit)
                    .validate(validateSearchQueryParams)

                val documents = documentRepository.searchMetadata(
                    params.metadata, params.limit
                )
                val response = DocumentListResponse(
                    documents = documents.map { it.toResponse() },
                    total = documents.size,
                    limit = params.limit,
                    offset = 0
                )
                call.respond(HttpStatusCode.OK, response)
            }

            get("/{id}/ocr") {
                val idParam = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest, ErrorResponse("Missing document ID")
                    )
                idParam.validate(validateDocumentId)

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@get
                }

                val ocrPath = document.ocrStoragePath
                if (ocrPath == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("No OCR results for document: $idParam")
                    )
                    return@get
                }

                val bytes = fileStorageService.retrieve(ocrPath)
                if (bytes == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("OCR file not found for document: $idParam")
                    )
                    return@get
                }

                call.respondBytes(bytes, ContentType.Application.Json)
            }

            patch("/{id}/classification") {
                val idParam = call.parameters["id"]
                    ?: return@patch call.respond(
                        HttpStatusCode.BadRequest, ErrorResponse("Missing document ID")
                    )
                idParam.validate(validateDocumentId)

                val body = try {
                    call.receive<CorrectClassificationRequest>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid request body")
                    )
                    return@patch
                }

                body.validate(validateCorrectClassification)

                val existing = documentRepository.getById(idParam)
                if (existing == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@patch
                }

                documentRepository.correctClassification(idParam, body.classification)

                val updated = documentRepository.getById(idParam)
                if (updated == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@patch
                }
                call.respond(HttpStatusCode.OK, updated.toResponse())
            }

            @Suppress("TooGenericExceptionCaught") // File deletion must not fail the request
            delete("/{id}") {
                val idParam = call.parameters["id"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest, ErrorResponse("Missing document ID")
                    )
                idParam.validate(validateDocumentId)

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@delete
                }

                // Delete files first (prefer orphaned rows over orphaned files)
                try {
                    fileStorageService.delete(document.storagePath)
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to delete stored file for {}: {}",
                        idParam, e.message
                    )
                }
                val ocrPath = document.ocrStoragePath
                if (ocrPath != null) {
                    try {
                        fileStorageService.delete(ocrPath)
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to delete OCR file for {}: {}",
                            idParam, e.message
                        )
                    }
                }

                documentRepository.delete(idParam)
                call.respond(HttpStatusCode.NoContent)
            }

            @Suppress("TooGenericExceptionCaught") // File deletion must not fail the request
            post("/{id}/retry") {
                val idParam = call.parameters["id"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest, ErrorResponse("Missing document ID")
                    )
                idParam.validate(validateDocumentId)

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@post
                }

                // Clean up existing OCR file if present
                val ocrPath = document.ocrStoragePath
                if (ocrPath != null) {
                    try {
                        fileStorageService.delete(ocrPath)
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to delete OCR file during retry for {}: {}",
                            idParam, e.message
                        )
                    }
                }

                documentRepository.resetClassification(idParam)
                queuePublisher.publish(idParam, call.callId)

                val updated = documentRepository.getById(idParam)
                if (updated == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@post
                }
                call.respond(HttpStatusCode.OK, updated.toResponse())
            }

            get("/{id}") {
                val idParam = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest, ErrorResponse("Missing document ID")
                    )
                idParam.validate(validateDocumentId)

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@get
                }

                call.respond(HttpStatusCode.OK, document.toResponse())
            }

            get("/{id}/download") {
                val idParam = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest, ErrorResponse("Missing document ID")
                    )
                idParam.validate(validateDocumentId)

                val document = documentRepository.getById(idParam)
                if (document == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Document not found: $idParam")
                    )
                    return@get
                }

                val bytes = fileStorageService.retrieve(document.storagePath)
                if (bytes == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("File not found for document: $idParam")
                    )
                    return@get
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        document.originalFilename
                    ).toString()
                )
                call.respondBytes(bytes, ContentType.parse(document.mimeType))
            }

            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?: DEFAULT_PAGE_SIZE
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val classification = call.request.queryParameters["classification"]

                val params = ListQueryParams(limit, offset, classification)
                    .validate(validateListQueryParams)

                val documents = documentRepository.list(
                    params.classification, params.limit, params.offset
                )
                val response = DocumentListResponse(
                    documents = documents.map { it.toResponse() },
                    total = documents.size,
                    limit = params.limit,
                    offset = params.offset
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
