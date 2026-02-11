import { http, HttpResponse } from "msw";
import { createMockDocument, createMockDocumentList, MOCK_OCR_RESULT } from "../fixtures";

export const handlers = [
  // List documents
  http.get("/api/documents", ({ request }) => {
    const url = new URL(request.url);
    const classification = url.searchParams.get("classification");
    const limit = parseInt(url.searchParams.get("limit") ?? "20");
    const offset = parseInt(url.searchParams.get("offset") ?? "0");

    const allDocs = createMockDocumentList(5);
    let filtered = allDocs.documents;
    if (classification) {
      filtered = filtered.filter((d) => d.classification === classification);
    }
    const paged = filtered.slice(offset, offset + limit);

    return HttpResponse.json({
      documents: paged,
      total: paged.length,
      limit,
      offset,
    });
  }),

  // Get single document
  http.get("/api/documents/:id", ({ params }) => {
    const id = params.id as string;
    if (id === "nonexistent") {
      return HttpResponse.json({ error: "Document not found" }, { status: 404 });
    }
    return HttpResponse.json(createMockDocument({ id }));
  }),

  // Get OCR results
  http.get("/api/documents/:id/ocr", ({ params }) => {
    const id = params.id as string;
    if (id === "nonexistent" || id === "no-ocr") {
      return HttpResponse.json({ error: "No OCR results" }, { status: 404 });
    }
    return HttpResponse.json(MOCK_OCR_RESULT);
  }),

  // Download document
  http.get("/api/documents/:id/download", () => {
    return new HttpResponse(new Uint8Array([0x25, 0x50, 0x44, 0x46]), {
      headers: {
        "Content-Type": "application/pdf",
        "Content-Disposition": 'attachment; filename="report.pdf"',
      },
    });
  }),

  // Upload document
  http.post("/api/documents/upload", async () => {
    return HttpResponse.json(createMockDocument({ id: "new-upload-001" }));
  }),

  // Search documents
  http.get("/api/documents/search", () => {
    return HttpResponse.json(createMockDocumentList(2));
  }),

  // Delete document
  http.delete("/api/documents/:id", ({ params }) => {
    const id = params.id as string;
    if (id === "nonexistent") {
      return HttpResponse.json({ error: "Document not found" }, { status: 404 });
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // Retry classification
  http.post("/api/documents/:id/retry", ({ params }) => {
    const id = params.id as string;
    if (id === "nonexistent") {
      return HttpResponse.json({ error: "Document not found" }, { status: 404 });
    }
    return HttpResponse.json(
      createMockDocument({
        id: id as string,
        classification: "unclassified",
        confidence: null,
        labelScores: null,
        classificationSource: "ml",
        hasOcrResults: false,
        correctedAt: null,
        updatedAt: new Date().toISOString(),
      }),
    );
  }),

  // Correct classification
  http.patch("/api/documents/:id/classification", async ({ params, request }) => {
    const id = params.id as string;
    if (id === "nonexistent") {
      return HttpResponse.json({ error: "Document not found" }, { status: 404 });
    }
    const body = (await request.json()) as { classification: string };
    return HttpResponse.json(
      createMockDocument({
        id: id as string,
        classification: body.classification,
        classificationSource: "manual",
        correctedAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }),
    );
  }),
];
