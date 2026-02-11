import { useEffect, useState } from "react";
import XMLViewer from "react-xml-viewer";
import { getDocumentDownloadUrl } from "@/lib/api/documents";
import { useTheme } from "@/components/shared/theme-provider";

interface XmlPreviewProps {
  documentId: string;
}

export function XmlPreview({ documentId }: XmlPreviewProps) {
  const [xml, setXml] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const { theme } = useTheme();

  useEffect(() => {
    setLoading(true);
    setError(null);
    fetch(getDocumentDownloadUrl(documentId))
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.text();
      })
      .then((text) => {
        setXml(text);
        setLoading(false);
      })
      .catch((err) => {
        setError(err.message);
        setLoading(false);
      });
  }, [documentId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96 text-muted-foreground">
        Loading preview...
      </div>
    );
  }

  if (error || !xml) {
    return (
      <div className="flex items-center justify-center h-96 text-destructive">
        Failed to load XML: {error ?? "Empty content"}
      </div>
    );
  }

  const isDark = theme === "dark" || (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  const customTheme = isDark
    ? { tagColor: "#7dd3fc", attributeKeyColor: "#c084fc", attributeValueColor: "#86efac", textColor: "#e2e8f0" }
    : undefined;

  return (
    <div className="max-h-[600px] overflow-auto rounded-lg border p-4 font-mono text-sm">
      <XMLViewer xml={xml} theme={customTheme} />
    </div>
  );
}
