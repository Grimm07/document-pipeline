-- Document ingestion pipeline - initial schema
-- Creates the documents table with all required columns and indexes

CREATE TABLE IF NOT EXISTS documents (
    -- Primary key: auto-generated UUID
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- File storage information
    storage_path    TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    mime_type       TEXT NOT NULL,
    file_size_bytes BIGINT NOT NULL,

    -- Classification results from ML service
    classification  TEXT NOT NULL DEFAULT 'unclassified',
    confidence      REAL,

    -- Flexible metadata as JSONB
    metadata        JSONB NOT NULL DEFAULT '{}',

    -- Audit fields
    uploaded_by     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for filtering by classification (common query pattern)
CREATE INDEX idx_documents_classification ON documents (classification);

-- Index for time-based queries and sorting
CREATE INDEX idx_documents_created_at ON documents (created_at);

-- GIN index for JSONB metadata searches
CREATE INDEX idx_documents_metadata ON documents USING GIN (metadata);

-- Add comment for documentation
COMMENT ON TABLE documents IS 'Stores document metadata and classification results for the ingestion pipeline';
