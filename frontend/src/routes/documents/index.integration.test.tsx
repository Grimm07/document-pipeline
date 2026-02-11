import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, createRouter, createMemoryHistory } from "@tanstack/react-router";
import { ThemeProvider } from "@/components/shared/theme-provider";
import { handlers } from "@/test/mocks/handlers";
import { routeTree } from "@/routeTree.gen";

// Mock heavy PDF/viewer deps that are loaded transitively via the route tree
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

function renderDocumentList(search: string = "") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });

  const router = createRouter({
    routeTree,
    history: createMemoryHistory({
      initialEntries: [`/documents/${search}`],
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

describe("Document List Page Integration", () => {
  it("loads and displays documents from API", async () => {
    renderDocumentList();

    // The default MSW handler returns 5 documents named document-1.pdf through document-5.pdf
    expect(await screen.findByText("document-1.pdf")).toBeInTheDocument();
    expect(screen.getByText("document-2.pdf")).toBeInTheDocument();
    expect(screen.getByText("document-3.pdf")).toBeInTheDocument();
    expect(screen.getByText("document-4.pdf")).toBeInTheDocument();
    expect(screen.getByText("document-5.pdf")).toBeInTheDocument();
  });

  it("shows empty state when no documents match", async () => {
    server.use(
      http.get("/api/documents", () => {
        return HttpResponse.json({
          documents: [],
          total: 0,
          limit: 20,
          offset: 0,
        });
      }),
    );

    renderDocumentList();

    expect(await screen.findByText("No documents found")).toBeInTheDocument();
  });

  it("shows classification filter buttons", async () => {
    renderDocumentList();

    // Wait for page to load
    await screen.findByText("document-1.pdf");

    // Classification filter buttons should be present
    expect(screen.getByRole("button", { name: "All" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /invoice/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /receipt/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /contract/i })).toBeInTheDocument();
  });

  it("classification filter changes displayed documents", async () => {
    // Override: when classification=invoice is requested, return only 1 invoice doc
    server.use(
      http.get("/api/documents", ({ request }) => {
        const url = new URL(request.url);
        const classification = url.searchParams.get("classification");
        if (classification === "invoice") {
          return HttpResponse.json({
            documents: [
              {
                id: "inv-001",
                originalFilename: "invoice-2025.pdf",
                mimeType: "application/pdf",
                fileSizeBytes: 500000,
                classification: "invoice",
                confidence: 0.92,
                metadata: {},
                uploadedBy: null,
                hasOcrResults: false,
                createdAt: "2025-01-15T10:30:00Z",
                updatedAt: "2025-01-15T10:30:05Z",
              },
            ],
            total: 1,
            limit: 20,
            offset: 0,
          });
        }
        // Default: return 1 doc
        return HttpResponse.json({
          documents: [
            {
              id: "doc-001",
              originalFilename: "all-doc-1.pdf",
              mimeType: "application/pdf",
              fileSizeBytes: 1000,
              classification: "invoice",
              confidence: 0.95,
              metadata: {},
              uploadedBy: null,
              hasOcrResults: false,
              createdAt: "2025-01-15T10:30:00Z",
              updatedAt: "2025-01-15T10:30:05Z",
            },
          ],
          total: 1,
          limit: 20,
          offset: 0,
        });
      }),
    );

    const user = userEvent.setup();
    renderDocumentList();

    // Wait for initial load
    await screen.findByText("all-doc-1.pdf");

    // Click the "invoice" filter button -- its content is a ClassificationBadge
    const invoiceButton = screen.getByRole("button", { name: /invoice/i });
    await user.click(invoiceButton);

    // After filter, should show the invoice-specific document
    expect(await screen.findByText("invoice-2025.pdf")).toBeInTheDocument();
  });

  it("selection mode shows select all and cancel buttons", async () => {
    const user = userEvent.setup();
    renderDocumentList();

    // Wait for documents to load
    await screen.findByText("document-1.pdf");

    // Click "Select" button to enter selection mode
    const selectButton = screen.getByRole("button", { name: /select$/i });
    await user.click(selectButton);

    // Selection mode toolbar should appear
    expect(screen.getByText("0 selected")).toBeInTheDocument();
    expect(screen.getByText("Select all")).toBeInTheDocument();
    expect(screen.getByText("Deselect all")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /cancel/i })).toBeInTheDocument();
  });

  it("empty state with classification filter shows contextual message", async () => {
    server.use(
      http.get("/api/documents", ({ request }) => {
        const url = new URL(request.url);
        const classification = url.searchParams.get("classification");
        if (classification) {
          return HttpResponse.json({
            documents: [],
            total: 0,
            limit: 20,
            offset: 0,
          });
        }
        return HttpResponse.json({
          documents: [
            {
              id: "doc-1",
              originalFilename: "doc.pdf",
              mimeType: "application/pdf",
              fileSizeBytes: 1000,
              classification: "report",
              confidence: 0.9,
              metadata: {},
              uploadedBy: null,
              hasOcrResults: false,
              createdAt: "2025-01-15T10:30:00Z",
              updatedAt: "2025-01-15T10:30:05Z",
            },
          ],
          total: 1,
          limit: 20,
          offset: 0,
        });
      }),
    );

    const user = userEvent.setup();
    renderDocumentList();

    await screen.findByText("doc.pdf");

    // Click invoice filter
    await user.click(screen.getByRole("button", { name: /invoice/i }));

    // Should show contextual empty state
    expect(await screen.findByText(/no invoice documents/i)).toBeInTheDocument();
  });
});
