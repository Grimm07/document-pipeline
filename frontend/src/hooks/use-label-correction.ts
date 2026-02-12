import { useMutation, useQueryClient } from "@tanstack/react-query";
import { correctClassification } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";

/** Hook that corrects a document's classification and refreshes related caches. */
export function useLabelCorrection(id: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (classification: string) => correctClassification(id, classification),
    onSuccess: (data) => {
      queryClient.setQueryData(documentKeys.detail(id), data);
      queryClient.invalidateQueries({ queryKey: documentKeys.lists() });
      queryClient.invalidateQueries({ queryKey: documentKeys.detail(id) });
    },
  });
}
