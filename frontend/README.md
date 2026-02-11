# Document Pipeline — Frontend

React SPA for the document ingestion pipeline. Upload documents, browse/search the document list, view document details with rich previews, and inspect OCR results with bounding box overlays.

## Quick Start

```bash
npm install
npm run dev          # Dev server on http://localhost:5173
```

Requires the backend API running on `localhost:8080` — Vite proxies `/api/*` requests automatically.

## Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start Vite dev server (port 5173) |
| `npm run build` | Type-check + production build |
| `npm test` | Run Vitest unit/component tests |
| `npm run test:watch` | Run Vitest in watch mode |
| `npm run test:e2e` | Run Playwright E2E tests |
| `npm run lint` | Type-check only (`tsc --noEmit`) |

## Stack

- **React 19** + **TypeScript 5.8** + **Vite 6**
- **TanStack Router** (file-based, auto code-splitting) + **TanStack Query** (server state) + **TanStack Form**
- **Tailwind CSS v4** + **shadcn/ui** (new-york style, dark zinc theme)
- **Vitest** + **React Testing Library** + **MSW** for tests, **Playwright** for E2E

## Project Structure

```
src/
├── components/
│   ├── dashboard/      # Stats cards, recent documents
│   ├── documents/      # Document list, classification badge
│   ├── layout/         # App shell, sidebar, header
│   ├── preview/        # Document viewers (PDF, JSON, XML, OCR)
│   ├── shared/         # Loading spinner, error display, theme
│   ├── ui/             # shadcn/ui primitives (button, card, tabs, etc.)
│   └── upload/         # Upload form, dropzone
├── hooks/              # TanStack Query hooks (useDocuments, useDocumentOcr, etc.)
├── lib/
│   ├── api/            # API client functions
│   └── query-keys.ts   # Centralized query key factory
├── routes/             # TanStack Router file-based routes
│   ├── __root.tsx      # Root layout
│   ├── index.tsx       # Dashboard
│   ├── upload.tsx      # Upload page
│   └── documents/      # Document list + detail ($documentId)
├── test/
│   ├── fixtures.ts     # Shared mock data
│   └── mocks/          # MSW handlers
└── types/              # API response types
```

## Document Viewers

The detail page uses `DocumentViewerTabs` which conditionally shows:

- **Preview tab** — Always shown. Routes by MIME type:
  - `application/pdf` — PDF deep zoom via `pdfjs-dist` + `OpenSeaDragon`
  - `application/json` — Syntax-highlighted tree via `react-json-view-lite`
  - `application/xml`, `text/xml` — Formatted XML via `react-xml-viewer`
  - `image/*` — Native `<img>` tag
  - `text/*` — Fetched and rendered as `<pre>`
- **OCR Text tab** — Shown when `hasOcrResults` is true. Displays extracted text and full OCR JSON tree.
- **Bounding Boxes tab** — Shown when `hasOcrResults` is true. Renders document in OpenSeaDragon with bounding box overlays from PaddleOCR detection results.

## Environment

No `.env` file needed for development — Vite proxy handles API routing. For production builds, set `VITE_API_BASE_URL` if the API isn't co-hosted.
