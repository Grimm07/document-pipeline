import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createElement, type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { useBulkDelete } from "./use-bulk-delete";
import type { BulkDeleteResult } from "./use-bulk-delete";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
}

describe("useBulkDelete", () => {
  it("calls deleteDocument for each ID and reports all successful", async () => {
    const onComplete = vi.fn<(result: BulkDeleteResult) => void>();
    const { result } = renderHook(() => useBulkDelete(onComplete), {
      wrapper: createWrapper(),
    });

    result.current.mutate(["test-doc-001", "test-doc-002"]);

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(onComplete).toHaveBeenCalledOnce();
    expect(onComplete).toHaveBeenCalledWith({ successCount: 2, failureCount: 0 });
  });

  it("reports partial failure when some deletes return 404", async () => {
    const onComplete = vi.fn<(result: BulkDeleteResult) => void>();
    const { result } = renderHook(() => useBulkDelete(onComplete), {
      wrapper: createWrapper(),
    });

    // "nonexistent" returns 404 per MSW handlers
    result.current.mutate(["test-doc-001", "nonexistent"]);

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(onComplete).toHaveBeenCalledOnce();
    expect(onComplete).toHaveBeenCalledWith({ successCount: 1, failureCount: 1 });
  });

  it("reports all failures when every delete fails", async () => {
    const onComplete = vi.fn<(result: BulkDeleteResult) => void>();
    const { result } = renderHook(() => useBulkDelete(onComplete), {
      wrapper: createWrapper(),
    });

    result.current.mutate(["nonexistent", "nonexistent"]);

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(onComplete).toHaveBeenCalledWith({ successCount: 0, failureCount: 2 });
  });

  it("reports isPending while deleting", async () => {
    const onComplete = vi.fn();

    server.use(
      http.delete("/api/documents/:id", async () => {
        await new Promise((r) => setTimeout(r, 100));
        return new HttpResponse(null, { status: 204 });
      })
    );

    const { result } = renderHook(() => useBulkDelete(onComplete), {
      wrapper: createWrapper(),
    });

    result.current.mutate(["test-doc-001"]);

    await waitFor(() => {
      expect(result.current.isPending).toBe(true);
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(result.current.isPending).toBe(false);
  });
});
