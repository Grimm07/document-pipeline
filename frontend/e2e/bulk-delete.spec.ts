import { test, expect } from "@playwright/test";

const MOCK_DOCS = [
  {
    id: "bulk-1",
    originalFilename: "invoice-2024.pdf",
    mimeType: "application/pdf",
    fileSizeBytes: 102400,
    classification: "invoice",
    confidence: 0.95,
    metadata: {},
    uploadedBy: null,
    hasOcrResults: false,
    createdAt: "2025-06-01T10:00:00Z",
    updatedAt: "2025-06-01T10:01:00Z",
  },
  {
    id: "bulk-2",
    originalFilename: "contract-v2.pdf",
    mimeType: "application/pdf",
    fileSizeBytes: 204800,
    classification: "contract",
    confidence: 0.88,
    metadata: {},
    uploadedBy: null,
    hasOcrResults: false,
    createdAt: "2025-06-02T10:00:00Z",
    updatedAt: "2025-06-02T10:01:00Z",
  },
  {
    id: "bulk-3",
    originalFilename: "receipt-jan.png",
    mimeType: "image/png",
    fileSizeBytes: 51200,
    classification: "receipt",
    confidence: 0.91,
    metadata: {},
    uploadedBy: null,
    hasOcrResults: false,
    createdAt: "2025-06-03T10:00:00Z",
    updatedAt: "2025-06-03T10:01:00Z",
  },
];

function mockDocumentList(page: import("@playwright/test").Page) {
  return page.route("**/api/documents?*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        documents: MOCK_DOCS,
        total: 3,
        limit: 20,
        offset: 0,
      }),
    })
  );
}

test.describe("Bulk Delete", () => {
  test("enter selection mode shows checkboxes", async ({ page }) => {
    await mockDocumentList(page);
    // Also mock the unparameterized list endpoint
    await page.route("**/api/documents", (route) => {
      if (route.request().url().includes("?")) return route.continue();
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          documents: MOCK_DOCS,
          total: 3,
          limit: 20,
          offset: 0,
        }),
      });
    });

    await page.goto("/documents");

    // Wait for document cards to render
    await expect(page.getByText("invoice-2024.pdf")).toBeVisible();

    // Click the Select button
    await page.getByRole("button", { name: "Select" }).click();

    // Selection mode UI should appear
    await expect(page.getByText("0 selected")).toBeVisible();
    await expect(page.getByRole("button", { name: "Select all", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel" })).toBeVisible();
  });

  test("select and bulk delete documents", async ({ page }) => {
    await mockDocumentList(page);
    await page.route("**/api/documents", (route) => {
      if (route.request().url().includes("?")) return route.continue();
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          documents: MOCK_DOCS,
          total: 3,
          limit: 20,
          offset: 0,
        }),
      });
    });

    // Mock delete endpoints
    for (const doc of MOCK_DOCS) {
      await page.route(`**/api/documents/${doc.id}`, (route) => {
        if (route.request().method() === "DELETE") {
          return route.fulfill({ status: 204, body: "" });
        }
        return route.continue();
      });
    }

    await page.goto("/documents");
    await expect(page.getByText("invoice-2024.pdf")).toBeVisible();

    // Enter selection mode
    await page.getByRole("button", { name: "Select" }).click();

    // Select all documents
    await page.getByRole("button", { name: "Select all", exact: true }).click();
    await expect(page.getByText("3 selected")).toBeVisible();

    // Click bulk delete button
    await page.getByRole("button", { name: /Delete \(3\)/ }).click();

    // Confirm in dialog
    await expect(page.getByText("Delete 3 documents?")).toBeVisible();
    await page.locator('[role="alertdialog"]').getByRole("button", { name: "Delete" }).click();
  });

  test("partial delete failure shows error banner", async ({ page }) => {
    await mockDocumentList(page);
    await page.route("**/api/documents", (route) => {
      if (route.request().url().includes("?")) return route.continue();
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          documents: MOCK_DOCS,
          total: 3,
          limit: 20,
          offset: 0,
        }),
      });
    });

    // First two deletions succeed, third fails
    await page.route("**/api/documents/bulk-1", (route) => {
      if (route.request().method() === "DELETE") {
        return route.fulfill({ status: 204, body: "" });
      }
      return route.continue();
    });
    await page.route("**/api/documents/bulk-2", (route) => {
      if (route.request().method() === "DELETE") {
        return route.fulfill({ status: 204, body: "" });
      }
      return route.continue();
    });
    await page.route("**/api/documents/bulk-3", (route) => {
      if (route.request().method() === "DELETE") {
        return route.fulfill({
          status: 500,
          contentType: "application/json",
          body: JSON.stringify({ error: "Internal server error" }),
        });
      }
      return route.continue();
    });

    await page.goto("/documents");
    await expect(page.getByText("invoice-2024.pdf")).toBeVisible();

    // Enter selection mode and select all
    await page.getByRole("button", { name: "Select" }).click();
    await page.getByRole("button", { name: "Select all", exact: true }).click();

    // Bulk delete
    await page.getByRole("button", { name: /Delete \(3\)/ }).click();
    await page.locator('[role="alertdialog"]').getByRole("button", { name: "Delete" }).click();

    // Verify error banner appears with partial failure message
    await expect(page.getByText(/failed/i)).toBeVisible({ timeout: 10000 });
  });

  test("exit selection mode on cancel", async ({ page }) => {
    await mockDocumentList(page);
    await page.route("**/api/documents", (route) => {
      if (route.request().url().includes("?")) return route.continue();
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          documents: MOCK_DOCS,
          total: 3,
          limit: 20,
          offset: 0,
        }),
      });
    });

    await page.goto("/documents");
    await expect(page.getByText("invoice-2024.pdf")).toBeVisible();

    // Enter selection mode
    await page.getByRole("button", { name: "Select" }).click();
    await expect(page.getByText("0 selected")).toBeVisible();

    // Cancel
    await page.getByRole("button", { name: "Cancel" }).click();

    // Should be back to normal mode — "Select" button visible again
    await expect(page.getByRole("button", { name: "Select" })).toBeVisible();
  });
});
