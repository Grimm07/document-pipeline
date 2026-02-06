# Document Pipeline

A generalized document ingestion service designed to exemplify large-scale, modular document ingestion pipeline processes. This project demonstrates best practices for building production-grade ingestion systems that handle diverse document types (PDFs, images, text files) with high throughput and reliability.

> **Note**: This is a scaffold project with stub implementations. All business logic methods contain `TODO()` markers ready for incremental implementation. The project compiles and the infrastructure (Docker, Gradle, DI wiring) is fully functional.

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│             │     │             │     │             │
│   Web UI    │────▶│   API       │────▶│  RabbitMQ   │
│             │     │  (Ktor)     │     │             │
└─────────────┘     └──────┬──────┘     └──────┬──────┘
                           │                   │
                           ▼                   ▼
                    ┌─────────────┐     ┌─────────────┐
                    │             │     │             │
                    │  PostgreSQL │◀────│   Worker    │
                    │             │     │             │
                    └─────────────┘     └──────┬──────┘
                                               │
                    ┌─────────────┐            │
                    │             │            │
                    │ File Store  │◀───────────┤
                    │             │            │
                    └─────────────┘            │
                                               ▼
                                        ┌─────────────┐
                                        │     ML      │
                                        │  Service    │
                                        └─────────────┘
```

### Data Flow

1. **Upload**: User uploads document via REST API
2. **Store**: API saves file to local storage and metadata to PostgreSQL
3. **Queue**: API publishes message to RabbitMQ for async processing
4. **Process**: Worker consumes message, retrieves file, calls ML service
5. **Update**: Worker updates document record with classification result

## Module Structure

| Module | Description |
|--------|-------------|
| `core-domain` | Domain models and interfaces (framework-agnostic) |
| `infra-db` | PostgreSQL + Exposed repository, Flyway migrations |
| `infra-storage` | Local file storage service |
| `infra-queue` | RabbitMQ publisher and consumer |
| `app-api` | Ktor HTTP server with REST endpoints |
| `app-worker` | Background worker for document processing |
| `docker` | Docker Compose for local development |

## Tech Stack

- **Language**: Kotlin 2.0
- **Build**: Gradle (Kotlin DSL) with version catalog
- **HTTP**: Ktor (Netty) for server and client
- **Serialization**: kotlinx.serialization
- **DI**: Koin
- **Database**: PostgreSQL + Exposed (DSL) + HikariCP
- **Migrations**: Flyway
- **Messaging**: RabbitMQ (amqp-client)
- **Async**: Kotlin Coroutines
- **Logging**: SLF4J + Logback
- **Config**: HOCON with env var substitution
- **Testing**: JUnit 5 + Testcontainers

## Quick Start

### Prerequisites

- JDK 21+
- Docker & Docker Compose

### 1. Start Infrastructure

```bash
cd docker
docker-compose up -d
```

This starts:
- PostgreSQL on port 5432
- RabbitMQ on port 5672 (management UI on 15672)

### 2. Run the API

```bash
./gradlew :app-api:run
```

API starts on http://localhost:8080

### 3. Run the Worker

```bash
./gradlew :app-worker:run
```

### 4. Test Upload

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/document.pdf" \
  -F "uploadedBy=testuser"
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/documents/upload` | Upload a document (multipart) |
| GET | `/api/documents/{id}` | Get document by ID |
| GET | `/api/documents` | List documents (optional `?classification=` filter) |

## Configuration

Configuration uses HOCON (`application.conf`) with environment variable overrides:

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | API server port | 8080 |
| `DATABASE_URL` | PostgreSQL JDBC URL | jdbc:postgresql://localhost:5432/document_pipeline |
| `DATABASE_USERNAME` | Database user | pipeline |
| `DATABASE_PASSWORD` | Database password | pipeline_secret |
| `RABBITMQ_HOST` | RabbitMQ host | localhost |
| `RABBITMQ_PORT` | RabbitMQ port | 5672 |
| `STORAGE_BASE_DIR` | File storage directory | ./document-storage |
| `ML_SERVICE_URL` | ML classification service URL | http://localhost:8000 |

## TODO Roadmap

Find all implementation stubs:
```bash
grep -rn "TODO" --include="*.kt" .
```

### Phase 1: Core Implementation
- [ ] Implement `ExposedDocumentRepository` methods
- [ ] Implement `LocalFileStorageService` methods
- [ ] Implement `RabbitMQPublisher.publish()`
- [ ] Implement `RabbitMQConsumer.start()`
- [ ] Implement API route handlers

### Phase 2: Worker & ML Integration
- [ ] Implement `DocumentProcessor.process()`
- [ ] Implement `HttpClassificationService.classify()`
- [ ] Create mock ML service for testing

### Phase 3: Production Hardening
- [ ] Add comprehensive error handling
- [ ] Add retry logic for transient failures
- [ ] Add metrics and observability
- [ ] Add authentication/authorization
- [ ] Add rate limiting

### Phase 4: Testing
- [ ] Unit tests for domain logic
- [ ] Integration tests with Testcontainers
- [ ] End-to-end API tests

## Development

### Build

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### Check Dependencies

```bash
./gradlew dependencies
```

## License

MIT
