import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { DocumentViewerTabs } from "./document-viewer-tabs";
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

describe("DocumentViewerTabs", () => {
  it("renders plain preview when no OCR results", () => {
    render(
      <DocumentViewerTabs
        documentId="test-id"
        mimeType="image/png"
        filename="photo.png"
        hasOcrResults={false}
      />,
      { wrapper }
    );
    // Should render ImagePreview directly, not tabs
    expect(screen.getByRole("img")).toBeInTheDocument();
    expect(screen.queryByText("OCR Text")).not.toBeInTheDocument();
  });

  it("renders tabs when document has OCR results", () => {
    render(
      <DocumentViewerTabs
        documentId="test-id"
        mimeType="application/pdf"
        filename="scan.pdf"
        hasOcrResults={true}
      />,
      { wrapper }
    );
    expect(screen.getByText("Preview")).toBeInTheDocument();
    expect(screen.getByText("OCR Text")).toBeInTheDocument();
    expect(screen.getByText("Bounding Boxes")).toBeInTheDocument();
  });
});
