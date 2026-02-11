import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MetadataFieldList } from "./metadata-field-list";

describe("MetadataFieldList", () => {
  it("renders empty state message", () => {
    render(<MetadataFieldList entries={[]} onChange={vi.fn()} />);
    expect(screen.getByText(/No metadata fields/)).toBeInTheDocument();
  });

  it("renders existing entries", () => {
    const entries = [
      { key: "dept", value: "finance" },
      { key: "year", value: "2025" },
    ];
    render(<MetadataFieldList entries={entries} onChange={vi.fn()} />);

    const inputs = screen.getAllByRole("textbox");
    expect(inputs).toHaveLength(4); // 2 key + 2 value
  });

  it("calls onChange when Add Field is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<MetadataFieldList entries={[]} onChange={onChange} />);

    await user.click(screen.getByText("Add Field"));
    expect(onChange).toHaveBeenCalledWith([{ key: "", value: "" }]);
  });

  it("calls onChange when remove button is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const entries = [{ key: "dept", value: "finance" }];
    render(<MetadataFieldList entries={entries} onChange={onChange} />);

    const removeButtons = screen
      .getAllByRole("button")
      .filter((b) => b.textContent !== "Add Field");
    await user.click(removeButtons[0]!);
    expect(onChange).toHaveBeenCalledWith([]);
  });
});
