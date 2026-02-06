Add a PostgreSQL database layer for a document ingestion pipeline to this project. The pipeline stores raw files (PDFs/images) in object storage and tracks classification + metadata in Postgres.

## Tech choices
- PostgreSQL via [SQLAlchemy / asyncpg / Prisma / raw SQL migrations — pick one]
- Object storage: S3 (local file system for development)
- Migration tool: [Alembic / Flyway / dbmate — pick one, or suggest one that fits the stack]

## Schema requirements

Create a `documents` table with:
- `id`: UUID primary key, auto-generated
- `storage_key`: TEXT NOT NULL — the S3/object storage path (e.g., "uploads/2026/02/abc123.pdf")
- `original_filename`: TEXT NOT NULL
- `mime_type`: TEXT NOT NULL — e.g., "application/pdf", "image/png"
- `file_size_bytes`: BIGINT
- `classification`: TEXT NOT NULL — document type label from the ML classifier (e.g., "invoice", "contract", "receipt")
- `confidence`: REAL — classification confidence score 0.0–1.0
- `metadata`: JSONB NOT NULL DEFAULT '{}' — flexible metadata (page_count, extracted_text, language, source, tags, etc.)
- `uploaded_by`: TEXT
- `created_at`: TIMESTAMPTZ NOT NULL DEFAULT now()
- `updated_at`: TIMESTAMPTZ NOT NULL DEFAULT now()

## Indexes
- B-tree on `classification`
- B-tree on `created_at`
- GIN on `metadata` for JSONB queries

## What to generate

1. **Migration file(s)** — create the table + indexes
2. **Model/schema layer** — ORM model or TypedDict/dataclass representing the document
3. **Repository/DAO module** with these operations:
    - `insert_document(...)` — insert a new document record
    - `get_document(id)` — fetch by UUID
    - `list_documents(classification=None, limit=50, offset=0)` — list with optional filter
    - `search_metadata(key, value)` — query into the JSONB metadata field
    - `update_classification(id, classification, confidence)` — update after re-classification
4. **Storage helper module** — upload/download file to object storage, return the `storage_key`
5. **Config** — database connection string + object storage config via environment variables
6. **Docker Compose** (if not already present) — Postgres + MinIO for local dev
7. **Tests** — at least basic tests for the repository operations

## Conventions
- Follow existing project structure and conventions
- Use type hints throughout
- Add docstrings to public functions
- Use environment variables for all config (no hardcoded credentials)