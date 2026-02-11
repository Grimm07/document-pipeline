import { Badge } from "@/components/ui/badge";
import { getClassificationColor } from "@/lib/classification";
import { cn } from "@/lib/utils";

interface ClassificationBadgeProps {
  classification: string;
  className?: string;
}

export function ClassificationBadge({
  classification,
  className,
}: ClassificationBadgeProps) {
  const isUnclassified = classification === "unclassified";

  return (
    <Badge
      variant="secondary"
      className={cn(
        getClassificationColor(classification),
        isUnclassified && "animate-pulse",
        className
      )}
    >
      {classification}
    </Badge>
  );
}
