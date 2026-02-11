import { test, expect } from "@playwright/test";

test.describe("Upload Page", () => {
  test("shows upload form", async ({ page }) => {
    await page.goto("/upload");
    await expect(page.getByRole("heading", { name: "Upload Document" })).toBeVisible();
    await expect(
      page.getByText("Drop a file here or click to browse")
    ).toBeVisible();
  });

  test("shows metadata field controls", async ({ page }) => {
    await page.goto("/upload");
    await expect(page.getByText("Metadata", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Add Field" })).toBeVisible();
  });

  test("upload button is disabled without file", async ({ page }) => {
    await page.goto("/upload");
    const uploadBtn = page.getByRole("button", { name: "Upload Document" });
    await expect(uploadBtn).toBeDisabled();
  });

  test("can add and remove metadata fields", async ({ page }) => {
    await page.goto("/upload");

    await page.getByRole("button", { name: "Add Field" }).click();
    const inputs = page.getByRole("textbox");
    await expect(inputs).toHaveCount(2); // key + value

    // Click the remove button (X icon)
    const removeBtn = page.locator("button").filter({ has: page.locator("svg") }).last();
    await removeBtn.click();
    await expect(page.getByRole("textbox")).toHaveCount(0);
  });
});
