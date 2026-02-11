import { useQuery } from "@tanstack/react-query";
import { fetchDocument } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";
import {
  CLASSIFICATION_POLL_INTERVAL,
  CLASSIFICATION_POLL_TIMEOUT,
} from "@/lib/constants";

export function useDocumentDetail(id: string) {
  return useQuery({
    queryKey: documentKeys.detail(id),
    queryFn: () => fetchDocument(id),
    refetchInterval: (query) => {
      const doc = query.state.data;
      if (doc?.classification !== "unclassified") return false;

      // Stop polling after timeout — classification likely failed
      const age = Date.now() - new Date(doc.updatedAt).getTime();
      if (age > CLASSIFICATION_POLL_TIMEOUT) return false;

      return CLASSIFICATION_POLL_INTERVAL;
    },
  });
}
