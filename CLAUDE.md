## Working Practices

**Always verify your work.** After every change — no matter how small — run the relevant build, test, or lint command to confirm it works. Never claim something is fixed or done without evidence from a passing command.

**Subagent workflow.** When using subagents for implementation:
1. Each subagent must run verification (build/test/lint) on its own work before returning results.
2. Before the main agent continues, it must launch a **code-reviewer subagent** to review the subagent's output against the plan and coding standards.
3. Only after the code-reviewer passes should the main agent proceed to the next step.
4. The main agent must never blindly trust subagent output — review diffs, check for regressions, and verify integration.

## Project Overview

Document Pipeline — a multi-module Kotlin document ingestion service. Accepts document uploads (PDF, images, text) via REST API, stores files locally, persists metadata in PostgreSQL, and dispatches async classification jobs through RabbitMQ to a worker that calls an external ML service.

**Current state**: All business logic implemented, security-hardened (Pass 1), document viewers + OCR pipeline complete (Pass 2). Frontend SPA in `frontend/` with rich viewers (JSON/XML/PDF deep zoom) and OCR results display (tabbed viewer with bounding box overlays). ML service has `/classify-with-ocr` endpoint with PaddleOCR bounding box detection. All tests pass (`./gradlew test` and `cd frontend && npm test`). Run `grep -rn "TODO()" --include="*.kt" .` to verify no stubs remain.

## Build & Run Commands

```bash
# Build entire project
./gradlew build

# Run API server (port 8080)
./gradlew :app-api:run

# Run background worker
./gradlew :app-worker:run

# Run all tests
./gradlew test

# Run tests for a single module
./gradlew :infra-db:test

# Run a single test class
./gradlew :infra-db:test --tests "org.example.pipeline.db.ExposedDocumentRepositoryTest"

# Start everything (infra + backend + frontend) in one command
./scripts/dev.sh              # Starts Docker infra (Postgres, RabbitMQ, ML service), then API, worker, and frontend dev server
./scripts/dev.sh --stop       # Stop all processes + Docker containers (preserves images/volumes)
./scripts/dev.sh --destroy    # Stop all processes + remove Docker containers/volumes (clean slate)
./scripts/dev.sh --restart    # Kill our processes and restart fresh
./scripts/dev.sh --force      # Kill ANY process on our ports, then start fresh

# Start local infrastructure (PostgreSQL + RabbitMQ)
docker compose -f docker/docker-compose.yml up -d

# Compile all modules without running tests (no Docker needed)
./gradlew build -x test

# Stop all Gradle daemons (useful after JDK changes or hangs)
./gradlew --stop

# Clear corrupt configuration cache
rm -rf .gradle/configuration-cache/

# --- Docker ---

# Build ML service Docker image only (no start)
docker compose -f docker/docker-compose.yml build ml-service

# --- ML Service (Python, FastAPI) ---

# Run ML service tests (no GPU needed — all mocked)
cd ml-service && pip install -e ".[dev]" && pytest tests/ -v

# Run ML service via Docker (first run downloads ~4GB of model weights)
docker compose -f docker/docker-compose.yml up ml-service

# Run ML service GPU integration tests (requires CUDA GPU + downloaded models)
cd ml-service && pip install -e ".[dev]" && pytest -m gpu -v

# Run ML service locally (requires CUDA GPU)
cd ml-service && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# --- Frontend (React SPA) ---

# Install frontend dependencies
cd frontend && npm install

# Run frontend dev server (port 5173, proxies /api to localhost:8080)
cd frontend && npm run dev

# Build frontend for production
cd frontend && npm run build

# Run frontend unit/component tests (Vitest)
cd frontend && npm test

# Run frontend E2E tests (Playwright, requires dev server or backend running)
cd frontend && npm run test:e2e

# Type-check frontend
cd frontend && npx tsc -b
```

**Quick start**: `docker compose -f docker/docker-compose.yml up -d && ./gradlew build`
**Frontend quick start**: `cd frontend && npm install && npm run dev` (requires backend at localhost:8080)
**Verify services**: `curl localhost:8080/api/documents` (API), `curl localhost:8000/health` (ML service), `curl localhost:15672` (RabbitMQ management UI, guest/guest)

