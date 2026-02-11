import { test, expect } from "@playwright/test";

test.describe("Document List", () => {
  test("shows documents page heading", async ({ page }) => {
    await page.goto("/documents");
    await expect(page.getByRole("heading", { name: "Documents" })).toBeVisible();
    await expect(page.getByText("Browse and filter")).toBeVisible();
  });

  test("shows classification filter buttons", async ({ page }) => {
    await page.goto("/documents");
    await expect(page.getByRole("button", { name: "All" })).toBeVisible();
  });
});
