import { useQuery } from "@tanstack/react-query";
import { fetchOcrResults } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";

/** Hook that fetches OCR results for a document with infinite stale time. */
export function useDocumentOcr(id: string, enabled: boolean = true) {
  return useQuery({
    queryKey: documentKeys.ocr(id),
    queryFn: () => fetchOcrResults(id),
    enabled,
    staleTime: Infinity,
  });
}
