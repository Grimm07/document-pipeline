const CLASSIFICATION_COLORS: Record<string, string> = {
  invoice: "bg-blue-500/15 text-blue-700 dark:text-blue-400",
  receipt: "bg-green-500/15 text-green-700 dark:text-green-400",
  contract: "bg-purple-500/15 text-purple-700 dark:text-purple-400",
  report: "bg-orange-500/15 text-orange-700 dark:text-orange-400",
  letter: "bg-teal-500/15 text-teal-700 dark:text-teal-400",
  form: "bg-pink-500/15 text-pink-700 dark:text-pink-400",
  other: "bg-gray-500/15 text-gray-700 dark:text-gray-400",
  unclassified: "bg-yellow-500/15 text-yellow-700 dark:text-yellow-400",
};

export function getClassificationColor(classification: string): string {
  return (
    CLASSIFICATION_COLORS[classification.toLowerCase()] ??
    CLASSIFICATION_COLORS["other"]!
  );
}
