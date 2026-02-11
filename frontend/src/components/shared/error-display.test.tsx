import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorDisplay } from "./error-display";

describe("ErrorDisplay", () => {
  it("renders default error message", () => {
    render(<ErrorDisplay />);
    expect(screen.getByText("Something went wrong.")).toBeInTheDocument();
  });

  it("renders custom error message", () => {
    render(<ErrorDisplay message="Network timeout" />);
    expect(screen.getByText("Network timeout")).toBeInTheDocument();
  });

  it("shows retry button when onRetry provided", () => {
    render(<ErrorDisplay onRetry={() => {}} />);
    expect(screen.getByText("Try Again")).toBeInTheDocument();
  });

  it("does not show retry button without onRetry", () => {
    render(<ErrorDisplay />);
    expect(screen.queryByText("Try Again")).not.toBeInTheDocument();
  });

  it("calls onRetry when retry button is clicked", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(<ErrorDisplay onRetry={onRetry} />);

    await user.click(screen.getByText("Try Again"));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
