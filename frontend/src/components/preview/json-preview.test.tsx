import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { JsonPreview } from "./json-preview";

describe("JsonPreview", () => {
  it("shows loading state initially", () => {
    render(<JsonPreview documentId="test-id" />);
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });
});
