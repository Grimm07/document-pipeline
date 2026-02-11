import type {
  DocumentResponse,
  DocumentListResponse,
  DocumentListFilters,
  OcrResult,
} from "@/types/api";
import { apiFetch } from "./client";

export async function fetchDocuments(
  filters: DocumentListFilters = {}
): Promise<DocumentListResponse> {
  const params = new URLSearchParams();
  if (filters.classification) params.set("classification", filters.classification);
  if (filters.limit) params.set("limit", String(filters.limit));
  if (filters.offset) params.set("offset", String(filters.offset));

  const qs = params.toString();
  return apiFetch<DocumentListResponse>(`/documents${qs ? `?${qs}` : ""}`);
}

export async function fetchDocument(id: string): Promise<DocumentResponse> {
  return apiFetch<DocumentResponse>(`/documents/${id}`);
}

export async function searchDocuments(
  metadata: Record<string, string>,
  limit?: number
): Promise<DocumentListResponse> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(metadata)) {
    params.set(`metadata.${key}`, value);
  }
  if (limit) params.set("limit", String(limit));
  return apiFetch<DocumentListResponse>(`/documents/search?${params.toString()}`);
}

export function getDocumentDownloadUrl(id: string): string {
  return `/api/documents/${id}/download`;
}

export async function fetchOcrResults(id: string): Promise<OcrResult> {
  return apiFetch<OcrResult>(`/documents/${id}/ocr`);
}

export async function deleteDocument(id: string): Promise<void> {
  await apiFetch<void>(`/documents/${id}`, { method: "DELETE" });
}

export async function retryClassification(id: string): Promise<DocumentResponse> {
  return apiFetch<DocumentResponse>(`/documents/${id}/retry`, { method: "POST" });
}

/**
 * Uploads a document with progress tracking via XHR.
 * Returns a promise that resolves with the DocumentResponse.
 */
export function uploadDocument(
  file: File,
  metadata: Record<string, string>,
  onProgress?: (percent: number) => void
): Promise<DocumentResponse> {
  return new Promise((resolve, reject) => {
    const formData = new FormData();
    formData.append("file", file, file.name);
    for (const [key, value] of Object.entries(metadata)) {
      formData.append(key, value);
    }

    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/documents/upload");

    xhr.upload.addEventListener("progress", (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    });

    xhr.addEventListener("load", () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText));
        } catch {
          reject(new Error("Invalid JSON response"));
        }
      } else {
        try {
          const body = JSON.parse(xhr.responseText);
          reject(new Error(body.error || `Upload failed: ${xhr.status}`));
        } catch {
          reject(new Error(`Upload failed: ${xhr.status}`));
        }
      }
    });

    xhr.addEventListener("error", () => reject(new Error("Network error")));
    xhr.addEventListener("abort", () => reject(new Error("Upload cancelled")));

    xhr.send(formData);
  });
}
