import { describe, it, expect } from "vitest";
import { getClassificationColor } from "./classification";

describe("getClassificationColor", () => {
  it("returns color for known classification", () => {
    expect(getClassificationColor("invoice")).toContain("blue");
    expect(getClassificationColor("receipt")).toContain("green");
    expect(getClassificationColor("contract")).toContain("purple");
  });

  it("returns unclassified color", () => {
    expect(getClassificationColor("unclassified")).toContain("yellow");
  });

  it("returns fallback for unknown classification", () => {
    expect(getClassificationColor("foobar")).toContain("gray");
  });

  it("handles uppercase via toLowerCase", () => {
    // The function lowercases input before lookup
    expect(getClassificationColor("INVOICE")).toContain("blue");
  });
});
