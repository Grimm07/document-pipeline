import { describe, it, expect, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useLabelCorrection } from "./use-label-correction";
import { createQueryWrapper } from "@/test/test-utils";
import * as api from "@/lib/api/documents";
import { createMockDocument } from "@/test/fixtures";

vi.mock("@/lib/api/documents", async (importOriginal) => {
  const actual = await importOriginal<typeof api>();
  return { ...actual, correctClassification: vi.fn() };
});

const mockCorrectClassification = vi.mocked(api.correctClassification);

describe("useLabelCorrection", () => {
  it("calls correctClassification with id and label", async () => {
    const mockDoc = createMockDocument({
      classification: "contract",
      classificationSource: "manual",
      correctedAt: new Date().toISOString(),
    });
    mockCorrectClassification.mockResolvedValueOnce(mockDoc);

    const { result } = renderHook(() => useLabelCorrection("doc-123"), {
      wrapper: createQueryWrapper(),
    });

    result.current.mutate("contract");

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(mockCorrectClassification).toHaveBeenCalledWith("doc-123", "contract");
  });

  it("reports error when API call fails", async () => {
    mockCorrectClassification.mockRejectedValueOnce(new Error("Not found"));

    const { result } = renderHook(() => useLabelCorrection("nonexistent"), {
      wrapper: createQueryWrapper(),
    });

    result.current.mutate("contract");

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
  });
});
