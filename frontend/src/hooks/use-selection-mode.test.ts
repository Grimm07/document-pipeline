import { describe, it, expect } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useSelectionMode } from "./use-selection-mode";

describe("useSelectionMode", () => {
  it("starts in non-selection mode with empty set", () => {
    const { result } = renderHook(() => useSelectionMode());
    expect(result.current.selectionMode).toBe(false);
    expect(result.current.selectedIds.size).toBe(0);
  });

  it("enterSelectionMode enables selection mode", () => {
    const { result } = renderHook(() => useSelectionMode());
    act(() => result.current.enterSelectionMode());
    expect(result.current.selectionMode).toBe(true);
  });

  it("toggle adds and removes IDs", () => {
    const { result } = renderHook(() => useSelectionMode());

    act(() => result.current.toggle("a"));
    expect(result.current.selectedIds.has("a")).toBe(true);
    expect(result.current.selectedIds.size).toBe(1);

    act(() => result.current.toggle("b"));
    expect(result.current.selectedIds.size).toBe(2);

    // Toggle off
    act(() => result.current.toggle("a"));
    expect(result.current.selectedIds.has("a")).toBe(false);
    expect(result.current.selectedIds.size).toBe(1);
  });

  it("selectAll replaces the set with given IDs", () => {
    const { result } = renderHook(() => useSelectionMode());

    act(() => result.current.toggle("x"));
    act(() => result.current.selectAll(["a", "b", "c"]));
    expect(result.current.selectedIds.size).toBe(3);
    expect(result.current.selectedIds.has("x")).toBe(false);
    expect(result.current.selectedIds.has("a")).toBe(true);
  });

  it("deselectAll clears the set but stays in selection mode", () => {
    const { result } = renderHook(() => useSelectionMode());

    act(() => result.current.enterSelectionMode());
    act(() => result.current.toggle("a"));
    act(() => result.current.deselectAll());

    expect(result.current.selectedIds.size).toBe(0);
    expect(result.current.selectionMode).toBe(true);
  });

  it("exitSelectionMode clears IDs and disables selection mode", () => {
    const { result } = renderHook(() => useSelectionMode());

    act(() => result.current.enterSelectionMode());
    act(() => result.current.toggle("a"));
    act(() => result.current.toggle("b"));
    act(() => result.current.exitSelectionMode());

    expect(result.current.selectionMode).toBe(false);
    expect(result.current.selectedIds.size).toBe(0);
  });
});
