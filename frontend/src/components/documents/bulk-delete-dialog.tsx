import { useState } from "react";
import { Loader2, Trash2 } from "lucide-react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";

interface BulkDeleteDialogProps {
  selectedCount: number;
  onConfirm: () => void;
  isPending: boolean;
}

/** Confirmation dialog for bulk-deleting selected documents. */
export function BulkDeleteDialog({ selectedCount, onConfirm, isPending }: BulkDeleteDialogProps) {
  const [open, setOpen] = useState(false);
  const plural = selectedCount !== 1 ? "s" : "";

  function handleConfirm(): void {
    onConfirm();
    setOpen(false);
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger asChild>
        <Button variant="destructive" size="sm" disabled={selectedCount === 0}>
          <Trash2 className="size-4 mr-1.5" />
          Delete ({selectedCount})
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            Delete {selectedCount} document{plural}?
          </AlertDialogTitle>
          <AlertDialogDescription>
            This will permanently delete {selectedCount} document{plural} and all associated files.
            This action cannot be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={isPending}>Cancel</AlertDialogCancel>
          <Button variant="destructive" onClick={handleConfirm} disabled={isPending}>
            {isPending && <Loader2 className="size-4 mr-2 animate-spin" />}
            Delete
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
