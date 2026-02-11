import { JsonView, allExpanded, darkStyles, defaultStyles } from "react-json-view-lite";
import "react-json-view-lite/dist/index.css";
import { useDocumentOcr } from "@/hooks/use-document-ocr";
import { useTheme } from "@/components/shared/theme-provider";
import { Loader2 } from "lucide-react";

interface OcrTextViewerProps {
  documentId: string;
}

/** Displays extracted OCR text and full OCR result details as a JSON tree. */
export function OcrTextViewer({ documentId }: OcrTextViewerProps) {
  const { data: ocr, isLoading, isError, error } = useDocumentOcr(documentId);
  const { theme } = useTheme();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-96 text-muted-foreground gap-2">
        <Loader2 className="size-4 animate-spin" />
        Loading OCR results...
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex items-center justify-center h-96 text-destructive">
        Failed to load OCR results: {error?.message}
      </div>
    );
  }

  if (!ocr) {
    return (
      <div className="flex items-center justify-center h-96 text-muted-foreground">
        No OCR results available
      </div>
    );
  }

  const isDark =
    theme === "dark" ||
    (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);

  return (
    <div className="space-y-4">
      {/* Full text summary */}
      <div className="rounded-lg border p-4">
        <h3 className="text-sm font-medium mb-2">Extracted Text</h3>
        <pre className="text-sm whitespace-pre-wrap text-muted-foreground max-h-48 overflow-auto">
          {ocr.fullText || "(No text extracted)"}
        </pre>
      </div>

      {/* Full OCR result as JSON tree */}
      <div className="rounded-lg border p-4 max-h-[400px] overflow-auto">
        <h3 className="text-sm font-medium mb-2">
          OCR Details ({ocr.pages.length} page{ocr.pages.length !== 1 ? "s" : ""})
        </h3>
        <JsonView
          data={ocr as unknown as object}
          shouldExpandNode={allExpanded}
          style={isDark ? darkStyles : defaultStyles}
        />
      </div>
    </div>
  );
}
