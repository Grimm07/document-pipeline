## Working Practices

**Always verify your work.** After every change — no matter how small — run the relevant build, test, or lint command to confirm it works. Never claim something is fixed or done without evidence from a passing command.

**Subagent workflow.** When using subagents for implementation:
1. Each subagent must run verification (build/test/lint) on its own work before returning results.
2. Before the main agent continues, it must launch a **code-reviewer subagent** to review the subagent's output against the plan and coding standards.
3. Only after the code-reviewer passes should the main agent proceed to the next step.
4. The main agent must never blindly trust subagent output — review diffs, check for regressions, and verify integration.

## Project Overview

Document Pipeline — a multi-module Kotlin document ingestion service. Accepts document uploads (PDF, images, text) via REST API, stores files locally, persists metadata in PostgreSQL, and dispatches async classification jobs through RabbitMQ to a worker that calls an external ML service.

**Current state**: All business logic implemented, security-hardened (Pass 1), document viewers + OCR pipeline complete (Pass 2), test coverage expanded (Pass 3), linting + documentation enforced (Pass 4), Gradle config cache + DevEx (Pass 5), observability + structured logging (Pass 6), model explainability + label correction (Pass 7), CI/CD pipeline (Phase 8). Frontend SPA in `frontend/` with rich viewers (JSON/XML/PDF deep zoom), OCR results display (tabbed viewer with bounding box overlays), and inline label correction (popover with all candidate scores + confirmation dialog). ML service returns full label scores from zero-shot classification. DB tracks `label_scores JSONB`, `classification_source` ("ml"/"manual"), and `corrected_at` for future fine-tuning. All three services expose Prometheus `/metrics` endpoints; correlation IDs propagate API → RabbitMQ → Worker → ML via `X-Request-ID`. GitHub Actions CI runs 8 parallel jobs on PRs; release workflow auto-versions, builds container images to GHCR, runs Trivy scans, and creates GitHub Releases. All tests pass (`./gradlew test` and `cd frontend && npm test`). All linters pass (`./gradlew detekt`, `ruff check`, `eslint`). Run `grep -rn "TODO()" --include="*.kt" .` to verify no stubs remain.

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

# --- Container Images ---

# Build API image as local Docker tar (no push, no Docker daemon needed)
./gradlew :app-api:jibBuildTar --no-configuration-cache

# Build Worker image as local Docker tar
./gradlew :app-worker:jibBuildTar --no-configuration-cache

# Build API image to local Docker daemon
./gradlew :app-api:jibDockerBuild --no-configuration-cache

# Push API image to GHCR (requires docker login)
./gradlew :app-api:jib --no-configuration-cache

# Override image tags at build time
./gradlew :app-api:jib --no-configuration-cache -Djib.to.tags=latest,v0.3.0,abc1234

# Build frontend Docker image
cd frontend && docker build -t document-pipeline-frontend .

# --- Versioning ---

# Preview next version (dry-run, no tags created)
cog bump --auto --dry-run

# --- Changelog ---

# Regenerate changelog (picks up git tags automatically)
git-cliff --output CHANGELOG.md

# Label unreleased commits as a version (before creating a git tag)
git-cliff --tag v0.2.0 --output CHANGELOG.md

# --- Git Hooks ---

# Install git hooks (required once after cloning)
lefthook install

# Validate a commit message manually
echo "feat: test message" | cog verify

