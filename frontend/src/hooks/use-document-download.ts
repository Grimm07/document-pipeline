import { useCallback } from "react";
import { getDocumentDownloadUrl } from "@/lib/api/documents";

/** Hook that provides a callback to trigger a browser file download for a document. */
export function useDocumentDownload(id: string, filename: string) {
  const download = useCallback(() => {
    const link = document.createElement("a");
    link.href = getDocumentDownloadUrl(id);
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }, [id, filename]);

  return { download };
}
