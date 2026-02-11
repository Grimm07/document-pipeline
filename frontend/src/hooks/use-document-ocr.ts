import { useQuery } from "@tanstack/react-query";
import { fetchOcrResults } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";

export function useDocumentOcr(id: string, enabled: boolean = true) {
  return useQuery({
    queryKey: documentKeys.ocr(id),
    queryFn: () => fetchOcrResults(id),
    enabled,
    staleTime: Infinity,
  });
}
