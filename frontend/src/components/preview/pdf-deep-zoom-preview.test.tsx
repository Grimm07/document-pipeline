import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { PdfDeepZoomPreview } from "./pdf-deep-zoom-preview";

// Mock pdfjs-dist to avoid worker issues in tests
vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  version: "5.0.0",
  getDocument: vi.fn(),
}));

// Mock openseadragon
vi.mock("openseadragon", () => ({
  default: vi.fn(() => ({
    open: vi.fn(),
    destroy: vi.fn(),
    addHandler: vi.fn(),
  })),
}));

describe("PdfDeepZoomPreview", () => {
  it("shows loading state initially", () => {
    render(<PdfDeepZoomPreview documentId="test-id" />);
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });
});
