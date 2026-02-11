import { test, expect } from "@playwright/test";

test.describe("Theme", () => {
  test("defaults to dark mode", async ({ page }) => {
    await page.goto("/");
    const html = page.locator("html");
    await expect(html).toHaveClass(/dark/);
  });

  test("toggles to light mode", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: /toggle theme/i }).click();
    await page.getByText("Light").click();
    const html = page.locator("html");
    await expect(html).toHaveClass(/light/);
  });

  test("persists theme on refresh", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: /toggle theme/i }).click();
    await page.getByText("Light").click();

    await page.reload();
    const html = page.locator("html");
    await expect(html).toHaveClass(/light/);
  });
});
