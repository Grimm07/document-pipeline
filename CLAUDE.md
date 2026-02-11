## Working Practices

**Always verify your work.** After every change — no matter how small — run the relevant build, test, or lint command to confirm it works. Never claim something is fixed or done without evidence from a passing command.

**Subagent workflow.** When using subagents for implementation:
1. Each subagent must run verification (build/test/lint) on its own work before returning results.
2. Before the main agent continues, it must launch a **code-reviewer subagent** to review the subagent's output against the plan and coding standards.
3. Only after the code-reviewer passes should the main agent proceed to the next step.
4. The main agent must never blindly trust subagent output — review diffs, check for regressions, and verify integration.

## Project Overview

Document Pipeline — a multi-module Kotlin document ingestion service. Accepts document uploads (PDF, images, text) via REST API, stores files locally, persists metadata in PostgreSQL, and dispatches async classification jobs through RabbitMQ to a worker that calls an external ML service.

**Current state**: All business logic implemented, security-hardened (Pass 1), document viewers + OCR pipeline complete (Pass 2), test coverage expanded (Pass 3), linting + documentation enforced (Pass 4). Frontend SPA in `frontend/` with rich viewers (JSON/XML/PDF deep zoom) and OCR results display (tabbed viewer with bounding box overlays). ML service has `/classify-with-ocr` endpoint with PaddleOCR bounding box detection. All tests pass (`./gradlew test` and `cd frontend && npm test`). All linters pass (`./gradlew detekt`, `ruff check`, `eslint`). Run `grep -rn "TODO()" --include="*.kt" .` to verify no stubs remain.

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
# NOTE: pip install -e ".[dev]" may fail on paddlepaddle — install tools directly if needed: pip install ruff pytest
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

# --- Linting ---

# Run Kotlin linter (Detekt) — also runs as part of ./gradlew build
./gradlew detekt

# Run Python linter (Ruff)
cd ml-service && ruff check app/ && ruff format --check app/

# Auto-fix Python lint + format
cd ml-service && ruff check --fix app/ && ruff format app/

# Run TypeScript linter (ESLint + type-check)
cd frontend && npm run lint

# Auto-fix TypeScript lint
cd frontend && npm run lint:fix

# Check TypeScript formatting (Prettier)
cd frontend && npm run format:check

# Auto-fix TypeScript formatting
cd frontend && npm run format
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

**Linting config**: `config/detekt/detekt.yml` (Kotlin), `ml-service/pyproject.toml` `[tool.ruff]` (Python), `frontend/eslint.config.js` + `frontend/.prettierrc` (TypeScript). Detekt hooks into Gradle `check` task; Ruff and ESLint run standalone.

### Key Patterns

- **Dependency inversion**: Domain interfaces in `core-domain`, implementations in `infra-*` modules. App modules bind them via Koin (`apiModule` / `workerModule`).
- **Suspended transactions**: Repository uses `newSuspendedTransaction` from Exposed for coroutine-safe DB access.
- **HOCON config**: `application.conf` in each app module with `${?ENV_VAR}` overrides. Config is loaded via `HoconApplicationConfig(ConfigFactory.load())`.
- **RabbitMQ topology**: Topic exchange with dead-letter exchange/queue. Constants in `QueueConstants`. Both publisher and consumer declare identical topology.
- **File storage paths**: Date-based layout `{yyyy}/{MM}/{dd}/{uuid}.{ext}` under configurable base directory.
- **Ktor route ordering**: Search and OCR routes must be declared before `{id}` route (Ktor matches first).
- **OCR results pipeline**: Worker calls ML `/classify-with-ocr` → stores OCR JSON as `{documentId}-ocr/ocr-results.json` → API serves at `/{id}/ocr`.
- **Flyway migrations**: SQL files in `infra-db/src/main/resources/db/migration/` following `V{N}__description.sql` naming. Run automatically on startup.
- **ML HTTP contracts**: `POST /classify-with-ocr` — `{"content": "<b64>", "mimeType": "..."}` → `{"classification", "confidence", "ocr": {"pages", "fullText"}}`. Legacy `POST /classify` still available (no OCR).
- **ML env vars**: `ML_CLASSIFIER_MODEL`, `ML_OCR_MODEL`, `ML_CANDIDATE_LABELS`, `ML_DEVICE` (`cuda`/`cpu`), `ML_TORCH_DTYPE`, `ML_OCR_MAX_PDF_PAGES`, `ML_HF_HOME`

### Two Runnable Applications

| Application | Entry point | DI Module | Purpose |
|---|---|---|---|
| `app-api` | `app-api/src/main/kotlin/.../api/Application.kt` | `apiModule` (static val) | Ktor HTTP server, REST endpoints |
| `app-worker` | `app-worker/src/main/kotlin/.../worker/Application.kt` | `workerModule(config)` (function) | RabbitMQ consumer, calls ML service |

