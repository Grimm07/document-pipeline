import { useState } from "react";
import { createFileRoute } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { DropzoneArea } from "@/components/upload/dropzone-area";
import { MetadataFieldList } from "@/components/upload/metadata-field-list";
import { useDocumentUpload } from "@/hooks/use-document-upload";
import { ACCEPTED_MIME_TYPES, MAX_FILE_SIZE } from "@/lib/constants";
import { formatFileSize } from "@/lib/format";
import type { MetadataEntry } from "@/types/api";

export const Route = createFileRoute("/upload")({
  component: UploadPage,
});

function UploadPage() {
  const [file, setFile] = useState<File | null>(null);
  const [metadata, setMetadata] = useState<MetadataEntry[]>([]);
  const [validationError, setValidationError] = useState<string | null>(null);
  const { upload, progress, isUploading, error, reset } = useDocumentUpload();

  const validateAndSetFile = (f: File) => {
    setValidationError(null);
    reset();

    if (!ACCEPTED_MIME_TYPES.includes(f.type as (typeof ACCEPTED_MIME_TYPES)[number])) {
      setValidationError(`Unsupported file type: ${f.type || "unknown"}`);
      return;
    }
    if (f.size > MAX_FILE_SIZE) {
      setValidationError(
        `File too large (${formatFileSize(f.size)}). Maximum is ${formatFileSize(MAX_FILE_SIZE)}.`
      );
      return;
    }
    if (f.size === 0) {
      setValidationError("File is empty.");
      return;
    }
    setFile(f);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || isUploading) return;

    const metadataMap: Record<string, string> = {};
    for (const entry of metadata) {
      if (entry.key.trim()) {
        metadataMap[entry.key.trim()] = entry.value;
      }
    }
    upload({ file, metadata: metadataMap });
  };

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Upload Document</h1>
        <p className="text-muted-foreground mt-1">
          Upload a document for classification
        </p>
      </div>

      <form onSubmit={handleSubmit}>
        <Card className="glass-card">
          <CardHeader>
            <CardTitle>Select File</CardTitle>
          </CardHeader>
          <CardContent className="space-y-6">
            <DropzoneArea
              onFileSelect={validateAndSetFile}
              selectedFile={file}
              disabled={isUploading}
            />

            {validationError && (
              <p className="text-sm text-destructive">{validationError}</p>
            )}

            <MetadataFieldList
              entries={metadata}
              onChange={setMetadata}
              disabled={isUploading}
            />

            {isUploading && (
              <div className="space-y-2">
                <Progress value={progress} />
                <p className="text-sm text-muted-foreground text-center">
                  Uploading... {progress}%
                </p>
              </div>
            )}

            {error && (
              <p className="text-sm text-destructive">{error.message}</p>
            )}

            <Button
              type="submit"
              className="w-full"
              disabled={!file || isUploading || !!validationError}
            >
              {isUploading ? "Uploading..." : "Upload Document"}
            </Button>
          </CardContent>
        </Card>
      </form>
    </div>
  );
}
