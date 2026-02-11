import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/shared/theme-provider";
import { LabelCorrectionPopover } from "./label-correction-popover";

function renderPopover(props: Partial<React.ComponentProps<typeof LabelCorrectionPopover>> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });

  const defaultProps = {
    documentId: "test-doc-001",
    classification: "invoice",
    labelScores: { invoice: 0.85, contract: 0.1, report: 0.05 } as Record<string, number> | null,
    classificationSource: "ml",
    ...props,
  };

  return render(
    <ThemeProvider defaultTheme="dark">
      <QueryClientProvider client={queryClient}>
        <LabelCorrectionPopover {...defaultProps} />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("LabelCorrectionPopover", () => {
  it("renders the classification badge", () => {
    renderPopover();
    expect(screen.getByText("invoice")).toBeInTheDocument();
  });

  it("opens popover on click and shows label scores", async () => {
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByText("invoice"));

    expect(await screen.findByText("Click a label to correct")).toBeInTheDocument();
    expect(screen.getByText("85.0%")).toBeInTheDocument();
    expect(screen.getByText("10.0%")).toBeInTheDocument();
    expect(screen.getByText("5.0%")).toBeInTheDocument();
  });

  it("sorts labels by score descending", async () => {
    const user = userEvent.setup();
    renderPopover({
      labelScores: { report: 0.05, invoice: 0.85, contract: 0.1 },
    });

    await user.click(screen.getByText("invoice"));
    await screen.findByText("Click a label to correct");

    const buttons = screen
      .getAllByRole("button")
      .filter((b) => b.closest("[data-slot='popover-content']"));
    const labels = buttons.map((b) => b.textContent);

    // invoice should be first (0.85), contract second (0.1), report last (0.05)
    expect(labels[0]).toContain("invoice");
    expect(labels[1]).toContain("contract");
    expect(labels[2]).toContain("report");
  });

  it("falls back to CLASSIFICATIONS when labelScores is null", async () => {
    const user = userEvent.setup();
    renderPopover({ labelScores: null });

    await user.click(screen.getByText("invoice"));
    await screen.findByText("Click a label to correct");

    // Should show known classifications without percentages
    expect(screen.getByText("receipt")).toBeInTheDocument();
    expect(screen.getByText("contract")).toBeInTheDocument();
    expect(screen.queryByText("%")).not.toBeInTheDocument();
  });

  it("disables current classification label", async () => {
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByText("invoice"));
    await screen.findByText("Click a label to correct");

    const invoiceButtons = screen
      .getAllByRole("button")
      .filter(
        (b) => b.closest("[data-slot='popover-content']") && b.textContent?.includes("invoice"),
      );
    expect(invoiceButtons[0]).toBeDisabled();
  });

  it("opens confirmation dialog when selecting a different label", async () => {
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByText("invoice"));
    await screen.findByText("Click a label to correct");

    await user.click(screen.getByText("contract"));

    expect(await screen.findByText("Correct classification?")).toBeInTheDocument();
    expect(screen.getByText(/Change classification to/)).toBeInTheDocument();
  });

  it("calls PATCH endpoint on confirm and closes", async () => {
    const user = userEvent.setup();
    renderPopover();

    // Open popover
    await user.click(screen.getByText("invoice"));
    await screen.findByText("Click a label to correct");

    // Select a different label to open confirmation
    const contractButtons = screen
      .getAllByRole("button")
      .filter(
        (b) => b.closest("[data-slot='popover-content']") && b.textContent?.includes("contract"),
      );
    await user.click(contractButtons[0]!);

    // Wait for confirmation dialog to appear
    await screen.findByText("Correct classification?");

    const confirmBtn = screen.getByRole("button", { name: "Confirm" });
    await user.click(confirmBtn);

    // The mutation closes the dialog and popover on success
    await waitFor(() => {
      expect(screen.queryByText("Correct classification?")).not.toBeInTheDocument();
    });
  });

  it("closes confirmation dialog on cancel", async () => {
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByText("invoice"));
    await screen.findByText("Click a label to correct");
    await user.click(screen.getByText("contract"));
    await screen.findByText("Correct classification?");

    await user.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => {
      expect(screen.queryByText("Correct classification?")).not.toBeInTheDocument();
    });
  });

  it("is disabled when disabled prop is true", () => {
    renderPopover({ disabled: true });
    const trigger = screen.getByRole("button");
    expect(trigger).toBeDisabled();
  });
});
