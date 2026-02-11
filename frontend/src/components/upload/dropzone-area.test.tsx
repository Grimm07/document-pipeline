import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { DropzoneArea } from "./dropzone-area";

describe("DropzoneArea", () => {
  it("renders default dropzone text", () => {
    render(<DropzoneArea onFileSelect={vi.fn()} selectedFile={null} />);
    expect(
      screen.getByText("Drop a file here or click to browse")
    ).toBeInTheDocument();
  });

  it("shows selected file name", () => {
    const file = new File(["test"], "report.pdf", {
      type: "application/pdf",
    });
    render(<DropzoneArea onFileSelect={vi.fn()} selectedFile={file} />);
    expect(screen.getByText("report.pdf")).toBeInTheDocument();
  });

  it("calls onFileSelect on drop", () => {
    const onFileSelect = vi.fn();
    render(<DropzoneArea onFileSelect={onFileSelect} selectedFile={null} />);

    const dropzone = screen.getByText("Drop a file here or click to browse")
      .closest("div")!;

    const file = new File(["test"], "test.pdf", { type: "application/pdf" });
    fireEvent.drop(dropzone, {
      dataTransfer: { files: [file] },
    });

    expect(onFileSelect).toHaveBeenCalledWith(file);
  });

  it("applies drag-over styling", () => {
    render(<DropzoneArea onFileSelect={vi.fn()} selectedFile={null} />);

    // The outer dropzone is the border-dashed container
    const dropzone = screen
      .getByText("Drop a file here or click to browse")
      .closest("[class*='border-dashed']")!;

    fireEvent.dragOver(dropzone);
    expect(dropzone.className).toContain("border-primary");
  });

  it("is disabled when disabled prop is true", () => {
    render(
      <DropzoneArea onFileSelect={vi.fn()} selectedFile={null} disabled />
    );

    const dropzone = screen
      .getByText("Drop a file here or click to browse")
      .closest("[class*='border-dashed']")!;
    expect(dropzone.className).toContain("opacity-50");
  });
});
