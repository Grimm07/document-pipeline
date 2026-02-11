import { useState } from "react";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { CheckSquare, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DocumentCard } from "@/components/documents/document-card";
import { ClassificationBadge } from "@/components/documents/classification-badge";
import { BulkDeleteDialog } from "@/components/documents/bulk-delete-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { LoadingSpinner } from "@/components/shared/loading-spinner";
import { ErrorDisplay } from "@/components/shared/error-display";
import { useDocumentList } from "@/hooks/use-document-list";
import { useSelectionMode } from "@/hooks/use-selection-mode";
import { useBulkDelete, type BulkDeleteResult } from "@/hooks/use-bulk-delete";
import { CLASSIFICATIONS } from "@/lib/constants";

interface SearchParams {
  classification?: string;
}

export const Route = createFileRoute("/documents/")({
  component: DocumentListPage,
  validateSearch: (search: Record<string, unknown>): SearchParams => ({
    classification: typeof search.classification === "string" ? search.classification : undefined,
  }),
});

function DocumentListPage() {
  const { classification } = Route.useSearch();
  const navigate = useNavigate();
  const {
    data,
    isLoading,
    isError,
    error,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    refetch,
  } = useDocumentList(classification);

  const documents = data?.pages.flatMap((page) => page.documents) ?? [];

  const {
    selectionMode,
    selectedIds,
    enterSelectionMode,
    exitSelectionMode,
    toggle,
    selectAll,
    deselectAll,
  } = useSelectionMode();

  const [deleteStatus, setDeleteStatus] = useState<string | null>(null);

  function handleDeleteComplete(result: BulkDeleteResult): void {
    if (result.failureCount === 0) {
      exitSelectionMode();
      setDeleteStatus(null);
    } else if (result.successCount === 0) {
      setDeleteStatus(`Failed to delete all ${result.failureCount} documents.`);
    } else {
      setDeleteStatus(`Deleted ${result.successCount}, failed ${result.failureCount}.`);
      deselectAll();
    }
  }

  const { mutate: bulkDelete, isPending: isDeleting } = useBulkDelete(handleDeleteComplete);

  function setClassification(value: string | undefined): void {
    exitSelectionMode();
    navigate({
      to: "/documents",
      search: value ? { classification: value } : {},
    });
  }

  // Count only IDs that are still visible in the current document list
  const visibleSelectedCount = documents.filter((d) => selectedIds.has(d.id)).length;

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Documents</h1>
          <p className="text-muted-foreground mt-1">Browse and filter uploaded documents</p>
        </div>

        {/* Selection toolbar */}
        {documents.length > 0 && (
          <div className="flex items-center gap-2">
            {selectionMode ? (
              <>
                <span className="text-sm text-muted-foreground whitespace-nowrap">
                  {visibleSelectedCount} selected
                </span>
                <Button
                  variant="link"
                  size="sm"
                  className="h-auto p-0 text-xs"
                  onClick={() => selectAll(documents.map((d) => d.id))}
                  disabled={isDeleting}
                >
                  Select all
                </Button>
                <Button
                  variant="link"
                  size="sm"
                  className="h-auto p-0 text-xs"
                  onClick={deselectAll}
                  disabled={visibleSelectedCount === 0 || isDeleting}
                >
                  Deselect all
                </Button>
                <BulkDeleteDialog
                  selectedCount={visibleSelectedCount}
                  onConfirm={() => {
                    setDeleteStatus(null);
                    bulkDelete(Array.from(selectedIds));
                  }}
                  isPending={isDeleting}
                />
                <Button variant="ghost" size="sm" onClick={exitSelectionMode} disabled={isDeleting}>
                  <X className="size-4 mr-1" />
                  Cancel
                </Button>
              </>
            ) : (
              <Button variant="outline" size="sm" onClick={enterSelectionMode}>
                <CheckSquare className="size-4 mr-1.5" />
                Select
              </Button>
            )}
          </div>
        )}
      </div>

      {/* Delete status message */}
      {deleteStatus && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 px-4 py-2 text-sm text-destructive">
          {deleteStatus}
        </div>
      )}

      {/* Classification Filter */}
      <div className="flex flex-wrap gap-2">
        <Button
          variant={!classification ? "default" : "outline"}
          size="sm"
          onClick={() => setClassification(undefined)}
        >
          All
        </Button>
        {CLASSIFICATIONS.map((c) => (
          <Button
            key={c}
            variant={classification === c ? "default" : "outline"}
            size="sm"
            onClick={() => setClassification(c)}
          >
            <ClassificationBadge classification={c} className="pointer-events-none" />
          </Button>
        ))}
        <Button
          variant={classification === "unclassified" ? "default" : "outline"}
          size="sm"
          onClick={() => setClassification("unclassified")}
        >
          <ClassificationBadge classification="unclassified" className="pointer-events-none" />
        </Button>
      </div>

      {/* Document Grid */}
      {isLoading ? (
        <LoadingSpinner />
      ) : isError ? (
        <ErrorDisplay message={error?.message} onRetry={() => refetch()} />
      ) : documents.length === 0 ? (
        <EmptyState
          title={classification ? `No ${classification} documents` : undefined}
          description={classification ? "Try selecting a different classification." : undefined}
        />
      ) : (
        <div className="grid gap-3">
          {documents.map((doc) => (
            <DocumentCard
              key={doc.id}
              document={doc}
              selectionMode={selectionMode}
              selected={selectedIds.has(doc.id)}
              onToggleSelect={() => toggle(doc.id)}
            />
          ))}
        </div>
      )}

      {/* Load More */}
      {hasNextPage && (
        <div className="flex justify-center pt-4">
          <Button variant="outline" onClick={() => fetchNextPage()} disabled={isFetchingNextPage}>
            {isFetchingNextPage ? "Loading..." : "Load More"}
          </Button>
        </div>
      )}
    </div>
  );
}
