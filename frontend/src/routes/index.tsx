import { createFileRoute, Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { FileText, Upload, Clock, CheckCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { documentKeys } from "@/lib/query-keys";
import { fetchDocuments } from "@/lib/api/documents";
import { DocumentCard } from "@/components/documents/document-card";
import { StatCard } from "@/components/dashboard/stat-card";
import { LoadingSpinner } from "@/components/shared/loading-spinner";

export const Route = createFileRoute("/")({
  component: DashboardPage,
});

function DashboardPage() {
  const { data, isLoading } = useQuery({
    queryKey: documentKeys.list({ limit: 50 }),
    queryFn: () => fetchDocuments({ limit: 50 }),
    refetchInterval: 10_000,
  });

  const documents = data?.documents ?? [];
  const totalDocs = documents.length;
  const classified = documents.filter(
    (d) => d.classification !== "unclassified"
  ).length;
  const pending = totalDocs - classified;

  if (isLoading) return <LoadingSpinner />;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Dashboard</h1>
        <p className="text-muted-foreground mt-1">
          Document ingestion pipeline overview
        </p>
      </div>

      {/* Stats */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Total Documents" value={totalDocs} icon={FileText} />
        <StatCard label="Classified" value={classified} icon={CheckCircle} />
        <StatCard label="Pending" value={pending} icon={Clock} />
        <Link to="/upload" className="block">
          <StatCard
            label="Upload New"
            value="+"
            icon={Upload}
            className="cursor-pointer transition-colors hover:border-primary/30"
          />
        </Link>
      </div>

      {/* Recent Uploads */}
      <div>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-xl font-semibold">Recent Uploads</h2>
          <Link to="/documents">
            <Button variant="ghost" size="sm">
              View All
            </Button>
          </Link>
        </div>
        {documents.length === 0 ? (
          <div className="glass-card rounded-lg p-8 text-center text-muted-foreground">
            No documents yet. Upload your first document to get started.
          </div>
        ) : (
          <div className="grid gap-3">
            {documents.slice(0, 5).map((doc) => (
              <DocumentCard key={doc.id} document={doc} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
