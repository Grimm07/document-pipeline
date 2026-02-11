import { getDocumentDownloadUrl } from "@/lib/api/documents";

interface ImagePreviewProps {
  documentId: string;
  filename: string;
}

/** Displays a responsive image preview fetched from the document download endpoint. */
export function ImagePreview({ documentId, filename }: ImagePreviewProps) {
  return (
    <div className="flex items-center justify-center rounded-lg border bg-muted/30 p-4">
      <img
        src={getDocumentDownloadUrl(documentId)}
        alt={filename}
        className="max-h-[600px] max-w-full rounded object-contain"
      />
    </div>
  );
}
