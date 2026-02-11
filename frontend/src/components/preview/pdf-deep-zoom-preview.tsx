import { useEffect, useRef, useState, useCallback } from "react";
import * as pdfjsLib from "pdfjs-dist";
import pdfjsWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import OpenSeadragon from "openseadragon";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getDocumentDownloadUrl } from "@/lib/api/documents";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorker;

interface PdfDeepZoomPreviewProps {
  documentId: string;
}

export function PdfDeepZoomPreview({ documentId }: PdfDeepZoomPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null);
  const [pageCount, setPageCount] = useState(0);
  const [currentPage, setCurrentPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const pdfDocRef = useRef<pdfjsLib.PDFDocumentProxy | null>(null);

  const renderPage = useCallback(async (pageNum: number) => {
    const pdfDoc = pdfDocRef.current;
    const viewer = viewerRef.current;
    if (!pdfDoc || !viewer) return;

    try {
      const page = await pdfDoc.getPage(pageNum);
      const scale = 2; // render at 2x for sharp zoom
      const viewport = page.getViewport({ scale });

      const canvas = document.createElement("canvas");
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      const ctx = canvas.getContext("2d")!;

      await page.render({ canvasContext: ctx, viewport, canvas }).promise;

      const dataUrl = canvas.toDataURL("image/png");

      viewer.open({
        type: "image",
        url: dataUrl,
      });
    } catch (err) {
      setError("Failed to render page");
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function init() {
      if (!containerRef.current) return;

      try {
        const url = getDocumentDownloadUrl(documentId);
        const response = await fetch(url);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const arrayBuffer = await response.arrayBuffer();

        if (cancelled) return;

        const pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
        pdfDocRef.current = pdfDoc;
        setPageCount(pdfDoc.numPages);

        if (cancelled) return;

        const viewer = OpenSeadragon({
          element: containerRef.current!,
          prefixUrl: "https://cdnjs.cloudflare.com/ajax/libs/openseadragon/5.0.1/images/",
          showNavigationControl: true,
          showNavigator: false,
          maxZoomLevel: 10,
          minZoomLevel: 0.5,
          visibilityRatio: 0.5,
          constrainDuringPan: true,
        });
        viewerRef.current = viewer;

        setLoading(false);

        // Render first page
        const page = await pdfDoc.getPage(1);
        const scale = 2;
        const viewport = page.getViewport({ scale });
        const canvas = document.createElement("canvas");
        canvas.width = viewport.width;
        canvas.height = viewport.height;
        const ctx = canvas.getContext("2d")!;
        await page.render({ canvasContext: ctx, viewport, canvas }).promise;

        if (cancelled) return;

        viewer.open({
          type: "image",
          url: canvas.toDataURL("image/png"),
        });
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load PDF");
          setLoading(false);
        }
      }
    }

    init();

    return () => {
      cancelled = true;
      viewerRef.current?.destroy();
      viewerRef.current = null;
      pdfDocRef.current?.destroy();
      pdfDocRef.current = null;
    };
  }, [documentId]);

  const goToPage = useCallback(
    (page: number) => {
      if (page < 1 || page > pageCount) return;
      setCurrentPage(page);
      renderPage(page);
    },
    [pageCount, renderPage]
  );

  if (error) {
    return (
      <div className="flex items-center justify-center h-96 text-destructive">
        Failed to load PDF: {error}
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {pageCount > 1 && (
        <div className="flex items-center justify-center gap-2">
          <Button
            variant="outline"
            size="icon"
            onClick={() => goToPage(currentPage - 1)}
            disabled={currentPage <= 1}
          >
            <ChevronLeft className="size-4" />
          </Button>
          <span className="text-sm text-muted-foreground">
            Page {currentPage} of {pageCount}
          </span>
          <Button
            variant="outline"
            size="icon"
            onClick={() => goToPage(currentPage + 1)}
            disabled={currentPage >= pageCount}
          >
            <ChevronRight className="size-4" />
          </Button>
        </div>
      )}
      {loading && (
        <div className="flex items-center justify-center h-96 text-muted-foreground">
          Loading preview...
        </div>
      )}
      <div
        ref={containerRef}
        className="h-[600px] w-full rounded-lg border bg-muted/50"
        style={{ display: loading ? "none" : "block" }}
      />
    </div>
  );
}
