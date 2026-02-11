import { Pencil } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { getClassificationColor } from "@/lib/classification";
import { cn } from "@/lib/utils";

interface ClassificationBadgeProps {
  classification: string;
  classificationSource?: string;
  className?: string;
}

/** Colored badge displaying a document's classification label, with manual indicator. */
export function ClassificationBadge({
  classification,
  classificationSource,
  className,
}: ClassificationBadgeProps) {
  const isUnclassified = classification === "unclassified";
  const isManual = classificationSource === "manual";

  return (
    <Badge
      variant="secondary"
      className={cn(
        getClassificationColor(classification),
        isUnclassified && "animate-pulse",
        className,
      )}
    >
      {isManual && <Pencil className="size-3 mr-1" />}
      {classification}
    </Badge>
  );
}
