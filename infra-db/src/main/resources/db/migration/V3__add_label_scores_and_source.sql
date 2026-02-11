ALTER TABLE documents ADD COLUMN label_scores JSONB;
ALTER TABLE documents ADD COLUMN classification_source TEXT NOT NULL DEFAULT 'ml';
ALTER TABLE documents ADD COLUMN corrected_at TIMESTAMPTZ;
