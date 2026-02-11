import { Link } from "@tanstack/react-router";
import { Trash2 } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ClassificationBadge } from "./classification-badge";
import { DeleteDocumentDialog } from "./delete-document-dialog";
import { MimeTypeIcon } from "@/components/shared/mime-type-icon";
import { FileSize } from "@/components/shared/file-size";
import { formatRelativeTime } from "@/lib/format";
import type { DocumentResponse } from "@/types/api";

interface DocumentCardProps {
  document: DocumentResponse;
  selectionMode?: boolean;
  selected?: boolean;
  onToggleSelect?: () => void;
}

export function DocumentCard({
  document,
  selectionMode = false,
  selected = false,
  onToggleSelect,
}: DocumentCardProps) {
  const cardContent = (
    <Card
      className={`glass-card transition-all hover:scale-[1.01] hover:shadow-lg ${
        selected ? "ring-2 ring-primary" : ""
      }`}
    >
      <CardContent className="flex items-center gap-4 p-4">
        {selectionMode ? (
          <Checkbox
            checked={selected}
            onCheckedChange={() => onToggleSelect?.()}
            className="size-5 shrink-0"
            // Prevent double-toggle: checkbox handles its own change, stop bubble to parent div
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <MimeTypeIcon mimeType={document.mimeType} className="size-8 shrink-0" />
        )}
        <div className="min-w-0 flex-1">
          <p
            className="truncate font-medium text-foreground"
            title={document.originalFilename}
          >
            {document.originalFilename}
          </p>
          <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
            <FileSize bytes={document.fileSizeBytes} />
            <span>&middot;</span>
            <span>{formatRelativeTime(document.createdAt)}</span>
          </div>
        </div>
        <ClassificationBadge classification={document.classification} />
        {!selectionMode && (
          <div onClick={(e) => { e.preventDefault(); e.stopPropagation(); }}>
            <DeleteDocumentDialog
              documentId={document.id}
              documentName={document.originalFilename}
              trigger={
                <Button variant="ghost" size="icon" className="size-8 text-muted-foreground hover:text-destructive">
                  <Trash2 className="size-4" />
                </Button>
              }
            />
          </div>
        )}
      </CardContent>
    </Card>
  );

  if (selectionMode) {
    return (
      <div
        className="block cursor-pointer"
        onClick={() => onToggleSelect?.()}
        role="button"
        tabIndex={0}
        aria-label={`${selected ? "Deselect" : "Select"} ${document.originalFilename}`}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onToggleSelect?.();
          }
        }}
      >
        {cardContent}
      </div>
    );
  }

  return (
    <Link
      to="/documents/$documentId"
      params={{ documentId: document.id }}
      className="block"
    >
      {cardContent}
    </Link>
  );
}
