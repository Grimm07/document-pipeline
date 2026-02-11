import { useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { ClassificationBadge } from "./classification-badge";
import { useLabelCorrection } from "@/hooks/use-label-correction";
import { getClassificationColor } from "@/lib/classification";
import { CLASSIFICATIONS } from "@/lib/constants";
import { cn } from "@/lib/utils";

interface LabelCorrectionPopoverProps {
  documentId: string;
  classification: string;
  labelScores: Record<string, number> | null;
  classificationSource: string;
  disabled?: boolean;
}

/** Popover showing label scores with click-to-correct functionality. */
export function LabelCorrectionPopover({
  documentId,
  classification,
  labelScores,
  classificationSource,
  disabled = false,
}: LabelCorrectionPopoverProps) {
  const [popoverOpen, setPopoverOpen] = useState(false);
  const [confirmLabel, setConfirmLabel] = useState<string | null>(null);
  const confirmLabelRef = useRef<string | null>(null);
  const mutation = useLabelCorrection(documentId);

  const sortedLabels = labelScores
    ? Object.entries(labelScores).sort(([, a], [, b]) => b - a)
    : CLASSIFICATIONS.map((label) => [label, null] as const);

  function handleSelect(label: string) {
    if (label === classification) return;
    confirmLabelRef.current = label;
    setConfirmLabel(label);
  }

  function handleConfirm() {
    const label = confirmLabelRef.current;
    if (!label) return;
    mutation.mutate(label, {
      onSettled: () => {
        confirmLabelRef.current = null;
        setConfirmLabel(null);
        setPopoverOpen(false);
      },
    });
  }

  return (
    <>
      <Popover open={popoverOpen} onOpenChange={setPopoverOpen}>
        <PopoverTrigger asChild disabled={disabled}>
          <button type="button" className="cursor-pointer" disabled={disabled}>
            <ClassificationBadge
              classification={classification}
              classificationSource={classificationSource}
            />
          </button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-64 p-2">
          <p className="text-xs font-medium text-muted-foreground px-2 py-1">
            Click a label to correct
          </p>
          <div className="space-y-0.5">
            {sortedLabels.map(([label, score]) => {
              const isCurrent = label === classification;
              return (
                <button
                  key={label}
                  type="button"
                  onClick={() => handleSelect(label)}
                  disabled={isCurrent || mutation.isPending}
                  className={cn(
                    "flex w-full items-center justify-between rounded-md px-2 py-1.5 text-sm",
                    "hover:bg-accent hover:text-accent-foreground",
                    "disabled:opacity-50 disabled:pointer-events-none",
                    isCurrent && "bg-accent/50 font-medium",
                  )}
                >
                  <span className={cn("truncate", getClassificationColor(label))}>{label}</span>
                  {score !== null && (
                    <span className="ml-2 shrink-0 text-xs text-muted-foreground tabular-nums">
                      {(score * 100).toFixed(1)}%
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </PopoverContent>
      </Popover>

      <AlertDialog
        open={confirmLabel !== null}
        onOpenChange={(open) => !open && setConfirmLabel(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Correct classification?</AlertDialogTitle>
            <AlertDialogDescription>
              Change classification to &ldquo;{confirmLabel}&rdquo;? This will be saved to the
              database and marked as a manual correction.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={mutation.isPending}>Cancel</AlertDialogCancel>
            <Button onClick={handleConfirm} disabled={mutation.isPending}>
              {mutation.isPending && <Loader2 className="size-4 mr-2 animate-spin" />}
              Confirm
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
