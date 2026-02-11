import { useCallback, useState, useRef } from "react";
import { Upload } from "lucide-react";
import { cn } from "@/lib/utils";
import { ACCEPTED_MIME_TYPES } from "@/lib/constants";

interface DropzoneAreaProps {
  onFileSelect: (file: File) => void;
  selectedFile: File | null;
  disabled?: boolean;
}

/** Drag-and-drop file selection area with click-to-browse fallback. */
export function DropzoneArea({ onFileSelect, selectedFile, disabled }: DropzoneAreaProps) {
  const [isDragOver, setIsDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setIsDragOver(false);
      if (disabled) return;
      const file = e.dataTransfer.files[0];
      if (file) onFileSelect(file);
    },
    [onFileSelect, disabled],
  );

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file) onFileSelect(file);
    },
    [onFileSelect],
  );

  return (
    <div
      className={cn(
        "glass-card flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 transition-colors",
        isDragOver && "border-primary bg-primary/5",
        !isDragOver && "border-muted-foreground/25 hover:border-muted-foreground/50",
        disabled && "cursor-not-allowed opacity-50",
      )}
      onDragOver={(e) => {
        e.preventDefault();
        if (!disabled) setIsDragOver(true);
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={handleDrop}
      onClick={() => !disabled && inputRef.current?.click()}
    >
      <Upload className="size-10 text-muted-foreground mb-3" />
      {selectedFile ? (
        <div className="text-center">
          <p className="font-medium">{selectedFile.name}</p>
          <p className="text-sm text-muted-foreground mt-1">Click or drag to replace</p>
        </div>
      ) : (
        <div className="text-center">
          <p className="font-medium">Drop a file here or click to browse</p>
          <p className="text-sm text-muted-foreground mt-1">
            PDF, images, or text files up to 50MB
          </p>
        </div>
      )}
      <input
        ref={inputRef}
        type="file"
        className="hidden"
        accept={ACCEPTED_MIME_TYPES.join(",")}
        onChange={handleChange}
        disabled={disabled}
      />
    </div>
  );
}
