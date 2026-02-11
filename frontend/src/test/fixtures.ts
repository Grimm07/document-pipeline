import type { DocumentResponse, DocumentListResponse, OcrResult } from "@/types/api";

export function createMockDocument(overrides: Partial<DocumentResponse> = {}): DocumentResponse {
  return {
    id: "test-doc-001",
    originalFilename: "report.pdf",
    mimeType: "application/pdf",
    fileSizeBytes: 1048576,
    classification: "invoice",
    confidence: 0.95,
    labelScores: { invoice: 0.85, contract: 0.1, report: 0.05 },
    classificationSource: "ml",
    metadata: { dept: "finance" },
    uploadedBy: null,
    hasOcrResults: false,
    correctedAt: null,
    createdAt: "2025-01-15T10:30:00Z",
    updatedAt: "2025-01-15T10:30:05Z",
    ...overrides,
  };
}

export function createMockDocumentList(
  count: number = 3,
  overrides: Partial<DocumentResponse> = {},
): DocumentListResponse {
  const documents = Array.from({ length: count }, (_, i) =>
    createMockDocument({
      id: `test-doc-${String(i + 1).padStart(3, "0")}`,
      originalFilename: `document-${i + 1}.pdf`,
      ...overrides,
    }),
  );

  return {
    documents,
    total: documents.length,
    limit: 20,
    offset: 0,
  };
}

export const UNCLASSIFIED_DOC = createMockDocument({
  id: "unclassified-001",
  classification: "unclassified",
  confidence: null,
});

export const CLASSIFIED_DOC = createMockDocument({
  id: "classified-001",
  classification: "invoice",
  confidence: 0.95,
});

export const IMAGE_DOC = createMockDocument({
  id: "image-001",
  originalFilename: "photo.png",
  mimeType: "image/png",
});

export const TEXT_DOC = createMockDocument({
  id: "text-001",
  originalFilename: "notes.txt",
  mimeType: "text/plain",
});

export const OCR_DOC = createMockDocument({
  id: "ocr-001",
  originalFilename: "scanned.pdf",
  mimeType: "application/pdf",
  hasOcrResults: true,
});

export const TIMED_OUT_DOC = createMockDocument({
  id: "timed-out-001",
  classification: "unclassified",
  confidence: null,
  createdAt: new Date(Date.now() - 6 * 60 * 1000).toISOString(),
  updatedAt: new Date(Date.now() - 6 * 60 * 1000).toISOString(),
});

export const MOCK_OCR_RESULT: OcrResult = {
  pages: [
    {
      pageIndex: 0,
      width: 612,
      height: 792,
      text: "This is extracted OCR text from page 1.",
      blocks: [
        {
          text: "",
          bbox: { x: 50, y: 100, width: 200, height: 30 },
          confidence: null,
        },
        {
          text: "",
          bbox: { x: 50, y: 150, width: 300, height: 30 },
          confidence: null,
        },
      ],
    },
  ],
  fullText: "This is extracted OCR text from page 1.",
};
