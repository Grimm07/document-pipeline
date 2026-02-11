import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { OcrTextViewer } from "./ocr-text-viewer";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("OcrTextViewer", () => {
  it("shows loading state initially", () => {
    render(<OcrTextViewer documentId="test-id" />, { wrapper });
    expect(screen.getByText("Loading OCR results...")).toBeInTheDocument();
  });
});
