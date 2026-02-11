import { test, expect } from "@playwright/test";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const FIXTURE_PATH = path.resolve(__dirname, "fixtures/test-document.pdf");

test.describe("Error States", () => {
  test("error when document list API fails (500)", async ({ page }) => {
    await page.route("**/api/documents?*", (route) =>
      route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ error: "Internal server error" }),
      })
    );
    await page.route("**/api/documents", (route) => {
      if (route.request().url().includes("?")) return route.continue();
      return route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ error: "Internal server error" }),
      });
    });

    await page.goto("/documents");

    // Error display should show with retry button
    await expect(page.getByRole("heading", { name: "Error" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Try Again" })).toBeVisible();
  });

  test("error when document detail returns 404", async ({ page }) => {
    await page.route("**/api/documents/nonexistent-id", (route) => {
      if (route.request().method() === "GET") {
        return route.fulfill({
          status: 404,
          contentType: "application/json",
          body: JSON.stringify({ error: "Document not found" }),
        });
      }
      return route.continue();
    });

    await page.goto("/documents/nonexistent-id");

    // Should show error
    await expect(page.getByRole("heading", { name: "Error" })).toBeVisible();
    await expect(page.getByText(/not found/i)).toBeVisible();
  });

  test("error when upload fails (500)", async ({ page }) => {
    await page.route("**/api/documents/upload", (route) =>
      route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ error: "Upload failed: server error" }),
      })
    );

    await page.goto("/upload");

    // Select a file
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(FIXTURE_PATH);
    await expect(page.getByText("test-document.pdf")).toBeVisible();

    // Submit
    await page.getByRole("button", { name: "Upload Document" }).click();

    // Error message should appear in the form
    await expect(page.getByText(/upload failed/i)).toBeVisible({ timeout: 10000 });
  });
});