## Architecture

Six Gradle modules with strict dependency direction — inner modules never depend on outer modules — plus a standalone React SPA and a Python ML service:

```
frontend/ ──────── (React SPA, Vite, npm-managed) ──▶ app-api via HTTP (/api proxy)

app-api  ──┐
app-worker ─┤──▶ core-domain (interfaces + models, zero framework deps)
            │
            ├──▶ infra-db       (Exposed + Flyway + HikariCP → PostgreSQL)
            ├──▶ infra-storage  (local filesystem, date-based paths)
            └──▶ infra-queue    (RabbitMQ publisher + consumer)

ml-service/ ────── (Python FastAPI, pip-managed) ◀── app-worker via HTTP (POST /classify-with-ocr)
```

**core-domain** defines the contracts: `DocumentRepository`, `FileStorageService`, `ClassificationService`, `QueuePublisher`. Infrastructure modules provide implementations. App modules wire everything with Koin DI.

### Key Patterns

- **Dependency inversion**: Domain interfaces in `core-domain`, implementations in `infra-*` modules. App modules bind them via Koin (`apiModule` / `workerModule`).
- **Suspended transactions**: Repository uses `newSuspendedTransaction` from Exposed for coroutine-safe DB access.
- **HOCON config**: `application.conf` in each app module with `${?ENV_VAR}` overrides. Config is loaded via `HoconApplicationConfig(ConfigFactory.load())`.
- **RabbitMQ topology**: Topic exchange with dead-letter exchange/queue. Constants in `QueueConstants`. Both publisher and consumer declare identical topology.
- **Queue message contract**: `DocumentMessage` (`infra-queue`) is the `@Serializable` data class exchanged between publisher and consumer. Contains document ID and action type.
- **File storage paths**: Date-based layout `{yyyy}/{MM}/{dd}/{uuid}.{ext}` under configurable base directory.
- **DTOs**: `DocumentResponse`/`UploadResponse`/`DocumentListResponse`/`ErrorResponse` in `app-api/dto/DocumentDtos.kt`. Domain `Document` converts via `Document.toResponse()` extension.
- **API routes**: `POST /api/documents/upload` (multipart), `GET /api/documents` (list, ?classification&limit&offset), `GET /api/documents/search` (?metadata.*&limit), `GET /api/documents/{id}` (detail), `GET /api/documents/{id}/download` (file bytes), `GET /api/documents/{id}/ocr` (OCR results JSON), `DELETE /api/documents/{id}` (delete document + files). Search and OCR routes must be declared before `{id}` route (Ktor matches in order).
- **OCR results pipeline**: Worker calls ML service `/classify-with-ocr` → receives OCR JSON with bounding boxes → stores as `{documentId}-ocr/ocr-results.json` via `FileStorageService` → document gets `ocrStoragePath` field → API serves raw JSON at `/{id}/ocr`.
- **Flyway migrations**: SQL files in `infra-db/src/main/resources/db/migration/` following `V{N}__description.sql` naming. Run automatically on app startup via `DatabaseConfig.init()`.
- **Database schema**: Single `documents` table (UUID PK, JSONB `metadata`, `ocr_storage_path TEXT`, indexes on `classification`, `created_at`, GIN on `metadata`). Schema: `V1__create_documents_table.sql` + `V2__add_ocr_storage_path.sql`.

### Two Runnable Applications

| Application | Entry point | DI Module | Purpose |
|---|---|---|---|
| `app-api` | `app-api/src/main/kotlin/.../api/Application.kt` | `apiModule` (static val) | Ktor HTTP server, REST endpoints |
| `app-worker` | `app-worker/src/main/kotlin/.../worker/Application.kt` | `workerModule(config)` (function) | RabbitMQ consumer, calls ML service |

Note the DI asymmetry: API module is a top-level `val`; worker module is a function accepting config.

