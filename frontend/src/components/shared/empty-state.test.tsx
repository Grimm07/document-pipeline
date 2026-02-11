import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { EmptyState } from "./empty-state";

describe("EmptyState", () => {
  it("renders default title and description", () => {
    render(<EmptyState />);
    expect(screen.getByText("No documents found")).toBeInTheDocument();
    expect(screen.getByText("Upload a document to get started.")).toBeInTheDocument();
  });

  it("renders custom title and description", () => {
    render(<EmptyState title="Nothing here" description="Try a different filter." />);
    expect(screen.getByText("Nothing here")).toBeInTheDocument();
    expect(screen.getByText("Try a different filter.")).toBeInTheDocument();
  });
});
