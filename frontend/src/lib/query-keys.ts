import type { DocumentListFilters } from "@/types/api";

/** TanStack Query key factory for document-related queries. */
export const documentKeys = {
  all: ["documents"] as const,
  lists: () => [...documentKeys.all, "list"] as const,
  list: (filters: DocumentListFilters) => [...documentKeys.lists(), filters] as const,
  details: () => [...documentKeys.all, "detail"] as const,
  detail: (id: string) => [...documentKeys.details(), id] as const,
  ocr: (id: string) => [...documentKeys.all, "ocr", id] as const,
};
