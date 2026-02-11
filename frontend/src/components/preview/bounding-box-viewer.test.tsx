import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { BoundingBoxViewer } from "./bounding-box-viewer";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

// Mock heavy deps
vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  version: "5.0.0",
  getDocument: vi.fn(),
}));

vi.mock("openseadragon", () => ({
  default: vi.fn(() => ({
    open: vi.fn(),
    destroy: vi.fn(),
    addHandler: vi.fn(),
    clearOverlays: vi.fn(),
    addOverlay: vi.fn(),
  })),
  Rect: vi.fn(),
}));

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("BoundingBoxViewer", () => {
  it("shows loading state while fetching OCR data", () => {
    render(<BoundingBoxViewer documentId="test-id" mimeType="application/pdf" />, { wrapper });
    expect(screen.getByText("Loading OCR data...")).toBeInTheDocument();
  });
});
