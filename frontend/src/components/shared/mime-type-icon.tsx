import { FileText, Image, FileSpreadsheet, File } from "lucide-react";
import { cn } from "@/lib/utils";

const ICON_MAP: Record<string, typeof FileText> = {
  "application/pdf": FileText,
  "image/png": Image,
  "image/jpeg": Image,
  "image/gif": Image,
  "image/webp": Image,
  "text/plain": FileText,
  "text/csv": FileSpreadsheet,
};

/** Renders a Lucide icon appropriate for the given MIME type. */
export function MimeTypeIcon({ mimeType, className }: { mimeType: string; className?: string }) {
  const Icon = ICON_MAP[mimeType] ?? File;
  return <Icon className={cn("size-5 text-muted-foreground", className)} />;
}
