import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { apiFetch, ApiError } from "./client";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("apiFetch", () => {
  it("fetches and parses JSON response", async () => {
    server.use(
      http.get("/api/test", () => {
        return HttpResponse.json({ message: "hello" });
      }),
    );

    const result = await apiFetch<{ message: string }>("/test");
    expect(result.message).toBe("hello");
  });

  it("throws ApiError with error body on 404", async () => {
    server.use(
      http.get("/api/missing", () => {
        return HttpResponse.json({ error: "Not found" }, { status: 404 });
      }),
    );

    await expect(apiFetch("/missing")).rejects.toThrow(ApiError);
    try {
      await apiFetch("/missing");
    } catch (e) {
      const err = e as ApiError;
      expect(err.status).toBe(404);
      expect(err.body.error).toBe("Not found");
    }
  });

  it("throws ApiError with fallback message on non-JSON error", async () => {
    server.use(
      http.get("/api/bad", () => {
        return new HttpResponse("Internal Server Error", { status: 500 });
      }),
    );

    await expect(apiFetch("/bad")).rejects.toThrow(ApiError);
  });

  it("handles empty response body", async () => {
    server.use(
      http.get("/api/empty", () => {
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const result = await apiFetch("/empty");
    expect(result).toBeUndefined();
  });
});
