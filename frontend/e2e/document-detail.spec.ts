import { test, expect } from "@playwright/test";

function makeDoc(overrides: Record<string, unknown> = {}) {
  return {
    id: "doc-100",
    originalFilename: "quarterly-report.pdf",
    mimeType: "application/pdf",
    fileSizeBytes: 1048576,
    classification: "report",
    confidence: 0.92,
    metadata: { department: "Finance" },
    uploadedBy: "alice",
    hasOcrResults: false,
    createdAt: "2025-06-15T10:30:00Z",
    updatedAt: "2025-06-15T10:31:00Z",
    ...overrides,
  };
}

function mockDetailRoute(page: import("@playwright/test").Page, doc: ReturnType<typeof makeDoc>) {
  return page.route("**/api/documents/doc-100", (route) => {
    if (route.request().method() === "GET") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(doc),
      });
    }
    return route.continue();
  });
}

test.describe("Document Detail", () => {
  test("shows document metadata and classification", async ({ page }) => {
    const doc = makeDoc();
    await mockDetailRoute(page, doc);

    // Mock download to avoid network errors
    await page.route("**/api/documents/doc-100/download", (route) =>
      route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.0") })
    );

    await page.goto("/documents/doc-100");

    // Filename visible
    await expect(page.getByText("quarterly-report.pdf")).toBeVisible();

    // Classification badge
    await expect(page.getByText("report", { exact: true })).toBeVisible();

    // Confidence
    await expect(page.getByText("92%")).toBeVisible();

    // File size (1 MB)
    await expect(page.getByText("1.0 MB")).toBeVisible();

    // Metadata
    await expect(page.getByText("department")).toBeVisible();
    await expect(page.getByText("Finance")).toBeVisible();
  });

  test("shows Processing state for unclassified document", async ({ page }) => {
    const doc = makeDoc({
      classification: "unclassified",
      confidence: null,
      updatedAt: new Date().toISOString(), // recent — still processing
    });
    await mockDetailRoute(page, doc);

    await page.route("**/api/documents/doc-100/download", (route) =>
      route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.0") })
    );

    await page.goto("/documents/doc-100");

    await expect(page.getByText("Processing...")).toBeVisible();
  });

  test("shows Failed state for timed-out classification", async ({ page }) => {
    // 6 minutes ago — exceeds the 5-minute CLASSIFICATION_POLL_TIMEOUT
    const sixMinAgo = new Date(Date.now() - 6 * 60 * 1000).toISOString();
    const doc = makeDoc({
      classification: "unclassified",
      confidence: null,
      updatedAt: sixMinAgo,
    });
    await mockDetailRoute(page, doc);

    await page.route("**/api/documents/doc-100/download", (route) =>
      route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.0") })
    );

    await page.goto("/documents/doc-100");

    await expect(page.getByText("Failed")).toBeVisible();
    await expect(page.getByText("Retry classification")).toBeVisible();
  });

  test("retry classification button works", async ({ page }) => {
    const sixMinAgo = new Date(Date.now() - 6 * 60 * 1000).toISOString();
    const doc = makeDoc({
      classification: "unclassified",
      confidence: null,
      updatedAt: sixMinAgo,
    });
    await mockDetailRoute(page, doc);

    await page.route("**/api/documents/doc-100/download", (route) =>
      route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.0") })
    );

    // Mock retry endpoint
    let retryCalled = false;
    await page.route("**/api/documents/doc-100/retry", (route) => {
      retryCalled = true;
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          makeDoc({
            classification: "unclassified",
            confidence: null,
            updatedAt: new Date().toISOString(), // reset timer
          })
        ),
      });
    });

    await page.goto("/documents/doc-100");
    await expect(page.getByText("Retry classification")).toBeVisible();

    await page.getByText("Retry classification").click();

    // Verify retry was called
    await expect.poll(() => retryCalled).toBe(true);
  });

  test("delete button opens confirmation dialog", async ({ page }) => {
    const doc = makeDoc();
    await mockDetailRoute(page, doc);

    await page.route("**/api/documents/doc-100/download", (route) =>
      route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.0") })
    );

    await page.goto("/documents/doc-100");

    // Click the delete button
    await page.getByRole("button", { name: "Delete" }).click();

    // Verify confirmation dialog appears
    await expect(page.getByText("Delete document?")).toBeVisible();
    await expect(page.getByText("This will permanently delete")).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel" })).toBeVisible();
  });

  test("download button is visible", async ({ page }) => {
    const doc = makeDoc();
    await mockDetailRoute(page, doc);

    await page.route("**/api/documents/doc-100/download", (route) =>
      route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.0") })
    );

    await page.goto("/documents/doc-100");

    await expect(page.getByRole("button", { name: "Download" })).toBeVisible();
  });

  test("OCR tabs visible when hasOcrResults is true", async ({ page }) => {
    const doc = makeDoc({ hasOcrResults: true });
    await mockDetailRoute(page, doc);

    await page.route("**/api/documents/doc-100/download", (route) =>
      route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.0") })
    );

    // Mock OCR results endpoint
    await page.route("**/api/documents/doc-100/ocr", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          pages: [
            {
              pageIndex: 0,
              width: 612,
              height: 792,
              text: "Sample OCR text",
              blocks: [
                {
                  text: "Sample OCR text",
                  bbox: { x: 50, y: 50, width: 200, height: 20 },
                  confidence: 0.98,
                },
              ],
            },
          ],
          fullText: "Sample OCR text",
        }),
      })
    );

    await page.goto("/documents/doc-100");

    // Tabs should include OCR options
    await expect(page.getByRole("tab", { name: "OCR Text" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Bounding Boxes" })).toBeVisible();
  });
});
