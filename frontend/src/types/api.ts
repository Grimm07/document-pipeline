/** Mirrors backend DocumentResponse DTO exactly */
export interface DocumentResponse {
  id: string;
  originalFilename: string;
  mimeType: string;
  fileSizeBytes: number;
  classification: string;
  confidence: number | null;
  metadata: Record<string, string>;
  uploadedBy: string | null;
  hasOcrResults: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors backend DocumentListResponse DTO */
export interface DocumentListResponse {
  documents: DocumentResponse[];
  total: number;
  limit: number;
  offset: number;
}

/** Mirrors backend ErrorResponse DTO */
export interface ErrorResponse {
  error: string;
  details?: string;
}

/** Frontend-only: filters for document list queries */
export interface DocumentListFilters {
  classification?: string;
  limit?: number;
  offset?: number;
}

/** Frontend-only: key-value pair for metadata form */
export interface MetadataEntry {
  key: string;
  value: string;
}

/** Frontend-only: upload form state */
export interface UploadFormValues {
  file: File | null;
  metadata: MetadataEntry[];
}

/** OCR bounding box coordinates in pixel space */
export interface BoundingBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** A detected text block with spatial location */
export interface TextBlock {
  text: string;
  bbox: BoundingBox;
  confidence: number | null;
}

/** OCR results for a single page */
export interface OcrPage {
  pageIndex: number;
  width: number;
  height: number;
  text: string;
  blocks: TextBlock[];
}

/** Complete OCR result returned by the ML service */
export interface OcrResult {
  pages: OcrPage[];
  fullText: string;
}
