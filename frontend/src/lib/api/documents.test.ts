import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { server } from "@/test/mocks/server";
import { fetchDocuments, fetchDocument } from "./documents";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("fetchDocuments", () => {
  it("returns document list", async () => {
    const result = await fetchDocuments();
    expect(result.documents).toBeDefined();
    expect(result.documents.length).toBeGreaterThan(0);
  });

  it("passes classification filter", async () => {
    const result = await fetchDocuments({ classification: "invoice" });
    expect(result).toBeDefined();
  });

  it("passes limit and offset", async () => {
    const result = await fetchDocuments({ limit: 10, offset: 5 });
    expect(result.limit).toBe(10);
    expect(result.offset).toBe(5);
  });
});

describe("fetchDocument", () => {
  it("returns a single document", async () => {
    const result = await fetchDocument("test-doc-001");
    expect(result.id).toBe("test-doc-001");
  });

  it("throws on 404", async () => {
    await expect(fetchDocument("nonexistent")).rejects.toThrow();
  });
});
