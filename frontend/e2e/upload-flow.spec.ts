import { test, expect } from "@playwright/test";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const MOCK_DOC = {
  id: "new-001",
  originalFilename: "report.pdf",
  mimeType: "application/pdf",
  fileSizeBytes: 204800,
  classification: "unclassified",
  confidence: null,
  metadata: {},
  uploadedBy: null,
  hasOcrResults: false,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const FIXTURE_PATH = path.resolve(__dirname, "fixtures/test-document.pdf");

test.describe("Upload Flow", () => {
  test("upload file shows success and redirects to detail", async ({ page }) => {
    // Mock upload endpoint (XHR POST)
    await page.route("**/api/documents/upload", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(MOCK_DOC),
      })
    );

    // Mock detail endpoint for the redirect target
    await page.route("**/api/documents/new-001", (route) => {
      if (route.request().method() === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(MOCK_DOC),
        });
      }
      return route.continue();
    });

    // Mock download endpoint to avoid errors on detail page
    await page.route("**/api/documents/new-001/download", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/pdf",
        body: Buffer.from("%PDF-1.0"),
      })
    );

    await page.goto("/upload");
    await expect(page.getByRole("heading", { name: "Upload Document" })).toBeVisible();

    // Select file via the hidden input
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(FIXTURE_PATH);

    // Verify file name appears in the dropzone
    await expect(page.getByText("test-document.pdf")).toBeVisible();

    // Submit the form
    await page.getByRole("button", { name: "Upload Document" }).click();

    // Wait for redirect to detail page
    await page.waitForURL("**/documents/new-001");

    // Verify document name is visible on detail page
    await expect(page.getByText("report.pdf")).toBeVisible();
  });

  test("upload with metadata fields", async ({ page }) => {
    const docWithMeta = {
      ...MOCK_DOC,
      metadata: { department: "Finance", priority: "high" },
    };

    let uploadCalled = false;

    await page.route("**/api/documents/upload", (route) => {
      uploadCalled = true;
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(docWithMeta),
      });
    });

    await page.route("**/api/documents/new-001", (route) => {
      if (route.request().method() === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(docWithMeta),
        });
      }
      return route.continue();
    });

    await page.route("**/api/documents/new-001/download", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/pdf",
        body: Buffer.from("%PDF-1.0"),
      })
    );

    await page.goto("/upload");

    // Add first metadata field
    await page.getByRole("button", { name: "Add Field" }).click();
    const inputs = page.getByRole("textbox");
    await inputs.nth(0).fill("department");
    await inputs.nth(1).fill("Finance");

    // Add second metadata field
    await page.getByRole("button", { name: "Add Field" }).click();
    const allInputs = page.getByRole("textbox");
    await allInputs.nth(2).fill("priority");
    await allInputs.nth(3).fill("high");

    // Select file
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(FIXTURE_PATH);

    // Submit
    await page.getByRole("button", { name: "Upload Document" }).click();

    // Wait for redirect
    await page.waitForURL("**/documents/new-001");

    // Verify upload was called
    expect(uploadCalled).toBe(true);
  });
});
