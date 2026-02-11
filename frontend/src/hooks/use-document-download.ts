import { useCallback } from "react";
import { getDocumentDownloadUrl } from "@/lib/api/documents";

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
