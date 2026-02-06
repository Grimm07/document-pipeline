package org.example.pipeline.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import kotlinx.serialization.json.Json

/**
 * Exposed table definition for the documents table.
 *
 * Mirrors the schema defined in V1__create_documents_table.sql.
 * Uses PostgreSQL-specific types: UUID, JSONB, TIMESTAMPTZ.
 */
object DocumentsTable : Table("documents") {

    /** Primary key - auto-generated UUID */
    val id = uuid("id").autoGenerate()

    /** Relative path to the stored file */
    val storagePath = text("storage_path")

    /** Original filename as uploaded */
    val originalFilename = text("original_filename")

    /** MIME type (e.g., application/pdf, image/png) */
    val mimeType = text("mime_type")

    /** File size in bytes */
    val fileSizeBytes = long("file_size_bytes")

    /** Classification assigned by ML service */
    val classification = text("classification").default("unclassified")

    /** Confidence score from ML service (0.0 to 1.0) */
    val confidence = float("confidence").nullable()

    /** JSONB metadata column */
    val metadata = jsonb<Map<String, String>>("metadata", Json.Default)

    /** Optional user identifier */
    val uploadedBy = text("uploaded_by").nullable()

    /** Record creation timestamp */
    val createdAt = timestampWithTimeZone("created_at")

    /** Last update timestamp */
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