# Check full history for conventional commit compliance
cog check
```

**Quick start**: `docker compose -f docker/docker-compose.yml up -d && ./gradlew build`
**Frontend quick start**: `cd frontend && npm install && npm run dev` (requires backend at localhost:8080)
**Verify services**: `curl localhost:8080/api/documents` (API), `curl localhost:8000/health` (ML service), `curl localhost:15672` (RabbitMQ management UI, guest/guest)
**Verify observability**: `curl localhost:8080/metrics` (API metrics), `curl localhost:8081/metrics` (Worker metrics), `curl localhost:8000/metrics` (ML metrics), `curl localhost:9090/api/v1/targets` (Prometheus targets), `curl localhost:3000/api/health` (Grafana)

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
- **Flyway migrations**: SQL files in `infra-db/src/main/resources/db/migration/` following `V{N}__description.sql` naming. Run automatically on startup. Current: V1 (initial), V2 (OCR path), V3 (label scores + classification source).
- **Label correction flow**: `PATCH /api/documents/{id}/classification` → `correctClassification(id, label)` → sets `classification_source = "manual"`, `corrected_at = now`. Frontend: `LabelCorrectionPopover` (Popover + AlertDialog) → `useLabelCorrection` hook → cache invalidation.
- **ML HTTP contracts**: `POST /classify-with-ocr` — `{"content": "<b64>", "mimeType": "..."}` → `{"classification", "confidence", "ocr": {"pages", "fullText"}}`. Legacy `POST /classify` still available (no OCR).
- **ML env vars**: `ML_CLASSIFIER_MODEL`, `ML_OCR_MODEL`, `ML_CANDIDATE_LABELS`, `ML_DEVICE` (`cuda`/`cpu`), `ML_TORCH_DTYPE`, `ML_OCR_MAX_PDF_PAGES`, `ML_HF_HOME`
- **Correlation ID propagation**: API generates UUID via Ktor `CallId` plugin → stored in `DocumentMessage.correlationId` field → Worker extracts and sets `MDC("correlationId")` → forwarded as `X-Request-ID` header to ML service → ML stores in `ContextVar` via ASGI middleware. All JSON logs include `correlationId`.
- **Prometheus metrics**: API at `/metrics` (Micrometer+Ktor), Worker at port 8081 `/metrics` (JDK HttpServer), ML at `/metrics` (prometheus-fastapi-instrumentator). Grafana dashboards auto-provisioned.
- **Structured JSON logging**: LogstashEncoder (Kotlin) and python-json-logger (Python). Dev mode uses plain text (`-Dlogback.configurationFile=logback-text.xml` in `dev.sh`).
- **Container images**: Jib (Gradle plugin) for backend images (`app-api`, `app-worker`) — no Dockerfiles, layered JRE images from `eclipse-temurin:21-jre-alpine`. Multi-stage Dockerfile for frontend (Node build → nginx). All pushed to `ghcr.io/grimm07/document-pipeline-*`. ML service uses existing Dockerfile in `docker/`.
- **CI/CD**: GitHub Actions — `ci.yml` (8 parallel PR check jobs), `release.yml` (auto-version → image build → Trivy scan → GitHub Release), `codeql.yml` (SAST for Kotlin, JS/TS, Python). Dependabot watches 4 ecosystems (Gradle, npm, pip, GitHub Actions).
- **Input validation**: Konform declarative validators in `app-api/.../validation/Validators.kt` as standalone `val` objects. Generic `T.validate(Validation<T>)` extension in `ValidationSupport.kt` throws `ValidationException(fieldErrors)`, caught by Ktor `StatusPages`. Query params parsed into wrapper DTOs (`ListQueryParams`, `SearchQueryParams`, `UploadParams`) for structured validation.

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
- **Label correction UI**: `LabelCorrectionPopover` wraps `ClassificationBadge` with a `Popover` listing all candidate label scores + `AlertDialog` for confirmation. Uses `useRef` to protect selected label from Radix `onOpenChange` race. `Button` (not `AlertDialogAction`) for confirm to prevent auto-close before mutation.
- Dev proxy: Vite forwards `/api/*` to `localhost:8080` — no CORS needed in dev

### ML Service Notes

- GPU VRAM: ~2.5GB total (DeBERTa ~870MB + GOT-OCR2 ~1.1GB + CUDA overhead). Docker: `nvidia/cuda:12.6.3-runtime-ubuntu24.04`, `ml_models` volume for HF cache (~4GB)
- First run downloads ~4GB of model weights from HuggingFace Hub

## Tech Stack

**Backend**: Kotlin 2.2, JVM 21, Gradle version catalog (`gradle/libs.versions.toml`), Ktor 3.2 (Netty API / CIO worker client), kotlinx.serialization, kotlinx.datetime, Koin DI, Exposed DSL, Flyway, HikariCP, RabbitMQ, Konform 0.11 (validation), Micrometer 1.14 + Prometheus, LogstashEncoder (JSON logging), Detekt 1.23.8
**Frontend**: React 19, TypeScript 5, Vite 6, TanStack Router + Query + Form, Tailwind CSS v4, shadcn/ui, pdfjs-dist + openseadragon, ESLint 9 + Prettier 3
**ML Service**: Python 3.12, FastAPI, Transformers (DeBERTa-v3-large NLI), GOT-OCR2, PaddleOCR, PyMuPDF, prometheus-client + prometheus-fastapi-instrumentator, python-json-logger, Ruff
**Testing**: Kotest 6 (FunSpec) + JUnit 5 + Testcontainers + MockK (backend), Vitest + RTL + MSW + Playwright (frontend), pytest (ML)
**Infrastructure**: PostgreSQL 16, RabbitMQ 4, Docker Compose, Prometheus + Grafana, NVIDIA CUDA 12.6 (optional), git-cliff (changelog), Lefthook, Cocogitto (commit linting)
**CI/CD**: GitHub Actions, Jib 3.4 (JVM container images), Trivy (container scanning), CodeQL (SAST), Dependabot (dependency updates), GHCR (container registry)

**Test file convention**: `<module>/src/test/kotlin/org/example/pipeline/<package>/<ClassName>Test.kt`. Stress tests use `<ClassName>StressTest.kt` suffix in the same directory.
**ML service test convention**: `ml-service/tests/test_<module>.py`. GPU integration tests in `test_gpu_integration.py` are marked `@pytest.mark.gpu` and excluded by default (`addopts = "-m 'not gpu'"` in pyproject.toml). Run with `pytest -m gpu -v`. Module-scoped fixtures for loaded models avoid reloading between tests.

## Configuration

Both apps use HOCON with env var overrides. Key variables: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `STORAGE_BASE_DIR`, `ML_SERVICE_URL`, `WORKER_METRICS_PORT`. Defaults point to localhost for local dev with Docker.

**Service ports**: API 8080 (`/api/*`, `/metrics`, `/health`), Worker metrics 8081 (`/metrics`), ML service 8000 (`/health`, `/metrics`, `/classify-with-ocr`), PostgreSQL 5432, RabbitMQ 5672 (AMQP) / 15672 (management) / 15692 (Prometheus), Prometheus 9090, Grafana 3000.

## Git Workflow

- **Remote**: `git@github.com:Grimm07/document-pipeline.git` (origin)
- **Branching**: Create small, focused branches per logical change. One bug fix = one branch. One feature = one branch. Keep PRs small and reviewable — avoid bundling unrelated changes. Branch naming: `fix/short-description`, `feat/short-description`, `chore/short-description`.
- **Commit style**: conventional commits required (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `perf:`, `chore:`, `ci:`, `style:`) — these feed the auto-generated changelog
- **Commit linting**: Enforced by Lefthook + Cocogitto `commit-msg` hook. Run `lefthook install` after cloning.
- **Changelog**: auto-generated via [git-cliff](https://git-cliff.org) from conventional commits. Config in `cliff.toml`. Regenerate with `git-cliff --output CHANGELOG.md`
- **Merge policy**: Squash merges only — merge commits are rejected by repo settings. Use `gh pr merge --squash`.

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
- **Micrometer 1.14+ package rename** — use `io.micrometer.prometheusmetrics.PrometheusMeterRegistry` and `PrometheusConfig`, NOT the old `io.micrometer.prometheus` package (won't compile).
- **MDC is thread-local, coroutines switch threads** — Ktor `CallLogging` plugin handles MDC per-request automatically. Worker uses `runBlocking` (same thread), so MDC is safe there. Do NOT use MDC in `suspend` functions launched with `Dispatchers.IO` without explicit MDC context propagation.
- **Worker metrics port** — configurable via `WORKER_METRICS_PORT` env var (default 8081). Uses JDK `com.sun.net.httpserver.HttpServer` — zero extra dependencies.
- **JDK `HttpServer` must bind `"0.0.0.0"` explicitly** — `InetSocketAddress(port)` without a host binds to `::` (IPv6) on JDK 21. Docker containers connect via IPv4, so the socket is unreachable on WSL2. Always use `InetSocketAddress("0.0.0.0", port)`.
- **Prometheus `host.docker.internal` on Docker Desktop + WSL2** — Docker Desktop resolves `host.docker.internal` to the VM gateway (`192.168.65.254`), which can't route to WSL2 host services. Fix: `extra_hosts: ["host.docker.internal:${HOST_IP:-host-gateway}"]` in docker-compose, with `dev.sh` exporting `HOST_IP` from `eth0`. Container-to-container targets (ml-service, rabbitmq) use Docker DNS names. `network_mode: host` does NOT work on Docker Desktop (binds to VM, not WSL2 host).
- **Old queue messages without `correlationId`** — backward compatible. Field defaults to `null`, consumer's Json uses `ignoreUnknownKeys = true`.
- **LogstashEncoder brings jackson-databind transitively** — no conflict with kotlinx.serialization (independent serialization systems).
- **Konform 0.11.0 deprecated package** — use `io.konform.validation.constraints` for `maxLength`, `minimum`, `maximum`, `minItems`. The old `io.konform.validation.jsonschema` package still compiles but is deprecated.
- **kotlinx.serialization `encodeDefaults = false`** (the default) — fields with default values are omitted from JSON output. Never give `@Serializable` data class fields defaults if they must always appear in responses (e.g., `error` field in error DTOs).
- **Detekt `MatchingDeclarationName`** — triggered when a file contains multiple top-level declarations where none matches the filename. Suppress with `@file:Suppress("MatchingDeclarationName")` when grouping related declarations intentionally.

### Database / Infrastructure

- **`lsof` can't see Docker-forwarded ports on Linux** — Docker uses iptables-level forwarding, not userspace sockets. Don't use `lsof -ti :PORT` to detect Docker-managed services. Use `docker compose ps` or health-check endpoints instead.
- **Grafana datasource UID must match dashboard JSON** — `docker/grafana/provisioning/datasources/prometheus.yml` pins `uid: PBFA97CFB590B2093` to match the hardcoded refs in `document-pipeline.json`. Without this, volume recreation generates a new UID and all panels break.
- **Prometheus scrape targets use two address modes** — `host.docker.internal:PORT` for host-running services (API, Worker), Docker DNS names for container services (`ml-service:8000`, `rabbitmq:15692`). Both work because Prometheus is on the bridge network with `extra_hosts` override.
- **Rebuild Docker images after dependency changes** — `docker compose up -d` does NOT rebuild images. After adding packages to `pyproject.toml` or other dependency files, run `docker compose -f docker/docker-compose.yml build <service>` (or `up -d --build`). Stale images will silently lack new packages.
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
- **Radix AlertDialog + async** — use controlled `open`/`onOpenChange` state for dialogs triggering mutations; uncontrolled won't close if page stays mounted. `AlertDialogAction` auto-closes the dialog *before* async `onClick` completes — use a regular `Button` for confirm actions that trigger mutations. Pair with `useRef` to protect selected state from `onOpenChange` race.
- **Hook tests with MSW + relative URLs** — `renderHook` in jsdom has no browser `location`, so `fetch("/api/...")` throws "Failed to parse URL". Use `vi.mock` on the API module instead of MSW for isolated hook tests.
- **`apiFetch` header merge** — headers are destructured from `init` before spreading (`const { headers, ...rest } = init`) to prevent `...init` from overwriting the merged headers object.
- **JSDoc required on exports** — `eslint-plugin-jsdoc` enforces `require-jsdoc` on exported functions, classes, interfaces, and type aliases. Excluded: tests, routes, `components/ui/`, generated files. Use `/** Description. */` (no `@param`/`@returns` — TypeScript types suffice).
- **Prettier runs separately** — `npm run format:check` verifies, `npm run format` auto-fixes. Config: 100-char width, double quotes, trailing commas.

### Changelog

- **Generate changelog AFTER committing** — `git-cliff` reads git history, so the commit must exist first. Correct order: commit → `git tag vX.Y.Z` → `git-cliff --output CHANGELOG.md` → commit changelog separately.
- **git-cliff has no `--dry-run`** — omit the `-o` flag to preview to stdout instead.
- **`cliff.toml` preprocessors truncate to first line** — intentional; older freeform commits have multi-paragraph bodies that would flood the changelog. Don't remove the `'\n[\s\S]*'` preprocessor.
- **`--tag v0.x.0`** labels unreleased commits as a version in the output without creating a git tag. Once a real tag exists, plain `git-cliff --output CHANGELOG.md` picks it up automatically.
- **Claude session URLs and Co-Authored-By trailers are scrubbed** — `cliff.toml` has preprocessors + a postprocessor safety net. Do not remove these.
- **Preprocessor ordering matters** — scrubs first, then first-line truncation, then link replacements. Parenthesized `(#N)` before bare `#N` to avoid double-matching.

### dev.sh

- **ML service is Docker-only** — its lifecycle belongs to `docker compose`, not host port checks. Never add ML back to `handle_port` pre-flight.
- **`wait` with zero children returns immediately** — if all services are skipped, `wait` exits and `kill 0` fires, killing pre-existing processes. The `BG_JOBS` counter + `trap - EXIT` disarm prevents this.
- **`(( x++ ))` is unsafe with `set -e`** — post-increment returns the old value; when that's 0, `(( 0 ))` returns exit code 1 and kills the script. Use `VAR=$((VAR + 1))` instead.

### Git Hooks

- **All commits are validated** — including commits made by Claude Code. The hook runs `cog verify` on every commit message. Always use conventional format.
- **`lefthook install` required after cloning** — writes shims to `.git/hooks/`, not tracked by git.
- **`cog check` flags old freeform commits** — expected, only new commits are enforced by the hook.
- **`lefthook-local.yml`** for developer-specific overrides (gitignored).
- **Cocogitto does NOT manage changelog** — git-cliff does that (`disable_changelog = true` in `cog.toml`).
- **Claude Code hooks** (`.claude/settings.json`) are separate — they operate at agent level, not git level.

### ML Service

- **Needs NVIDIA GPU** by default (`ML_DEVICE=cuda`). For CPU: `ML_DEVICE=cpu`, `ML_TORCH_DTYPE=float32`, remove `deploy.resources.reservations` from docker-compose.
- **Lazy imports for transformers/torch** — MUST be inside `load()` methods, never at module level. Causes SIGBUS on WSL2 without GPU.
- **PaddleOCR 3.x** — lazy load via `TextDetection(model_name="PP-OCRv5_server_det", device=...)`. Uses `"gpu"` not `"cuda"` for device. PaddlePaddle 3.x must be listed explicitly in pyproject.toml (PaddleX does not declare it as a transitive dep). Needs `libgl1`/`libglib2.0-0`/`libgomp1` in Docker.
- **OCR bounding boxes** are text region polygons (lines/paragraphs), not per-word boxes.
- **FastAPI TestClient triggers lifespan** — swap `app.router.lifespan_context` with no-op for tests.
- **Ruff enforces Google-style docstrings** — all public functions/classes need docstrings. Test files and `__init__.py` are excluded. Run `ruff check app/` and `ruff format --check app/` before committing.

### CI/CD

- **Jib requires `--no-configuration-cache`** — Jib 3.4.4 serializes `Project` at execution time, incompatible with Gradle's configuration cache. Always pass `--no-configuration-cache` for `jib`/`jibBuildTar`/`jibDockerBuild` tasks. Normal `build`/`test` tasks are unaffected.
- **Jib tag override** — use `-Djib.to.tags=latest,v1.0.0,sha` to override the tags defined in `build.gradle.kts`. Jib reads `GIT_SHA` env var for the default dev tag.
- **`cog bump --auto --dry-run`** — calculates the next semver from conventional commits since last tag. Returns non-zero if no bump needed (e.g., only `docs:` or `chore:` commits). Release workflow gates on this.
- **ML service CI pip install** — uses `pip install -e ".[dev]"` with pip caching (PaddlePaddle 3.x is ~800MB).
- **Playwright E2E excluded from CI** — needs full backend stack (PostgreSQL, RabbitMQ, ML service). Run locally or in a future dedicated E2E workflow.
- **Release workflow pushes to main** — the `create-release` job commits the changelog and pushes. Needs `github-actions[bot]` allowed to bypass branch protection. Loop prevention: `paths-ignore: ['CHANGELOG.md']` + `[skip ci]` in the commit message (defense-in-depth).
- **`fetch-depth: 0` required** — both Cocogitto (`cog bump`) and git-cliff need full git history to calculate versions and generate changelogs. Shallow clones break them.
- **CodeQL language identifier** — Kotlin uses `java-kotlin` (not `kotlin`). It needs `autobuild` mode with Java 21 + Gradle set up.
- **Trivy SARIF upload** — results go to GitHub Security tab → Code scanning alerts. Uses `if: always()` so results upload even if Trivy finds HIGH/CRITICAL vulnerabilities.
- **Dependabot grouped updates** — Ktor, Exposed, Kotest, Testcontainers, TanStack, and testing libraries are grouped to avoid PR spam. Groups create one PR per group.

### Testing

- **Tests without Docker**: `app-api`, `app-worker`, `infra-storage`, `core-domain` — no Docker needed. `infra-db` and `infra-queue` require Docker for Testcontainers.
- **`app-worker:test` takes ~35s** — CIO timeout test intentionally waits 30s. Not a hang.
- **Testcontainer lifecycle** — use `install(TestContainerSpecExtension(container))`, NOT `extension()`. `extension()` never calls `mount()`. Wrap `afterSpec` cleanup in `runCatching`.
- **ML tests are fully mocked** — run without GPU/CUDA/models. GPU tests: `pytest -m gpu -v` (auto-skip when unavailable).
- **Two-tier ML mocking** — unit tests mock internals (`service._pipeline = MagicMock()`); pipeline tests use `create_autospec()`. Don't mix.
- **SIGBUS can't be caught** — use subprocess probe to detect if `from transformers import pipeline` works.
- **Mocking uninstalled lazy imports** — inject mock into `sys.modules["paddleocr"]` before `load()`, not `@patch()` (fails with `ModuleNotFoundError`). Mock `TextDetection` (not `PaddleOCR`).

## Automations

**Hooks** (`.claude/settings.json`): Flyway migration protection (PreToolUse blocks edits to existing `V*__` files), Gradle compile-on-edit (PostToolUse runs `:$MODULE:compileKotlin` on `.kt` edits).

**Skills**: `/create-migration <description>` — auto-versioned Flyway migration. `/implement-stub <file>` — implements TODO() stubs following domain contracts and project patterns.

**Subagent**: `security-reviewer` (`.claude/agents/`) — threat-model-driven review for path traversal, injection, deserialization, file upload abuse, missing auth.

**MCP**: PostgreSQL via `.mcp.json` — connects to Docker instance with `${VAR:-default}` env var substitution for credentials.
