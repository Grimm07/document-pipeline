import { formatFileSize } from "@/lib/format";

export function FileSize({ bytes }: { bytes: number }) {
  return <span className="text-muted-foreground">{formatFileSize(bytes)}</span>;
}