### Frontend Stack

- **React 19** with **TypeScript 5.x**, built with **Vite 6.x**
- **TanStack Router** (file-based routing with auto code-splitting) + **TanStack Query** (server state, polling) + **TanStack Form** (upload form)
- **Tailwind CSS v4** (via `@tailwindcss/vite` plugin) + **shadcn/ui** (new-york style, dark zinc theme)
- **Glassmorphism theme** — light/dark mode with `ThemeProvider`, CSS custom properties for glass effects
- **Document viewers**: `react-json-view-lite` (JSON), `react-xml-viewer` (XML), `pdfjs-dist` + `openseadragon` (PDF deep zoom/pan + bounding box overlays)
- **OCR results UI**: Tabbed viewer (`DocumentViewerTabs`) — Preview / OCR Text / Bounding Boxes tabs, conditional on `hasOcrResults`
- **Vitest** + **React Testing Library** + **MSW** (Mock Service Worker) for unit/component/integration tests
- **Playwright** for E2E tests
- **Multi-select + bulk delete** on document list — `useSelectionMode` hook (selection state) + `useBulkDelete` hook (`Promise.allSettled` mutation with per-result cache eviction). Selection mode exits on filter change, counts only visible-selected IDs, shows error banner on partial failures.
- Dev proxy: Vite forwards `/api/*` to `localhost:8080` — no CORS needed in development

### ML Service Stack

- **Python 3.12** with **FastAPI** + **Uvicorn** (ASGI server)
- **DeBERTa-v3-large NLI** (`MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli`) — zero-shot document classification
- **GOT-OCR2** (`stepfun-ai/GOT-OCR-2.0-hf`) — text extraction from images/PDFs
- **PaddleOCR** (detection-only, `rec=False`) — text region bounding box detection (~3MB model, ~200MB framework)
- **PyMuPDF** (`fitz`) — PDF→image conversion (no system deps)
- **Pydantic Settings** — typed env var config with `ML_` prefix
- Docker: `nvidia/cuda:12.6.3-runtime-ubuntu24.04` base, `ml_models` volume for HF model cache (~4GB)
- GPU VRAM: ~2.5GB total (DeBERTa ~870MB + GOT-OCR2 ~1.1GB + CUDA overhead)
- HTTP contracts:
  - `POST /classify` — `{"content": "<b64>", "mimeType": "..."}` → `{"classification": "...", "confidence": 0.xx}` (legacy, still available)
  - `POST /classify-with-ocr` — same request → `{"classification": "...", "confidence": 0.xx, "ocr": {"pages": [...], "fullText": "..."}}` (worker uses this)
- Env vars: `ML_CLASSIFIER_MODEL`, `ML_OCR_MODEL`, `ML_CANDIDATE_LABELS`, `ML_DEVICE` (`cuda`/`cpu`), `ML_TORCH_DTYPE`, `ML_OCR_MAX_PDF_PAGES`, `ML_HF_HOME`
- Tests: `pytest` with mocked models — no GPU or Docker needed

## Tech Stack Specifics

- **Kotlin 2.2** targeting **JVM 21** with `-Xjsr305=strict` and `-opt-in=kotlin.time.ExperimentalTime`
- **Gradle version catalog**: all versions in `gradle/libs.versions.toml`, referenced as `libs.*` in build files
- **Exposed DSL** (not DAO) with `exposed-json` for JSONB and `exposed-kotlin-datetime` for TIMESTAMPTZ
- **kotlinx.serialization** for JSON (both API DTOs and queue messages) — classes need `@Serializable`
- **kotlinx.datetime** (`Instant`) for timestamps — not `java.time`
- **Ktor 3.2.x** with Netty engine (API) and CIO engine (worker HTTP client)
- **Kotest 6.x** (`FunSpec` style) + **JUnit 5** platform + **Testcontainers** (PostgreSQL + RabbitMQ containers available) + **kotest-extensions-testcontainers** for lifecycle management
- **MockK** for mocking, **Kotest Property** for property-based tests with `Arb` generators

