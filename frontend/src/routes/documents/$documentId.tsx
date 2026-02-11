import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeft, Download, Loader2, AlertTriangle, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { ClassificationBadge } from "@/components/documents/classification-badge";
import { DeleteDocumentDialog } from "@/components/documents/delete-document-dialog";
import { DocumentViewerTabs } from "@/components/preview/document-viewer-tabs";
import { LoadingSpinner } from "@/components/shared/loading-spinner";
import { ErrorDisplay } from "@/components/shared/error-display";
import { useDocumentDetail } from "@/hooks/use-document-detail";
import { useDocumentDownload } from "@/hooks/use-document-download";
import { useRetryClassification } from "@/hooks/use-retry-classification";
import { formatFileSize, formatDate, formatConfidence } from "@/lib/format";
import { CLASSIFICATION_POLL_TIMEOUT } from "@/lib/constants";

export const Route = createFileRoute("/documents/$documentId")({
  component: DocumentDetailPage,
});

function DocumentDetailPage() {
  const { documentId } = Route.useParams();
  const { data: doc, isLoading, isError, error, refetch } = useDocumentDetail(documentId);
  const { download } = useDocumentDownload(documentId, doc?.originalFilename ?? "download");
  const retryMutation = useRetryClassification(documentId);

  if (isLoading) return <LoadingSpinner />;
  if (isError) {
    return <ErrorDisplay message={error?.message} onRetry={() => refetch()} />;
  }
  if (!doc) return <ErrorDisplay message="Document not found" />;

  const isUnclassified = doc.classification === "unclassified";
  const age = Date.now() - new Date(doc.updatedAt).getTime();
  const isTimedOut = isUnclassified && age > CLASSIFICATION_POLL_TIMEOUT;
  const isClassifying = isUnclassified && !isTimedOut;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <Link to="/documents">
          <Button variant="ghost" size="icon">
            <ArrowLeft className="size-4" />
          </Button>
        </Link>
        <div className="flex-1 min-w-0">
          <h1 className="text-2xl font-bold tracking-tight truncate" title={doc.originalFilename}>
            {doc.originalFilename}
          </h1>
          <p className="text-muted-foreground text-sm mt-1">Uploaded {formatDate(doc.createdAt)}</p>
        </div>
        <div className="flex items-center gap-2">
          <Button onClick={download} variant="outline">
            <Download className="size-4 mr-2" />
            Download
          </Button>
          <DeleteDocumentDialog
            documentId={doc.id}
            documentName={doc.originalFilename}
            trigger={
              <Button variant="outline" className="text-destructive hover:text-destructive">
                <Trash2 className="size-4 mr-2" />
                Delete
              </Button>
            }
          />
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Metadata Panel */}
        <Card className="glass-card lg:col-span-1">
          <CardHeader>
            <CardTitle className="text-base">Details</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <DetailRow label="Classification">
              <div className="flex flex-col items-end gap-1">
                <div className="flex items-center gap-2">
                  <ClassificationBadge classification={doc.classification} />
                  {isClassifying && (
                    <span className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Loader2 className="size-3 animate-spin" />
                      Processing...
                    </span>
                  )}
                  {isTimedOut && (
                    <span className="flex items-center gap-1 text-xs text-destructive">
                      <AlertTriangle className="size-3" />
                      Failed
                    </span>
                  )}
                </div>
                {isTimedOut && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 px-2 text-xs"
                    onClick={() => retryMutation.mutate()}
                    disabled={retryMutation.isPending}
                  >
                    {retryMutation.isPending ? (
                      <Loader2 className="size-3 mr-1 animate-spin" />
                    ) : (
                      <RefreshCw className="size-3 mr-1" />
                    )}
                    Retry classification
                  </Button>
                )}
              </div>
            </DetailRow>
            <DetailRow label="Confidence">{formatConfidence(doc.confidence)}</DetailRow>
            <Separator />
            <DetailRow label="MIME Type">{doc.mimeType}</DetailRow>
            <DetailRow label="Size">{formatFileSize(doc.fileSizeBytes)}</DetailRow>
            <DetailRow label="Updated">{formatDate(doc.updatedAt)}</DetailRow>
            {doc.uploadedBy && <DetailRow label="Uploaded By">{doc.uploadedBy}</DetailRow>}

            {Object.keys(doc.metadata).length > 0 && (
              <>
                <Separator />
                <p className="text-sm font-medium">Metadata</p>
                {Object.entries(doc.metadata).map(([key, value]) => (
                  <DetailRow key={key} label={key}>
                    {value}
                  </DetailRow>
                ))}
              </>
            )}
            {Object.keys(doc.metadata).length === 0 && (
              <>
                <Separator />
                <p className="text-xs text-muted-foreground">No metadata</p>
              </>
            )}
          </CardContent>
        </Card>

        {/* Preview */}
        <div className="lg:col-span-2">
          <DocumentViewerTabs
            documentId={doc.id}
            mimeType={doc.mimeType}
            filename={doc.originalFilename}
            hasOcrResults={doc.hasOcrResults}
          />
        </div>
      </div>
    </div>
  );
}

function DetailRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-2 text-sm">
      <span className="text-muted-foreground shrink-0">{label}</span>
      <span className="text-right font-medium truncate">{children}</span>
    </div>
  );
}
