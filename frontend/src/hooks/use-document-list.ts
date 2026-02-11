import { useInfiniteQuery } from "@tanstack/react-query";
import { fetchDocuments } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";
import { DEFAULT_PAGE_SIZE } from "@/lib/constants";

/** Hook that fetches a paginated, infinite-scrollable list of documents. */
export function useDocumentList(classification?: string) {
  return useInfiniteQuery({
    queryKey: documentKeys.list({ classification, limit: DEFAULT_PAGE_SIZE }),
    queryFn: ({ pageParam = 0 }) =>
      fetchDocuments({
        classification,
        limit: DEFAULT_PAGE_SIZE,
        offset: pageParam,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => {
      // "has more" heuristic: if we got a full page, there might be more
      if (lastPage.documents.length === DEFAULT_PAGE_SIZE) {
        return lastPage.offset + DEFAULT_PAGE_SIZE;
      }
      return undefined;
    },
  });
}