**Test file convention**: `<module>/src/test/kotlin/org/example/pipeline/<package>/<ClassName>Test.kt`. Stress tests use `<ClassName>StressTest.kt` suffix in the same directory.
**ML service test convention**: `ml-service/tests/test_<module>.py`. GPU integration tests in `test_gpu_integration.py` are marked `@pytest.mark.gpu` and excluded by default (`addopts = "-m 'not gpu'"` in pyproject.toml). Run with `pytest -m gpu -v`. Module-scoped fixtures for loaded models avoid reloading between tests.

## Configuration

Both apps use HOCON with env var overrides. Key variables: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `STORAGE_BASE_DIR`, `ML_SERVICE_URL`. Defaults point to localhost for local dev with Docker.

## Gotchas

### Kotlin / Gradle

- **`kotlinx.datetime.Instant`** for all domain timestamps, never `java.time`. Exposed 0.57.0 `timestampWithTimeZone` → `java.time.OffsetDateTime` — bridge with `toJavaInstant()`/`toKotlinInstant()` (from `kotlin.time`).
- **All serialized classes need `@Serializable`** — kotlinx.serialization is compile-time, not reflection-based.
- **Exposed DSL only** — `exposed-core` + `exposed-jdbc`, not `exposed-dao`. `exposed-dao` exists in version catalog but is intentionally unused.
- **`Clock.System`** requires `import kotlin.time.Clock` — not `kotlinx.datetime.Clock`.
- **Avoid Pair/Triple destructuring in lambdas** — K2 reports ambiguous `component1()/component2()`. Use `.first`/`.second`.
- **K2 smart cast across modules** — public properties from other modules can't smart-cast. Extract to local val first.
- **Ktor 3.x multipart**: `PartData.FileItem.provider()` returns `ByteReadChannel`. Use `.toByteArray()` (from `io.ktor.utils.io`).
- **Exposed has no built-in JSONB containment** — create custom `Op<Boolean>` with `QueryBuilder.append()` for `@>` queries.
- **`java.nio.file.Path` is an interface** — use `Path.of()` (static factory), not `Path()`.
- **`Path.resolve(absolutePath)`** returns the absolute path unchanged — test with relative baseDirs too.
- **Add new dependencies via version catalog** — edit `gradle/libs.versions.toml`, not inline versions.
- **After changing JDKs**, run `./gradlew --stop` — daemon caches toolchain detection.
- **If Gradle sync hangs**, delete `.gradle/configuration-cache/`.

### Database / Infrastructure

- **Never edit existing Flyway migrations** — create `V{N+1}__` instead. Applied migrations are immutable.
- **Start Docker before running apps** — `docker compose -f docker/docker-compose.yml up -d` must run first.
- **RabbitMQ topology** — publisher and consumer must declare identical topology via `declareTopology()`.
- **HikariCP 6.x**: Do NOT set `transactionIsolation` when `isAutoCommit = false` — PostgreSQL rejects isolation changes mid-transaction.
- **Docker `nvidia/cuda:*-ubuntu24.04`** — has `ubuntu` user at UID 1000 (Dockerfile does `userdel -r ubuntu`). Always verify containers **run**, not just build.

### Frontend

- **Vite proxy** forwards `/api` to `localhost:8080` — no CORS issues in dev.
- **`routeTree.gen.ts` is auto-generated** — run `npx @tanstack/router-cli generate` if missing.
- **shadcn/ui CLI creates literal `@/` directory** — after running, move files to `src/components/ui/` and delete `@/`.
- **pdfjs-dist v5** — `RenderParameters` requires `canvas` prop; worker must use `?url` import (not CDN).
- **`src/vite-env.d.ts`** required for Vite `?url`/`?raw` import suffixes to type-check.
- **Async preview tests** — initial render shows "Loading preview...". Use `findBy*` or test loading state.
- **Vitest 3.x `vi.fn` generics** — use `vi.fn<(arg: T) => R>()` (single function type). Two-param form removed.
- **`Promise.allSettled` in TanStack Query** — `onSuccess` always fires. Must inspect per-result `status` for failures.
- **Radix AlertDialog + async** — use controlled `open`/`onOpenChange` state for dialogs triggering mutations; uncontrolled won't close if page stays mounted.

