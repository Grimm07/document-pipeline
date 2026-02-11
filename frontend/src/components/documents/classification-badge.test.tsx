import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ClassificationBadge } from "./classification-badge";

describe("ClassificationBadge", () => {
  it("renders the classification text", () => {
    render(<ClassificationBadge classification="invoice" />);
    expect(screen.getByText("invoice")).toBeInTheDocument();
  });

  it("applies color class for known classification", () => {
    render(<ClassificationBadge classification="invoice" />);
    const badge = screen.getByText("invoice");
    expect(badge.className).toContain("blue");
  });

  it("pulses when unclassified", () => {
    render(<ClassificationBadge classification="unclassified" />);
    const badge = screen.getByText("unclassified");
    expect(badge.className).toContain("animate-pulse");
  });

  it("does not pulse for classified documents", () => {
    render(<ClassificationBadge classification="invoice" />);
    const badge = screen.getByText("invoice");
    expect(badge.className).not.toContain("animate-pulse");
  });

  it("shows pencil icon when classificationSource is manual", () => {
    const { container } = render(
      <ClassificationBadge classification="invoice" classificationSource="manual" />,
    );
    const svg = container.querySelector("svg");
    expect(svg).toBeInTheDocument();
  });

  it("does not show pencil icon when classificationSource is ml", () => {
    const { container } = render(
      <ClassificationBadge classification="invoice" classificationSource="ml" />,
    );
    const svg = container.querySelector("svg");
    expect(svg).not.toBeInTheDocument();
  });

  it("does not show pencil icon when classificationSource is not provided", () => {
    const { container } = render(<ClassificationBadge classification="invoice" />);
    const svg = container.querySelector("svg");
    expect(svg).not.toBeInTheDocument();
  });
});
