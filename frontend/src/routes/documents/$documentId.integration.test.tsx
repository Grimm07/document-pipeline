import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, createRouter, createMemoryHistory } from "@tanstack/react-router";
import { ThemeProvider } from "@/components/shared/theme-provider";
import { handlers } from "@/test/mocks/handlers";
import { createMockDocument } from "@/test/fixtures";
import { routeTree } from "@/routeTree.gen";

// Mock heavy PDF/viewer deps loaded transitively via the route tree
vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  version: "5.0.0",
  getDocument: vi.fn(() => ({
    promise: Promise.resolve({
      numPages: 1,
      destroy: vi.fn(),
      getPage: vi.fn(() =>
        Promise.resolve({
          getViewport: () => ({ width: 612, height: 792 }),
          render: () => ({ promise: Promise.resolve() }),
        }),
      ),
    }),
  })),
}));

vi.mock("pdfjs-dist/build/pdf.worker.min.mjs?url", () => ({
  default: "",
}));

vi.mock("openseadragon", () => ({
  default: vi.fn(() => ({
    open: vi.fn(),
    destroy: vi.fn(),
    addHandler: vi.fn(),
    clearOverlays: vi.fn(),
    addOverlay: vi.fn(),
    viewport: {
      imageToViewportRectangle: vi.fn(() => ({ x: 0, y: 0, width: 1, height: 1 })),
    },
    world: {
      getItemAt: vi.fn(() => ({
        getContentSize: vi.fn(() => ({ x: 612, y: 792 })),
      })),
    },
  })),
  Rect: vi.fn(),
}));

const server = setupServer(...handlers);
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderDocumentDetail(documentId: string) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });

  const router = createRouter({
    routeTree,
    history: createMemoryHistory({
      initialEntries: [`/documents/${documentId}`],
    }),
  });

  return render(
    <ThemeProvider defaultTheme="dark">
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("Document Detail Page Integration", () => {
  it("displays document details", async () => {
    // Default handler returns a classified invoice with id matching the URL param
    renderDocumentDetail("test-doc-001");

    // Wait for document name to appear
    expect(await screen.findByText("report.pdf")).toBeInTheDocument();

    // Classification badge
    expect(screen.getByText("invoice")).toBeInTheDocument();

    // Confidence (0.95 -> 95%)
    expect(screen.getByText("95%")).toBeInTheDocument();

    // File size (1048576 bytes = 1.0 MB)
    expect(screen.getByText("1.0 MB")).toBeInTheDocument();

    // MIME type
    expect(screen.getByText("application/pdf")).toBeInTheDocument();

    // Metadata key-value
    expect(screen.getByText("dept")).toBeInTheDocument();
    expect(screen.getByText("finance")).toBeInTheDocument();
  });

  it("shows Processing state for unclassified documents", async () => {
    server.use(
      http.get("/api/documents/:id", ({ params }) => {
        return HttpResponse.json(
          createMockDocument({
            id: params.id as string,
            classification: "unclassified",
            confidence: null,
            updatedAt: new Date().toISOString(),
          }),
        );
      }),
    );

    renderDocumentDetail("processing-doc");

    // Should show "Processing..." text
    expect(await screen.findByText("Processing...")).toBeInTheDocument();
    // Classification badge should say "unclassified"
    expect(screen.getByText("unclassified")).toBeInTheDocument();
    // Confidence should say "Pending"
    expect(screen.getByText("Pending")).toBeInTheDocument();
  });

  it("shows Failed state for timed-out classification", async () => {
    server.use(
      http.get("/api/documents/:id", ({ params }) => {
        return HttpResponse.json(
          createMockDocument({
            id: params.id as string,
            classification: "unclassified",
            confidence: null,
            // updatedAt is 6 minutes ago, exceeding the 5-minute timeout
            updatedAt: new Date(Date.now() - 6 * 60 * 1000).toISOString(),
          }),
        );
      }),
    );

    renderDocumentDetail("timed-out-doc");

    // Should show "Failed" text
    expect(await screen.findByText("Failed")).toBeInTheDocument();
    // Should show "Retry classification" button
    expect(screen.getByRole("button", { name: /retry classification/i })).toBeInTheDocument();
  });

  it("download button is present", async () => {
    renderDocumentDetail("test-doc-001");

    expect(await screen.findByRole("button", { name: /download/i })).toBeInTheDocument();
  });

  it("delete button opens confirmation dialog", async () => {
    const user = userEvent.setup();
    renderDocumentDetail("test-doc-001");

    // Wait for page to load
    await screen.findByText("report.pdf");

    // Click the delete button
    const deleteButton = screen.getByRole("button", { name: /delete/i });
    await user.click(deleteButton);

    // Confirmation dialog should appear
    expect(await screen.findByText("Delete document?")).toBeInTheDocument();
    expect(screen.getByText(/permanently delete/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /cancel/i })).toBeInTheDocument();
  });

  it("shows error display for nonexistent document", async () => {
    renderDocumentDetail("nonexistent");

    // The MSW handler returns 404 for "nonexistent" id, which triggers isError.
    // ErrorDisplay renders an <h3>Error</h3> heading.
    expect(await screen.findByRole("heading", { name: "Error" })).toBeInTheDocument();
  });
});