### ML Service

- **First run downloads ~4GB** of models from HuggingFace Hub. Set `ML_HF_HOME` to control cache location.
- **Needs NVIDIA GPU** by default (`ML_DEVICE=cuda`). For CPU: `ML_DEVICE=cpu`, `ML_TORCH_DTYPE=float32`, remove `deploy.resources.reservations` from docker-compose.
- **Lazy imports for transformers/torch** — MUST be inside `load()` methods, never at module level. Causes SIGBUS on WSL2 without GPU.
- **PaddleOCR** — lazy load; pinned to 2.x (3.x drops `det`/`rec`/`cls` params); needs `libgl1`/`libglib2.0-0` in Docker.
- **OCR bounding boxes** are text region polygons (lines/paragraphs), not per-word boxes.
- **FastAPI TestClient triggers lifespan** — swap `app.router.lifespan_context` with no-op for tests.

### Testing

- **Tests without Docker**: `app-api`, `app-worker`, `infra-storage`, `core-domain` — no Docker needed. `infra-db` and `infra-queue` require Docker for Testcontainers.
- **`app-worker:test` takes ~35s** — CIO timeout test intentionally waits 30s. Not a hang.
- **Testcontainer lifecycle** — use `install(TestContainerSpecExtension(container))`, NOT `extension()`. `extension()` never calls `mount()`. Wrap `afterSpec` cleanup in `runCatching`.
- **ML tests are fully mocked** — run without GPU/CUDA/models. GPU tests: `pytest -m gpu -v` (auto-skip when unavailable).
- **Two-tier ML mocking** — unit tests mock internals (`service._pipeline = MagicMock()`); pipeline tests use `create_autospec()`. Don't mix.
- **SIGBUS can't be caught** — use subprocess probe to detect if `from transformers import pipeline` works.
- **Mocking uninstalled lazy imports** — inject mock into `sys.modules["paddleocr"]` before `load()`, not `@patch()` (fails with `ModuleNotFoundError`).

## TODO — Remaining Hardening Passes

Pass 1 (Security & Robustness) and Pass 2 (Document Viewers + OCR Pipeline) are complete. Three passes remain:

### Pass 3: Test Coverage Gaps
- Frontend E2E tests need Playwright browsers installed (`npx playwright install chromium`) and backend running
- Frontend integration tests for full page flows (upload → redirect → detail with polling)
- `DatabaseConfig` — untested init/migration error paths
- `RabbitMQPublisher.close()` — untested double-close and error scenarios
- Property-based tests for `Document` domain model validation

### Pass 4: Documentation & Code Quality
- KDoc on all public APIs (domain interfaces, DTOs, config objects)
- `@throws` annotations on functions that throw (`IllegalArgumentException` in path traversal, etc.)
- Error handling consistency — standardize error response format across all routes
- Logging audit — ensure structured logging with correlation IDs

### Pass 5: Build & DevEx Optimization
- Gradle configuration cache enablement (currently disabled due to past corruption)
- Docker Compose health checks for test reliability
- CI pipeline configuration (GitHub Actions or similar)
- Test parallelization tuning for Testcontainers (shared containers across specs)

## Automations

**Hooks** (`.claude/settings.json`): Flyway migration protection (PreToolUse blocks edits to existing `V*__` files), Gradle compile-on-edit (PostToolUse runs `:$MODULE:compileKotlin` on `.kt` edits).

**Skills**: `/create-migration <description>` — auto-versioned Flyway migration. `/implement-stub <file>` — implements TODO() stubs following domain contracts and project patterns.

**Subagent**: `security-reviewer` (`.claude/agents/`) — threat-model-driven review for path traversal, injection, deserialization, file upload abuse, missing auth.

**MCP**: PostgreSQL via `.mcp.json` — connects to Docker instance with `${VAR:-default}` env var substitution for credentials.
