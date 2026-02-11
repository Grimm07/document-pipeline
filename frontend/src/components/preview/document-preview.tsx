import { Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { PdfDeepZoomPreview } from "./pdf-deep-zoom-preview";
import { ImagePreview } from "./image-preview";
import { TextPreview } from "./text-preview";
import { JsonPreview } from "./json-preview";
import { XmlPreview } from "./xml-preview";
import { getDocumentDownloadUrl } from "@/lib/api/documents";

interface DocumentPreviewProps {
  documentId: string;
  mimeType: string;
  filename: string;
}

/** Renders the appropriate preview component based on the document MIME type. */
export function DocumentPreview({ documentId, mimeType, filename }: DocumentPreviewProps) {
  if (mimeType === "application/json") {
    return <JsonPreview documentId={documentId} />;
  }

  if (mimeType === "application/xml" || mimeType === "text/xml") {
    return <XmlPreview documentId={documentId} />;
  }

  if (mimeType === "application/pdf") {
    return <PdfDeepZoomPreview documentId={documentId} />;
  }

  if (mimeType.startsWith("image/")) {
    return <ImagePreview documentId={documentId} filename={filename} />;
  }

  if (mimeType.startsWith("text/")) {
    return <TextPreview documentId={documentId} />;
  }

  // Unsupported preview — offer download
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed p-12 text-center">
      <p className="text-muted-foreground mb-4">Preview not available for {mimeType}</p>
      <a href={getDocumentDownloadUrl(documentId)} download={filename}>
        <Button variant="outline">
          <Download className="size-4 mr-2" />
          Download File
        </Button>
      </a>
    </div>
  );
}
