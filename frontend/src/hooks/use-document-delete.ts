import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { deleteDocument } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";

/** Hook that deletes a single document and navigates back to the document list. */
export function useDocumentDelete(id: string) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  return useMutation({
    mutationFn: () => deleteDocument(id),
    onSuccess: () => {
      // Remove detail + OCR caches (doc no longer exists)
      queryClient.removeQueries({ queryKey: documentKeys.detail(id) });
      queryClient.removeQueries({ queryKey: documentKeys.ocr(id) });
      // Invalidate list caches so deleted doc disappears
      queryClient.invalidateQueries({ queryKey: documentKeys.lists() });
      navigate({ to: "/documents" });
    },
  });
}
