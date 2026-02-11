import { useEffect, useRef, useState, useCallback } from "react";
import * as pdfjsLib from "pdfjs-dist";
import pdfjsWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import OpenSeadragon from "openseadragon";
import { ChevronLeft, ChevronRight, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useDocumentOcr } from "@/hooks/use-document-ocr";
import { getDocumentDownloadUrl } from "@/lib/api/documents";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorker;

interface BoundingBoxViewerProps {
  documentId: string;
  mimeType: string;
}

export function BoundingBoxViewer({ documentId, mimeType }: BoundingBoxViewerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null);
  const pdfDocRef = useRef<pdfjsLib.PDFDocumentProxy | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [viewerReady, setViewerReady] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const { data: ocr, isLoading: ocrLoading } = useDocumentOcr(documentId);
  const pageCount = ocr?.pages.length ?? 0;

  const addOverlays = useCallback(
    (viewer: OpenSeadragon.Viewer, pageIndex: number) => {
      // Clear existing overlays
      viewer.clearOverlays();

      const page = ocr?.pages[pageIndex];
      if (!page || !page.blocks.length) return;

      // Wait for the image to be loaded to get proper coordinates
      viewer.addHandler("open", function handler() {
        viewer.removeHandler("open", handler);
        for (const block of page.blocks) {
          const overlay = document.createElement("div");
          overlay.className = "bbox-overlay";
          overlay.style.border = "2px solid rgba(59, 130, 246, 0.6)";
          overlay.style.backgroundColor = "rgba(59, 130, 246, 0.1)";
          overlay.style.cursor = "pointer";
          overlay.title = block.text || `Region at (${Math.round(block.bbox.x)}, ${Math.round(block.bbox.y)})`;

          // Convert pixel coordinates to normalized viewport coordinates
          const rect = new OpenSeadragon.Rect(
            block.bbox.x / page.width,
            block.bbox.y / page.height,
            block.bbox.width / page.width,
            block.bbox.height / page.height
          );

          viewer.addOverlay({
            element: overlay,
            location: rect,
          });
        }
      });
    },
    [ocr]
  );

  const renderPageWithBoxes = useCallback(
    async (pageIndex: number) => {
      const viewer = viewerRef.current;
      if (!viewer) return;

      try {
        if (mimeType === "application/pdf") {
          const pdfDoc = pdfDocRef.current;
          if (!pdfDoc) return;

          const page = await pdfDoc.getPage(pageIndex + 1);
          const scale = 2;
          const viewport = page.getViewport({ scale });
          const canvas = document.createElement("canvas");
          canvas.width = viewport.width;
          canvas.height = viewport.height;
          const ctx = canvas.getContext("2d")!;
          await page.render({ canvasContext: ctx, viewport, canvas }).promise;

          viewer.open({ type: "image", url: canvas.toDataURL("image/png") });
        } else if (mimeType.startsWith("image/")) {
          viewer.open({ type: "image", url: getDocumentDownloadUrl(documentId) });
        }

        addOverlays(viewer, pageIndex);
      } catch {
        setError("Failed to render page");
      }
    },
    [documentId, mimeType, addOverlays]
  );

  useEffect(() => {
    let cancelled = false;

    async function init() {
      if (!containerRef.current || !ocr) return;

      try {
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

        if (mimeType === "application/pdf") {
          const url = getDocumentDownloadUrl(documentId);
          const response = await fetch(url);
          const arrayBuffer = await response.arrayBuffer();
          if (cancelled) return;

          const pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
          pdfDocRef.current = pdfDoc;

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

          viewer.open({ type: "image", url: canvas.toDataURL("image/png") });
        } else if (mimeType.startsWith("image/")) {
          viewer.open({ type: "image", url: getDocumentDownloadUrl(documentId) });
        }

        if (cancelled) return;
        addOverlays(viewer, 0);
        setViewerReady(true);
        setLoading(false);
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load");
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
  }, [documentId, mimeType, ocr, addOverlays]);

  const goToPage = useCallback(
    (pageIdx: number) => {
      if (pageIdx < 0 || pageIdx >= pageCount) return;
      setCurrentPage(pageIdx);
      renderPageWithBoxes(pageIdx);
    },
    [pageCount, renderPageWithBoxes]
  );

  if (ocrLoading) {
    return (
      <div className="flex items-center justify-center h-96 text-muted-foreground gap-2">
        <Loader2 className="size-4 animate-spin" />
        Loading OCR data...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-96 text-destructive">
        {error}
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
            disabled={currentPage <= 0}
          >
            <ChevronLeft className="size-4" />
          </Button>
          <span className="text-sm text-muted-foreground">
            Page {currentPage + 1} of {pageCount}
          </span>
          <Button
            variant="outline"
            size="icon"
            onClick={() => goToPage(currentPage + 1)}
            disabled={currentPage >= pageCount - 1}
          >
            <ChevronRight className="size-4" />
          </Button>
        </div>
      )}
      {loading && (
        <div className="flex items-center justify-center h-96 text-muted-foreground gap-2">
          <Loader2 className="size-4 animate-spin" />
          Loading document...
        </div>
      )}
      <div
        ref={containerRef}
        className="h-[600px] w-full rounded-lg border bg-muted/50"
        style={{ display: loading ? "none" : "block" }}
      />
      {viewerReady && ocr && (
        <p className="text-xs text-muted-foreground text-center">
          {ocr.pages[currentPage]?.blocks.length ?? 0} text regions detected on this page
        </p>
      )}
    </div>
  );
}
