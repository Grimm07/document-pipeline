import { useEffect, useState } from "react";
import { JsonView, allExpanded, darkStyles, defaultStyles } from "react-json-view-lite";
import "react-json-view-lite/dist/index.css";
import { getDocumentDownloadUrl } from "@/lib/api/documents";
import { useTheme } from "@/components/shared/theme-provider";

interface JsonPreviewProps {
  documentId: string;
}

export function JsonPreview({ documentId }: JsonPreviewProps) {
  const [data, setData] = useState<object | unknown[]>(null as unknown as object);
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
        setData(JSON.parse(text));
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

  if (error) {
    return (
      <div className="flex items-center justify-center h-96 text-destructive">
        Failed to load JSON: {error}
      </div>
    );
  }

  const isDark = theme === "dark" || (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);

  return (
    <div className="max-h-[600px] overflow-auto rounded-lg border p-4">
      <JsonView
        data={data}
        shouldExpandNode={allExpanded}
        style={isDark ? darkStyles : defaultStyles}
      />
    </div>
  );
}
