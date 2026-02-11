/** Accepted MIME types for upload */
export const ACCEPTED_MIME_TYPES = [
  "application/pdf",
  "image/png",
  "image/jpeg",
  "image/gif",
  "image/webp",
  "text/plain",
  "text/csv",
] as const;

/** Max file size in bytes (50MB — matches backend default) */
export const MAX_FILE_SIZE = 50 * 1024 * 1024;

/** Default page size for document list */
export const DEFAULT_PAGE_SIZE = 20;

/** Polling interval for classification status (ms) */
export const CLASSIFICATION_POLL_INTERVAL = 3000;

/** Max polling duration before showing timeout message (ms) */
export const CLASSIFICATION_POLL_TIMEOUT = 5 * 60 * 1000;

/** Known classification categories */
export const CLASSIFICATIONS = [
  "invoice",
  "receipt",
  "contract",
  "report",
  "letter",
  "form",
  "other",
] as const;
