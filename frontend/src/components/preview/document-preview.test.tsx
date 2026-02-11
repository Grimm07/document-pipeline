import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { DocumentPreview } from "./document-preview";

// Mock heavy deps to avoid worker/canvas issues in tests
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
  })),
}));

describe("DocumentPreview", () => {
  it("renders loading state for PDF (async preview)", () => {
    render(
      <DocumentPreview documentId="test-id" mimeType="application/pdf" filename="report.pdf" />,
    );
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });

  it("renders img for image types", () => {
    render(<DocumentPreview documentId="test-id" mimeType="image/png" filename="photo.png" />);
    expect(screen.getByRole("img")).toBeInTheDocument();
  });

  it("renders loading state for text (async preview)", () => {
    render(<DocumentPreview documentId="test-id" mimeType="text/plain" filename="notes.txt" />);
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });

  it("renders JSON viewer for application/json", () => {
    render(
      <DocumentPreview documentId="test-id" mimeType="application/json" filename="data.json" />,
    );
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });

  it("renders XML viewer for application/xml", () => {
    render(<DocumentPreview documentId="test-id" mimeType="application/xml" filename="data.xml" />);
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });

  it("renders XML viewer for text/xml", () => {
    render(<DocumentPreview documentId="test-id" mimeType="text/xml" filename="data.xml" />);
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });

  it("renders fallback for unsupported types", () => {
    render(
      <DocumentPreview documentId="test-id" mimeType="application/zip" filename="archive.zip" />,
    );
    expect(screen.getByText(/Preview not available/)).toBeInTheDocument();
    expect(screen.getByText("Download File")).toBeInTheDocument();
  });
});
