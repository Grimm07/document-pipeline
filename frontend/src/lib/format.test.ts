import { describe, it, expect } from "vitest";
import { formatFileSize, formatDate, formatConfidence } from "./format";

describe("formatFileSize", () => {
  it("formats zero bytes", () => {
    expect(formatFileSize(0)).toBe("0 B");
  });

  it("formats bytes", () => {
    expect(formatFileSize(512)).toBe("512 B");
  });

  it("formats kilobytes", () => {
    expect(formatFileSize(1024)).toBe("1.0 KB");
    expect(formatFileSize(1536)).toBe("1.5 KB");
  });

  it("formats megabytes", () => {
    expect(formatFileSize(1048576)).toBe("1.0 MB");
    expect(formatFileSize(5242880)).toBe("5.0 MB");
  });

  it("formats gigabytes", () => {
    expect(formatFileSize(1073741824)).toBe("1.0 GB");
  });

  it("handles negative numbers", () => {
    expect(formatFileSize(-1)).toBe("0 B");
  });
});

describe("formatDate", () => {
  it("formats a valid ISO date", () => {
    const result = formatDate("2025-01-15T10:30:00Z");
    expect(result).not.toBe("-");
    expect(result.length).toBeGreaterThan(0);
  });

  it("returns dash for null", () => {
    expect(formatDate(null)).toBe("-");
  });

  it("returns dash for undefined", () => {
    expect(formatDate(undefined)).toBe("-");
  });

  it("returns dash for invalid date", () => {
    expect(formatDate("not-a-date")).toBe("-");
  });
});

describe("formatConfidence", () => {
  it("returns Pending for null", () => {
    expect(formatConfidence(null)).toBe("Pending");
  });

  it("formats 0 as 0%", () => {
    expect(formatConfidence(0)).toBe("0%");
  });

  it("formats 0.95 as 95%", () => {
    expect(formatConfidence(0.95)).toBe("95%");
  });

  it("formats 1.0 as 100%", () => {
    expect(formatConfidence(1.0)).toBe("100%");
  });
});
