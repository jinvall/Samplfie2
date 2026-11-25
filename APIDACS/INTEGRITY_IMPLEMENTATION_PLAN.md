# APIDACS Phased Implementation Plan

**Governing Specs:** `/APIDACS/APIDACS.md` & `/APIDACS/APIDACSI.md`  
**Audit Baseline:** `/APIDACS/INTEGRITY_AUDIT.md`

---

## Phase Execution Order & Acceptance Gates

### Phase 0: Repository Audit (Completed)
- **Deliverables:** `INTEGRITY_AUDIT.md`, `INTEGRITY_ARCHITECTURE.md`, `INTEGRITY_IMPLEMENTATION_PLAN.md`.
- **Gate:** Zero modifications to application source (`app/src`, `desktop/src`); exact file/tool/warning baseline recorded.

### Phase 1: Foundation & Authoritative SQLite Registry
- **Scope:** Build `.apidacs/apidacs.db` schema (all 37 structural + lifecycle tables), schema migration engine, atomic transaction wrapper, database backup/restore, project & scan registration, stable ID generation, file inventory, and base `integrity` / `apidacs` CLI dispatcher.
- **Acceptance:** Registry creates, migrates, backs up, validates integrity (`PRAGMA integrity_check`, orphan/FK checks), and rolls back cleanly on simulated failure.

### Phase 2: Real AST Source Analysis Engine
- **Scope:** Compile offline Kotlin PSI AST extractor using local `kotlin-compiler-embeddable-2.4.21.jar`, build SHA-256 incremental parse cache, and parse `.kt`, `.kts`, `.xml`, `.toml`, `.sh`, `.json` files to extract imports, types, inheritance, functions, parameters, variables, call sites, reflection sites, Compose/Swing UI controls, and tests with explicit confidence tags.
- **Acceptance:** Full scan of Samplfie2 inventories all 122 `.kt` files, 4 `.kts` files, 11 `.xml` files, and incremental re-scan skips unchanged files deterministically.

### Phase 3: Variable Lifecycle, Data-Flow & Dependency Graph
- **Scope:** Track `DECLARED -> INITIALIZED -> ASSIGNED -> READ -> PROPAGATED -> TRANSFORMED -> CONSUMED -> VERIFIED / DISCONNECTED / UNRESOLVED / OBSOLETE`, distinguish logging-only reads from functional consumption, build directed graph edges, detect circular dependencies, and trace `upstream`/`downstream`/`trace` paths (including configuration chains like `RigConfig.sampleRate`).
- **Acceptance:** Unused, logging-only, ignored-parameter, hardcoded-override, and disconnected flow fixtures are detected without auto-deleting any code.

### Phase 4: Feature, Interface Contract & UI-to-Backend Mapping
- **Scope:** Map Android Jetpack Compose controls (`Button`, `Slider`, `Switch`, `testTag`, `onClick`) and Desktop Swing controls (`hardwareButton`, listeners) through ViewModel/Controller to backend engines; enforce interface/type contract compatibility tracking and status classification (`PLANNED`..`VERIFIED`/`BROKEN`).
- **Acceptance:** Disconnected UI controls and removed interface implementations are flagged automatically.

### Phase 5: Global Finding Lifecycle, Change-Impact & Enforcement
- **Scope:** Implement `APIDACSI.md` multi-signal fingerprint reconciliation, automatic queueing, read-only 8-step investigation, impact & root-cause analysis, dispositions, remediation plans, explicit approval gate, execution & regression comparison, closure validation & immutable history, evidence freshness (`STALE`/`INVALIDATED`) + auto-reopening, exception expiration, and `AUDIT`/`MONITOR`/`ENFORCEMENT` compliance verdicts (`PASS`, `PASS_WITH_WARNINGS`, `REVIEW_REQUIRED`, `BLOCKED`, `ANALYSIS_INCOMPLETE`).
- **Acceptance:** Destructive edits without impact/approval are blocked; compiler warning baseline (`7` Kotlin warnings, `38` lint advisories) separates pre-existing items from newly introduced regressions.

### Phase 6: Test Integrity, Git Integration & CI Enforcement
- **Scope:** Track verification categories separately, detect `@Ignore`/`@Disabled`/empty/weakened/mock-misrepresented tests, install executable Git pre-commit/pre-push hook templates + optional `.git` hook installer, and generate standalone GitHub Actions CI workflow (`.github/workflows/apidacs-integrity.yml`).
- **Acceptance:** Weakened/skipped tests generate test-integrity findings; CLI pre-commit & CI checks run independently against source.

### Phase 7: Deterministic Markdown Documentation & Drift Detection
- **Scope:** Generate all 14 mandatory Markdown reports in `/APIDACS/reports/` (plus `PROJECT_INTEGRITY.md`) strictly from SQLite queries in deterministic sorted order, and implement `integrity docs validate` drift detection.
- **Acceptance:** Two consecutive `docs generate` runs produce byte-identical Markdown; manual tampering triggers `docs validate` failure (`exit 1`).

### Phase 8: Full Validation & Completion Report
- **Scope:** Run comprehensive unit & integration test suite (`APIDACS.md` §22 & `APIDACSI.md` §34), run full live `integrity audit` + `apidacs findings report` across the actual Samplfie2 codebase, verify Android/Desktop app builds/tests remain healthy, and produce `/APIDACS/INTEGRITY_SYSTEM_COMPLETION.md`.
- **Acceptance:** All automated APIDACS tests pass, full live audit & all 14 docs validate with zero drift, and all known Phase 0/8 limitations are explicitly recorded.
