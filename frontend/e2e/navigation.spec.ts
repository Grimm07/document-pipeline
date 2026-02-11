import { test, expect } from "@playwright/test";

test.describe("Navigation", () => {
  test("loads dashboard at root URL", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByText("Dashboard")).toBeVisible();
    await expect(page.getByText("Document Pipeline")).toBeVisible();
  });

  test("navigates to documents page", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: /documents/i }).first().click();
    await expect(page.getByText("Browse and filter")).toBeVisible();
  });

  test("navigates to upload page", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: /upload/i }).first().click();
    await expect(page.getByRole("heading", { name: "Upload Document" })).toBeVisible();
  });

  test("direct URL to documents page works", async ({ page }) => {
    await page.goto("/documents");
    await expect(page.getByRole("heading", { name: "Documents" })).toBeVisible();
  });

  test("direct URL to upload page works", async ({ page }) => {
    await page.goto("/upload");
    await expect(page.getByRole("heading", { name: "Upload Document" })).toBeVisible();
  });
});
