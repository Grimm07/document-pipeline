import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { uploadDocument } from "@/lib/api/documents";
import { documentKeys } from "@/lib/query-keys";
import type { DocumentResponse } from "@/types/api";

export function useDocumentUpload() {
  const [progress, setProgress] = useState(0);
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const mutation = useMutation({
    mutationFn: ({
      file,
      metadata,
    }: {
      file: File;
      metadata: Record<string, string>;
    }) => uploadDocument(file, metadata, setProgress),
    onSuccess: (data: DocumentResponse) => {
      // Cache the newly uploaded document
      queryClient.setQueryData(documentKeys.detail(data.id), data);
      // Invalidate list queries so they refresh
      queryClient.invalidateQueries({ queryKey: documentKeys.lists() });
      // Navigate to the new document's detail page
      navigate({ to: "/documents/$documentId", params: { documentId: data.id } });
    },
    onSettled: () => {
      setProgress(0);
    },
  });

  return {
    upload: mutation.mutate,
    progress,
    isUploading: mutation.isPending,
    error: mutation.error,
    reset: mutation.reset,
  };
}
