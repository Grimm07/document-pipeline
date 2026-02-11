import { useEffect, useState } from "react";
import { getDocumentDownloadUrl } from "@/lib/api/documents";

interface PdfPreviewProps {
  documentId: string;
}

/** Simple PDF preview using a browser iframe with blob URL. */
export function PdfPreview({ documentId }: PdfPreviewProps) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);

  useEffect(() => {
    const url = getDocumentDownloadUrl(documentId);
    fetch(url)
      .then((res) => res.blob())
      .then((blob) => {
        const objectUrl = URL.createObjectURL(blob);
        setBlobUrl(objectUrl);
      })
      .catch(() => setBlobUrl(null));

    return () => {
      if (blobUrl) URL.revokeObjectURL(blobUrl);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId]);

  if (!blobUrl) {
    return (
      <div className="flex items-center justify-center h-96 text-muted-foreground">
        Loading preview...
      </div>
    );
  }

  return (
    <iframe src={blobUrl} className="h-[600px] w-full rounded-lg border" title="PDF Preview" />
  );
}
