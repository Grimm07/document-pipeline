import { Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { MetadataEntry } from "@/types/api";

interface MetadataFieldListProps {
  entries: MetadataEntry[];
  onChange: (entries: MetadataEntry[]) => void;
  disabled?: boolean;
}

/** Editable list of key-value metadata fields for document uploads. */
export function MetadataFieldList({ entries, onChange, disabled }: MetadataFieldListProps) {
  const addEntry = () => {
    onChange([...entries, { key: "", value: "" }]);
  };

  const removeEntry = (index: number) => {
    onChange(entries.filter((_, i) => i !== index));
  };

  const updateEntry = (index: number, field: "key" | "value", newValue: string) => {
    const updated = entries.map((entry, i) =>
      i === index ? { ...entry, [field]: newValue } : entry,
    );
    onChange(updated);
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium">Metadata</label>
        <Button type="button" variant="ghost" size="sm" onClick={addEntry} disabled={disabled}>
          <Plus className="size-4 mr-1" />
          Add Field
        </Button>
      </div>
      {entries.length === 0 && (
        <p className="text-xs text-muted-foreground">
          No metadata fields. Click &quot;Add Field&quot; to add key-value pairs.
        </p>
      )}
      {entries.map((entry, index) => (
        <div key={index} className="flex items-center gap-2">
          <Input
            placeholder="Key"
            value={entry.key}
            onChange={(e) => updateEntry(index, "key", e.target.value)}
            disabled={disabled}
            className="flex-1"
          />
          <Input
            placeholder="Value"
            value={entry.value}
            onChange={(e) => updateEntry(index, "value", e.target.value)}
            disabled={disabled}
            className="flex-1"
          />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={() => removeEntry(index)}
            disabled={disabled}
          >
            <X className="size-4" />
          </Button>
        </div>
      ))}
    </div>
  );
}
