import { useEffect, useState } from "react";
import { getDocumentDownloadUrl } from "@/lib/api/documents";

interface TextPreviewProps {
  documentId: string;
}

/** Plain text document preview rendered in a scrollable pre block. */
export function TextPreview({ documentId }: TextPreviewProps) {
  const [content, setContent] = useState<string | null>(null);

  useEffect(() => {
    fetch(getDocumentDownloadUrl(documentId))
      .then((res) => res.text())
      .then(setContent)
      .catch(() => setContent(null));
  }, [documentId]);

  if (content === null) {
    return (
      <div className="flex items-center justify-center h-48 text-muted-foreground">
        Loading preview...
      </div>
    );
  }

  return (
    <pre className="max-h-[600px] overflow-auto rounded-lg border bg-muted/30 p-4 text-sm">
      {content}
    </pre>
  );
}
