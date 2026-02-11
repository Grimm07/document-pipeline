import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
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

function renderUploadPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });

  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/upload"] }),
  });

  return render(
    <ThemeProvider defaultTheme="dark">
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("Upload Page Integration", () => {
  it("renders upload form", async () => {
    renderUploadPage();
    // Wait for heading (use role to distinguish from button text)
    expect(await screen.findByRole("heading", { name: /upload document/i })).toBeInTheDocument();
    expect(screen.getByText("Drop a file here or click to browse")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /upload document/i })).toBeDisabled();
  });

  it("shows validation error for unsupported file type", async () => {
    renderUploadPage();

    await screen.findByRole("heading", { name: /upload document/i });

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;

    // Use fireEvent.change directly to bypass the accept attribute filtering
    // that user-event enforces (which prevents onChange from firing for
    // non-matching MIME types, matching real browser behavior for file dialogs).
    // In real usage, drag-and-drop bypasses accept filters, so this is valid.
    const unsupportedFile = new File(["content"], "archive.zip", {
      type: "application/zip",
    });
    fireEvent.change(fileInput, { target: { files: [unsupportedFile] } });

    expect(await screen.findByText(/unsupported file type/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /upload document/i })).toBeDisabled();
  });

  it("accepts valid file and enables upload button", async () => {
    const user = userEvent.setup();
    renderUploadPage();

    await screen.findByRole("heading", { name: /upload document/i });

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;

    const validFile = new File(["pdf content"], "report.pdf", {
      type: "application/pdf",
    });
    await user.upload(fileInput, validFile);

    expect(await screen.findByText("report.pdf")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /upload document/i })).toBeEnabled();
  });

  it("successful upload navigates to detail page", async () => {
    const user = userEvent.setup();
    renderUploadPage();

    await screen.findByRole("heading", { name: /upload document/i });

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;

    const validFile = new File(["pdf content"], "report.pdf", {
      type: "application/pdf",
    });
    await user.upload(fileInput, validFile);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /upload document/i })).toBeEnabled();
    });

    await user.click(screen.getByRole("button", { name: /upload document/i }));

    // After successful upload, the hook navigates to /documents/$documentId.
    // The MSW handler returns a doc with id "new-upload-001", so we should
    // see the detail page content for that document.
    await waitFor(
      () => {
        // The detail page shows "Download" button
        expect(screen.getByRole("button", { name: /download/i })).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("shows error message on upload failure", async () => {
    server.use(
      http.post("/api/documents/upload", () => {
        return HttpResponse.json({ error: "Internal Server Error" }, { status: 500 });
      }),
    );

    const user = userEvent.setup();
    renderUploadPage();

    await screen.findByRole("heading", { name: /upload document/i });

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;

    const validFile = new File(["pdf content"], "report.pdf", {
      type: "application/pdf",
    });
    await user.upload(fileInput, validFile);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /upload document/i })).toBeEnabled();
    });

    await user.click(screen.getByRole("button", { name: /upload document/i }));

    // The XHR-based upload function parses the error body
    await waitFor(
      () => {
        expect(screen.getByText(/internal server error|upload failed/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });
});
