# PR & Pipeline Triage — 2026-02-17

## Branch Inventory

| Branch | Status | Action |
|--------|--------|--------|
| `Grimm07-patch-1` | **Stale — squash-merged** via #35 | Delete branch |
| `feat/production-hardening-tier1` | **Stale — squash-merged** via #34 | Delete branch |
| `claude/fix-architecture-diagram-urB2D` | **Stale — fully merged** (ancestor of main) | Delete branch |
| `claude/update-docs-architecture-8HBgM` | **Open** — 1 commit (Mermaid diagram fix in README) | Review & merge or close |
| `chore/dependency-updates` | **Open** — 2 commits (14 dep bumps + CodeQL fix) | Review — see details below |
| `dependabot/gradle/exposed-703cff52fa` | **Open** — 1 commit (Exposed 0.57 → 1.0.0) | Review — see details below |

### Branches safe to delete (3)

These branches have already been squash-merged into `main`:

- `Grimm07-patch-1` — Delete ARCHITECTURE_DECISIONS.md (merged as #35)
- `feat/production-hardening-tier1` — production hardening (merged as #34)
- `claude/fix-architecture-diagram-urB2D` — fully contained in main, zero unique commits

## Active PRs Requiring Review

### 1. `chore/dependency-updates` — Broad dependency sweep

**Risk: HIGH** — Contains multiple major version bumps that need CI validation.

#### Gradle changes
| Dependency | Current | Proposed | Severity |
|------------|---------|----------|----------|
| Kotlin | 2.2.0 | **2.3.10** | Major — language version bump |
| HikariCP | 6.0.0 | **7.0.2** | Major |
| Flyway | 10.21.0 | **12.0.1** | Major |
| Jib | 3.4.4 | **3.5.3** | Minor |

#### Frontend changes
| Package | Current | Proposed | Severity |
|---------|---------|----------|----------|
| vitest | ^3.2.1 | **^4.0.18** | Major — test runner |
| @vitejs/plugin-react | ^4.5.2 | **^5.1.4** | Major |
| msw | ^2.8.4 | ^2.12.10 | Minor |
| @types/react | ^19.1.6 | ^19.2.14 | Minor |
| @tanstack/react-router | ^1.160.0 | ^1.160.2 | Patch |
| typescript-eslint | ^8.55.0 | ^8.56.0 | Minor |

#### CI/CD changes
- `actions/checkout` v4 → **v6** (all workflows)
- `actions/setup-node` v4 → **v6**
- `actions/setup-python` v5 → **v6**
- `github/codeql-action/*` v3 → **v4**
- `aquasecurity/trivy-action` 0.28.0 → **0.34.0**
- CodeQL Java-Kotlin build mode: `autobuild` → **`manual`** (fixes Kotlin 2.3.x daemon crash)

**Merge conflicts:** None — clean merge into main.

**Recommendation:** Split into smaller PRs or merge with thorough CI validation. The Kotlin 2.3.10 + Flyway 12 + HikariCP 7 bumps each carry migration risk. Vitest 4.x may require test API changes.

---

### 2. `dependabot/gradle/exposed-703cff52fa` — Exposed 0.57.0 → 1.0.0

**Risk: MEDIUM-HIGH** — The project uses Exposed DSL extensively for DB access.

Potential breakage areas:
- `newSuspendedTransaction` API changes
- `timestampWithTimeZone` ↔ `java.time.OffsetDateTime` bridge
- JSONB column support (`exposed-json`)
- `exposed-kotlin-datetime` integration
- Custom `Op<Boolean>` for `@>` containment queries

**Merge conflicts:** None — only touches `gradle/libs.versions.toml`.

**Recommendation:** Merge separately from the broad dep sweep. Run full `infra-db:test` suite (requires Testcontainers/Docker) to validate.

---

### 3. `claude/update-docs-architecture-8HBgM` — Mermaid diagram fix

**Risk: NONE** — Documentation-only change (README.md, 8 insertions / 8 deletions).

**Recommendation:** Quick review and merge.

## CI Pipeline Issues

### Current (`main`)
- **CodeQL `autobuild` mode** will break if Kotlin is bumped to 2.3.x (daemon crash). The `chore/dependency-updates` branch includes the fix (manual build mode with `--no-configuration-cache -Dkotlin.compiler.execution.strategy=in-process`). If Kotlin is bumped without this fix, CodeQL SAST will fail on PRs.

### Release workflow
- **Trivy action** pinned at `0.28.0` on main; the dep update branch bumps to `0.34.0`. Not blocking, but newer Trivy versions include updated vulnerability databases.
- **`codeql-action/upload-sarif`** at v3 on main; dep update branch bumps to v4.

### Versions on `main` vs latest available (per `chore/dependency-updates`)
- `actions/checkout`: v4 → v6 available
- `actions/setup-node`: v4 → v6 available
- `actions/setup-python`: v5 → v6 available

## Code Health

| Check | Status |
|-------|--------|
| `TODO()` stubs | **Clean** — none found |
| `TODO` comments | **Clean** — none found |
| `FIXME` / `HACK` / `XXX` markers | **Clean** — none found |
| Source code quality | Good — no outstanding debt markers |

## Recommended Merge Order

1. **Delete** 3 stale branches (`Grimm07-patch-1`, `feat/production-hardening-tier1`, `claude/fix-architecture-diagram-urB2D`)
2. **Merge** `claude/update-docs-architecture-8HBgM` (zero-risk docs fix)
3. **Merge** `dependabot/gradle/exposed-703cff52fa` (Exposed 1.0.0) — after CI validates
4. **Merge** `chore/dependency-updates` (broad sweep) — after CI validates; consider splitting if CI fails
