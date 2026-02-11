package org.example.pipeline.db

import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.pipeline.domain.Document
import org.example.pipeline.domain.DocumentRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Clock

/**
 * Exposed-based implementation of [DocumentRepository].
 *
 * Uses PostgreSQL with Exposed DSL for database operations.
 * All operations are wrapped in suspended transactions for coroutine support.
 */
class ExposedDocumentRepository : DocumentRepository {

    private val logger = LoggerFactory.getLogger(ExposedDocumentRepository::class.java)

    override suspend fun insert(document: Document): Document = newSuspendedTransaction {
        logger.debug("Inserting document id={}", document.id)
        DocumentsTable.insert { row ->
            row[id] = UUID.fromString(document.id)
            row[storagePath] = document.storagePath
            row[originalFilename] = document.originalFilename
            row[mimeType] = document.mimeType
            row[fileSizeBytes] = document.fileSizeBytes
            row[classification] = document.classification
            row[confidence] = document.confidence
            row[metadata] = document.metadata
            row[uploadedBy] = document.uploadedBy
            row[ocrStoragePath] = document.ocrStoragePath
            row[createdAt] = OffsetDateTime.ofInstant(document.createdAt.toJavaInstant(), ZoneOffset.UTC)
            row[updatedAt] = OffsetDateTime.ofInstant(document.updatedAt.toJavaInstant(), ZoneOffset.UTC)
        }
        document
    }

    override suspend fun getById(id: String): Document? = newSuspendedTransaction {
        val uuid = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            logger.debug("Malformed UUID: {}", id)
            return@newSuspendedTransaction null
        }
        DocumentsTable.selectAll()
            .where { DocumentsTable.id eq uuid }
            .singleOrNull()
            ?.toDocument()
    }

    override suspend fun list(
        classification: String?,
        limit: Int,
        offset: Int
    ): List<Document> = newSuspendedTransaction {
        val query = DocumentsTable.selectAll()
        if (classification != null) {
            query.where { DocumentsTable.classification eq classification }
        }
        query
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toDocument() }
    }

    override suspend fun searchMetadata(
        metadataQuery: Map<String, String>,
        limit: Int
    ): List<Document> = newSuspendedTransaction {
        if (metadataQuery.isEmpty()) {
            DocumentsTable.selectAll()
                .limit(limit)
                .map { it.toDocument() }
        } else {
            val conditions: List<Op<Boolean>> = metadataQuery.entries.map { entry ->
                val key = entry.key
                val value = entry.value
                val jsonStr = buildJsonObject { put(key, value) }.toString()
                object : Op<Boolean>() {
                    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                        queryBuilder {
                            append(DocumentsTable.metadata)
                            append(" @> ")
                            append(stringParam(jsonStr))
                            append("::jsonb")
                        }
                    }
                }
            }
            val combinedCondition: Op<Boolean> = conditions.reduce { acc: Op<Boolean>, op: Op<Boolean> -> acc and op }
            DocumentsTable.selectAll()
                .where { combinedCondition }
                .limit(limit)
                .map { it.toDocument() }
        }
    }

    override suspend fun updateClassification(
        id: String,
        classification: String,
        confidence: Float,
        ocrStoragePath: String?
    ): Boolean = newSuspendedTransaction {
        val uuid = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            logger.debug("Malformed UUID for update: {}", id)
            return@newSuspendedTransaction false
        }
        val now = OffsetDateTime.ofInstant(Clock.System.now().toJavaInstant(), ZoneOffset.UTC)
        val updatedCount = DocumentsTable.update({ DocumentsTable.id eq uuid }) {
            it[DocumentsTable.classification] = classification
            it[DocumentsTable.confidence] = confidence
            it[updatedAt] = now
            if (ocrStoragePath != null) {
                it[DocumentsTable.ocrStoragePath] = ocrStoragePath
            }
        }
        updatedCount > 0
    }

    override suspend fun delete(id: String): Boolean = newSuspendedTransaction {
        val uuid = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            logger.debug("Malformed UUID for delete: {}", id)
            return@newSuspendedTransaction false
        }
        val deletedCount = DocumentsTable.deleteWhere { DocumentsTable.id eq uuid }
        deletedCount > 0
    }

    override suspend fun resetClassification(id: String): Boolean = newSuspendedTransaction {
        val uuid = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            logger.debug("Malformed UUID for resetClassification: {}", id)
            return@newSuspendedTransaction false
        }
        val now = OffsetDateTime.ofInstant(Clock.System.now().toJavaInstant(), ZoneOffset.UTC)
        val updatedCount = DocumentsTable.update({ DocumentsTable.id eq uuid }) {
            it[classification] = "unclassified"
            it[confidence] = null
            it[ocrStoragePath] = null
            it[updatedAt] = now
        }
        updatedCount > 0
    }

    /**
     * Maps an Exposed ResultRow to a Document domain object.
     */
    private fun ResultRow.toDocument(): Document {
        return Document(
            id = this[DocumentsTable.id].toString(),
            storagePath = this[DocumentsTable.storagePath],
            originalFilename = this[DocumentsTable.originalFilename],
            mimeType = this[DocumentsTable.mimeType],
            fileSizeBytes = this[DocumentsTable.fileSizeBytes],
            classification = this[DocumentsTable.classification],
            confidence = this[DocumentsTable.confidence],
            metadata = this[DocumentsTable.metadata],
            uploadedBy = this[DocumentsTable.uploadedBy],
            ocrStoragePath = this[DocumentsTable.ocrStoragePath],
            createdAt = this[DocumentsTable.createdAt].toInstant().toKotlinInstant(),
            updatedAt = this[DocumentsTable.updatedAt].toInstant().toKotlinInstant()
        )
    }
}
