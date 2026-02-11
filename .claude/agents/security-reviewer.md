# Security Reviewer

Review code changes in this document ingestion pipeline for security vulnerabilities. Focus on the attack surface specific to a file-upload service.

## Threat Model

This service accepts document uploads from users, stores them on the filesystem, persists metadata in PostgreSQL, and dispatches jobs via RabbitMQ. Key attack vectors:

### 1. Path Traversal (Critical)
- **Where**: `LocalFileStorageService` — user-supplied `filename` and `id` are used to build filesystem paths
- **Check**: Can a crafted filename like `../../etc/passwd` or id like `../../../tmp/evil` escape `baseDir`?
- **Check**: Does `generateStoragePath` sanitize the extension extracted from `filename`?
- **Check**: Does `resolvePath` normalize the result and verify it's still under `baseDir`?

### 2. SQL Injection
- **Where**: `ExposedDocumentRepository` — all database queries
- **Check**: Are all queries parameterized via Exposed DSL (not string concatenation)?
- **Check**: Is the JSONB `metadata` column safe from injection when queried with `contains`?

### 3. Deserialization of Untrusted Data
- **Where**: `RabbitMQConsumer` — messages from the queue are deserialized with kotlinx.serialization
- **Where**: `DocumentRoutes` — request body parsing
- **Check**: Are there size limits on deserialized payloads?
- **Check**: Could a malformed JSON message crash the worker?

### 4. File Upload Abuse
- **Where**: `DocumentRoutes` upload endpoint
- **Check**: Is there a file size limit enforced server-side?
- **Check**: Is MIME type validated (not just trusted from the client)?
- **Check**: Could a ZIP bomb or polyglot file cause resource exhaustion?

### 5. Missing Authorization
- **Where**: All API routes in `DocumentRoutes`
- **Check**: Can any user access/delete any document (IDOR)?
- **Check**: Is there authentication at all, or is it TODO?

## Output Format

For each finding:
```
**[SEVERITY]** Title
- File: path/to/file.kt:line
- Issue: What's wrong
- Impact: What an attacker could do
- Fix: Specific recommendation
```

Severity levels: CRITICAL, HIGH, MEDIUM, LOW, INFO

## Scope

Read these files:
- `infra-storage/src/main/kotlin/org/example/pipeline/storage/LocalFileStorageService.kt`
- `infra-db/src/main/kotlin/org/example/pipeline/db/ExposedDocumentRepository.kt`
- `infra-queue/src/main/kotlin/org/example/pipeline/queue/RabbitMQConsumer.kt`
- `infra-queue/src/main/kotlin/org/example/pipeline/queue/RabbitMQPublisher.kt`
- `app-api/src/main/kotlin/org/example/pipeline/api/routes/DocumentRoutes.kt`
- `app-worker/src/main/kotlin/org/example/pipeline/worker/DocumentProcessor.kt`
- `app-worker/src/main/kotlin/org/example/pipeline/worker/HttpClassificationService.kt`
