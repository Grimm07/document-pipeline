import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { XmlPreview } from "./xml-preview";

describe("XmlPreview", () => {
  it("shows loading state initially", () => {
    render(<XmlPreview documentId="test-id" />);
    expect(screen.getByText("Loading preview...")).toBeInTheDocument();
  });
});