Note the DI asymmetry: API module is a top-level `val`; worker module is a function accepting config.

### Frontend Notes

- **Glassmorphism theme** with dark/light mode via `ThemeProvider`
- **Document viewers**: JSON (`react-json-view-lite`), XML (`react-xml-viewer`), PDF deep zoom (`pdfjs-dist` + `openseadragon`), OCR tabbed viewer with bounding box overlays
- **Multi-select + bulk delete**: `useSelectionMode` + `useBulkDelete` hooks (`Promise.allSettled` with per-result cache eviction)
- Dev proxy: Vite forwards `/api/*` to `localhost:8080` — no CORS needed in dev

### ML Service Notes

- GPU VRAM: ~2.5GB total (DeBERTa ~870MB + GOT-OCR2 ~1.1GB + CUDA overhead). Docker: `nvidia/cuda:12.6.3-runtime-ubuntu24.04`, `ml_models` volume for HF cache (~4GB)
- First run downloads ~4GB of model weights from HuggingFace Hub

## Tech Stack

**Backend**: Kotlin 2.2, JVM 21, Gradle version catalog (`gradle/libs.versions.toml`), Ktor 3.2 (Netty API / CIO worker client), kotlinx.serialization, kotlinx.datetime, Koin DI, Exposed DSL, Flyway, HikariCP, RabbitMQ, Detekt 1.23.8
**Frontend**: React 19, TypeScript 5, Vite 6, TanStack Router + Query + Form, Tailwind CSS v4, shadcn/ui, pdfjs-dist + openseadragon, ESLint 9 + Prettier 3
**ML Service**: Python 3.12, FastAPI, Transformers (DeBERTa-v3-large NLI), GOT-OCR2, PaddleOCR, PyMuPDF, Ruff
**Testing**: Kotest 6 (FunSpec) + JUnit 5 + Testcontainers + MockK (backend), Vitest + RTL + MSW + Playwright (frontend), pytest (ML)
**Infrastructure**: PostgreSQL 16, RabbitMQ 4, Docker Compose, NVIDIA CUDA 12.6 (optional)

**Test file convention**: `<module>/src/test/kotlin/org/example/pipeline/<package>/<ClassName>Test.kt`. Stress tests use `<ClassName>StressTest.kt` suffix in the same directory.
**ML service test convention**: `ml-service/tests/test_<module>.py`. GPU integration tests in `test_gpu_integration.py` are marked `@pytest.mark.gpu` and excluded by default (`addopts = "-m 'not gpu'"` in pyproject.toml). Run with `pytest -m gpu -v`. Module-scoped fixtures for loaded models avoid reloading between tests.

## Configuration

Both apps use HOCON with env var overrides. Key variables: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `STORAGE_BASE_DIR`, `ML_SERVICE_URL`. Defaults point to localhost for local dev with Docker.

## Git Workflow

- **Remote**: `git@github.com:Grimm07/document-pipeline.git` (origin)
- **Branch**: `main` — all work currently on main (no feature branch convention yet)
- **Commit style**: imperative subject line, bullet-point body for multi-topic commits

## Gotchas

### Kotlin / Gradle

- **`kotlinx.datetime.Instant`** for all domain timestamps, never `java.time`. Exposed 0.57.0 `timestampWithTimeZone` → `java.time.OffsetDateTime` — bridge with `toJavaInstant()`/`toKotlinInstant()` (from `kotlin.time`).
- **All serialized classes need `@Serializable`** — kotlinx.serialization is compile-time, not reflection-based.
- **`decodeFromString` can throw `IllegalArgumentException`** — not just `SerializationException`. Catch `Exception` in safety-critical paths (e.g., message consumers that must nack malformed input).
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
- **Detekt runs with `./gradlew build`** — it hooks into the `check` task. Add `@Suppress("RuleName")` for justified exceptions. Wildcard imports for Exposed, Ktor, RabbitMQ, Kotest, MockK are pre-exempted in config.
- **KDoc on public APIs is enforced** — Detekt's `UndocumentedPublicClass/Function/Property` rules are active (test files excluded). Add KDoc when creating new public APIs.
- **Detekt companion objects** — use `private companion object` for constants to avoid `UndocumentedPublicClass` on companion. If public, add KDoc to the companion.

### Database / Infrastructure

- **Never document credential defaults in README or public docs** — they exist in `application.conf` and `docker-compose.yml` for local dev, but don't repeat values like passwords in documentation.
- **Never edit existing Flyway migrations** — create `V{N+1}__` instead. Applied migrations are immutable.
- **Start Docker before running apps** — `docker compose -f docker/docker-compose.yml up -d` must run first.
- **RabbitMQ topology** — publisher and consumer must declare identical topology via `declareTopology()`.
- **HikariCP 6.x**: Do NOT set `transactionIsolation` when `isAutoCommit = false` — PostgreSQL rejects isolation changes mid-transaction.

