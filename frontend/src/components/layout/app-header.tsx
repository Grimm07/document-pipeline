import { Link } from "@tanstack/react-router";
import { FileText, Upload, LayoutDashboard } from "lucide-react";
import { ModeToggle } from "@/components/shared/mode-toggle";

const navLinks = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard },
  { to: "/documents", label: "Documents", icon: FileText },
  { to: "/upload", label: "Upload", icon: Upload },
] as const;

/** Sticky top navigation bar with links to Dashboard, Documents, and Upload pages. */
export function AppHeader() {
  return (
    <header className="sticky top-0 z-50 glass border-b border-border/40">
      <div className="mx-auto flex h-14 max-w-7xl items-center gap-6 px-4">
        <Link to="/" className="flex items-center gap-2 font-semibold">
          <FileText className="size-5 text-primary" />
          <span className="hidden sm:inline">Document Pipeline</span>
        </Link>

        <nav className="flex flex-1 items-center gap-1">
          {navLinks.map(({ to, label, icon: Icon }) => (
            <Link
              key={to}
              to={to}
              className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground [&.active]:text-foreground [&.active]:bg-accent"
            >
              <Icon className="size-4" />
              <span className="hidden sm:inline">{label}</span>
            </Link>
          ))}
        </nav>

        <ModeToggle />
      </div>
    </header>
  );
}
