import { useMutation, useQueryClient } from "@tanstack/react-query";
import { retryClassification } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";

/** Hook that retries classification for a document and refreshes related caches. */
export function useRetryClassification(id: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => retryClassification(id),
    onSuccess: (data) => {
      // Update cached document data (triggers polling resumption)
      queryClient.setQueryData(documentKeys.detail(id), data);
      // Remove stale OCR cache
      queryClient.removeQueries({ queryKey: documentKeys.ocr(id) });
      // Invalidate list caches
      queryClient.invalidateQueries({ queryKey: documentKeys.lists() });
    },
  });
}