### Frontend

- **ESLint 10 upgrade blocked** (evaluated 2026-02) — `typescript-eslint`, `eslint-plugin-jsdoc`, and `eslint-plugin-react-hooks` all cap peer deps at ESLint 9. Track [typescript-eslint#11952](https://github.com/typescript-eslint/typescript-eslint/issues/11952) — once that ships, upgrade all together. No config changes needed (already on flat config).
- **Vite proxy** forwards `/api` to `localhost:8080` — no CORS issues in dev.
- **`routeTree.gen.ts` is auto-generated** — run `npx @tanstack/router-cli generate` if missing.
- **pdfjs-dist v5** — `RenderParameters` requires `canvas` prop; worker must use `?url` import (not CDN).
- **Async preview tests** — initial render shows "Loading preview...". Use `findBy*` or test loading state.
- **Vitest 3.x `vi.fn` generics** — use `vi.fn<(arg: T) => R>()` (single function type). Two-param form removed.
- **`Promise.allSettled` in TanStack Query** — `onSuccess` always fires. Must inspect per-result `status` for failures.
- **Radix AlertDialog + async** — use controlled `open`/`onOpenChange` state for dialogs triggering mutations; uncontrolled won't close if page stays mounted.
- **JSDoc required on exports** — `eslint-plugin-jsdoc` enforces `require-jsdoc` on exported functions, classes, interfaces, and type aliases. Excluded: tests, routes, `components/ui/`, generated files. Use `/** Description. */` (no `@param`/`@returns` — TypeScript types suffice).
- **Prettier runs separately** — `npm run format:check` verifies, `npm run format` auto-fixes. Config: 100-char width, double quotes, trailing commas.

### ML Service

- **Needs NVIDIA GPU** by default (`ML_DEVICE=cuda`). For CPU: `ML_DEVICE=cpu`, `ML_TORCH_DTYPE=float32`, remove `deploy.resources.reservations` from docker-compose.
- **Lazy imports for transformers/torch** — MUST be inside `load()` methods, never at module level. Causes SIGBUS on WSL2 without GPU.
- **PaddleOCR** — lazy load; pinned to 2.x (3.x drops `det`/`rec`/`cls` params); needs `libgl1`/`libglib2.0-0` in Docker.
- **PaddlePaddle 2.x removed from PyPI** — `pip install -e ".[dev]"` may fail. PaddleOCR 2.x requires `paddlepaddle<3,>=2.6` but only 3.x is available. Install individual dev tools (ruff, pytest) directly if full install fails. Docker build still works (pinned wheels).
- **OCR bounding boxes** are text region polygons (lines/paragraphs), not per-word boxes.
- **FastAPI TestClient triggers lifespan** — swap `app.router.lifespan_context` with no-op for tests.
- **Ruff enforces Google-style docstrings** — all public functions/classes need docstrings. Test files and `__init__.py` are excluded. Run `ruff check app/` and `ruff format --check app/` before committing.

### Testing

- **Tests without Docker**: `app-api`, `app-worker`, `infra-storage`, `core-domain` — no Docker needed. `infra-db` and `infra-queue` require Docker for Testcontainers.
- **`app-worker:test` takes ~35s** — CIO timeout test intentionally waits 30s. Not a hang.
- **Testcontainer lifecycle** — use `install(TestContainerSpecExtension(container))`, NOT `extension()`. `extension()` never calls `mount()`. Wrap `afterSpec` cleanup in `runCatching`.
- **ML tests are fully mocked** — run without GPU/CUDA/models. GPU tests: `pytest -m gpu -v` (auto-skip when unavailable).
- **Two-tier ML mocking** — unit tests mock internals (`service._pipeline = MagicMock()`); pipeline tests use `create_autospec()`. Don't mix.
- **SIGBUS can't be caught** — use subprocess probe to detect if `from transformers import pipeline` works.
- **Mocking uninstalled lazy imports** — inject mock into `sys.modules["paddleocr"]` before `load()`, not `@patch()` (fails with `ModuleNotFoundError`).

## Roadmap

Passes 1–4 complete (security, OCR viewers, test coverage, linting). See `README.md` Roadmap table for passes 5–9.

## Automations

**Hooks** (`.claude/settings.json`): Flyway migration protection (PreToolUse blocks edits to existing `V*__` files), Gradle compile-on-edit (PostToolUse runs `:$MODULE:compileKotlin` on `.kt` edits).

**Skills**: `/create-migration <description>` — auto-versioned Flyway migration. `/implement-stub <file>` — implements TODO() stubs following domain contracts and project patterns.

**Subagent**: `security-reviewer` (`.claude/agents/`) — threat-model-driven review for path traversal, injection, deserialization, file upload abuse, missing auth.

**MCP**: PostgreSQL via `.mcp.json` — connects to Docker instance with `${VAR:-default}` env var substitution for credentials.
