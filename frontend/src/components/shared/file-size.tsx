import { formatFileSize } from "@/lib/format";

/** Renders a human-readable file size from a byte count. */
export function FileSize({ bytes }: { bytes: number }) {
  return <span className="text-muted-foreground">{formatFileSize(bytes)}</span>;
}
