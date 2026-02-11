import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { DocumentPreview } from "./document-preview";
import { OcrTextViewer } from "./ocr-text-viewer";
import { BoundingBoxViewer } from "./bounding-box-viewer";

interface DocumentViewerTabsProps {
  documentId: string;
  mimeType: string;
  filename: string;
  hasOcrResults: boolean;
}

/** Tabbed viewer with Preview, OCR Text, and Bounding Boxes tabs when OCR results exist. */
export function DocumentViewerTabs({
  documentId,
  mimeType,
  filename,
  hasOcrResults,
}: DocumentViewerTabsProps) {
  if (!hasOcrResults) {
    return <DocumentPreview documentId={documentId} mimeType={mimeType} filename={filename} />;
  }

  return (
    <Tabs defaultValue="preview">
      <TabsList>
        <TabsTrigger value="preview">Preview</TabsTrigger>
        <TabsTrigger value="ocr-text">OCR Text</TabsTrigger>
        <TabsTrigger value="bboxes">Bounding Boxes</TabsTrigger>
      </TabsList>
      <TabsContent value="preview">
        <DocumentPreview documentId={documentId} mimeType={mimeType} filename={filename} />
      </TabsContent>
      <TabsContent value="ocr-text">
        <OcrTextViewer documentId={documentId} />
      </TabsContent>
      <TabsContent value="bboxes">
        <BoundingBoxViewer documentId={documentId} mimeType={mimeType} />
      </TabsContent>
    </Tabs>
  );
}
