import { useMutation, useQueryClient } from "@tanstack/react-query";
import { deleteDocument } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";

/** Result summary from a bulk delete operation. */
export interface BulkDeleteResult {
  successCount: number;
  failureCount: number;
}

/** Hook that deletes multiple documents in parallel and reports per-result outcomes. */
export function useBulkDelete(onComplete: (result: BulkDeleteResult) => void) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (ids: string[]) => Promise.allSettled(ids.map((id) => deleteDocument(id))),
    onSuccess: (results, ids) => {
      const succeeded = results
        .map((r, i) => ({ status: r.status, id: ids[i]! }))
        .filter((r) => r.status === "fulfilled");

      for (const { id } of succeeded) {
        queryClient.removeQueries({ queryKey: documentKeys.detail(id) });
        queryClient.removeQueries({ queryKey: documentKeys.ocr(id) });
      }

      if (succeeded.length > 0) {
        queryClient.invalidateQueries({ queryKey: documentKeys.lists() });
      }

      onComplete({
        successCount: succeeded.length,
        failureCount: results.length - succeeded.length,
      });
    },
  });
}
