# APIDACS Architecture Specification for Samplfie2

**Authority:** Implements both `/APIDACS/APIDACS.md` (Project Integrity, Dependency Tracking & Agent Compliance System) and `/APIDACS/APIDACSI.md` (Global Finding Lifecycle Procedure).

---

## 1. System Topology

```text
+-------------------------------------------------------------------------+
|                    CLIs: ./integrity  &  ./apidacs                       |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------v------------------------------------+
|           Orchestration, Enforcement & Lifecycle Engine                 |
|  - Modes: AUDIT | MONITOR | ENFORCEMENT                                 |
|  - Finding Lifecycle State Machine (NEW -> REGISTERED -> ... -> CLOSED) |
|  - Change-Impact & Change-Direction Analyzer                            |
|  - Approval / Exception / Test-Integrity Gates                          |
+-------+-------------------------+-------------------------+-------------+
        |                         |                         |
+-------v--------+      +---------v---------+     +---------v---------+
| Kotlin PSI AST |      | Structured Parsers|     | Diagnostics & Test|
| Extractor      |      | - XML (Manifest/  |     | Ingestion         |
| (kotlin-       |      |   Resources)      |     | - Gradle/Kotlin   |
|  compiler-     |      | - TOML (Catalog)  |     |   compiler logs   |
|  embeddable    |      | - Shell / JSON /  |     | - Android Lint    |
|  2.4.21 JAR)   |      |   Properties      |     | - JUnit XML       |
+-------+--------+      +---------+---------+     +---------+---------+
        |                         |                         |
        +-------------------------+-------------------------+
                                  | (Atomic SQLite Transactions)
+---------------------------------v---------------------------------------+
|              Authoritative SQLite Registry (.apidacs/apidacs.db)         |
|  - Structural Elements (files, types, functions, variables, ui, tests)  |
|  - Directed Graph Edges & Data-Flow Chains (with Confidence levels)     |
|  - Global Finding Lifecycle Tables (findings, observations,             |
|    fingerprints, investigations, evidence, impact, plans, approvals,    |
|    remediation_runs, verification_runs, regressions, closures, history) |
+---------------------------------+---------------------------------------+
                                  | (Deterministic Generator)
+---------------------------------v---------------------------------------+
|         14 Mandatory Generated Markdown Views (APIDACS/reports/)        |
|  PROJECT_INTEGRITY.md, DEPENDENCIES.md, VARIABLES.md, TYPES.md,         |
|  FUNCTIONS.md, DATA_FLOW.md, FEATURE_MAP.md, UI_BACKEND_MAP.md,         |
|  ISSUES.md, CHANGE_IMPACT.md, TEST_COVERAGE.md, EXCEPTIONS.md,          |
|  REGISTRY_STATUS.md, CHANGE_HISTORY.md                                  |
+-------------------------------------------------------------------------+
```

---

## 2. Component Design & File Layout

All APIDACS implementation files live cleanly under `/APIDACS/toolkit/` with top-level root CLI entrypoints `./integrity` and `./apidacs` (keeping application source trees `app/src` and `desktop/src` untouched during scanning):

- `/integrity` & `/apidacs` — Executable wrapper scripts invoking `python3 /APIDACS/toolkit/cli.py`.
- `/APIDACS/toolkit/psi_extractor/` — Real Kotlin PSI AST extractor compiled offline into `.apidacs/psi_helper.jar` using the local `kotlin-compiler-embeddable-2.4.21.jar` (`KtFile`, `KtTreeVisitorVoid`, declarations, modifiers, parameters, return types, property initializers/getters/setters, assignments, reads, calls, dot-qualified references, annotations, reflection API markers, Compose `@Composable` controls & `onClick`/`testTag` callbacks, Swing listeners).
- `/APIDACS/toolkit/apidacs/` — Modular Python 3.11 package:
  - `db.py` — SQLite schema (`PRAGMA foreign_keys=ON`, WAL mode, schema migrations, atomic transactions, backup & restore).
  - `models.py` — Enums & dataclasses for confidence (`Confirmed`, `Strong`, `Inferred`, `Unknown`, `Contradictory`), variable lifecycle (`DECLARED`..`VERIFIED` / `DISCONNECTED` / `UNRESOLVED` / `OBSOLETE`), finding states (`NEW`..`CLOSED` / `STALE` / `REOPENED` / `BLOCKED` / `DEFERRED`), classifications, dispositions, verification categories, and compliance verdicts.
  - `parsers.py` — Batch Kotlin PSI invocation + SHA-256 AST cache (`.apidacs/cache/`) + XML/TOML/Shell/JSON parsers.
  - `analyzer.py` — Scanner building file/type/function/variable/UI-control/test registries, directed dependency edges, circular dependency detection, reflection uncertainty markers, and end-to-end data-flow tracer.
  - `lifecycle.py` — Finding ingestion, multi-signal fingerprint identity reconciliation, automatic triage, read-only step-by-step investigation engine (`Step 1`..`Step 8`), impact/root-cause analysis, disposition, remediation planning, approval validation, execution tracking, verification, regression comparison, formal closure, evidence freshness (`CURRENT` / `STALE` / `INVALIDATED`), and automatic reopening.
  - `diagnostics.py` — Compiler/Lint/Test ingestion, `@Suppress` audit, baseline comparison, test-integrity analyzer (empty/skipped/disabled/weakened/mock-only checks).
  - `impact.py` — Git/hash change detection, destructive-change detector, change-direction violation checker, compliance evaluator (`PASS`, `PASS_WITH_WARNINGS`, `REVIEW_REQUIRED`, `BLOCKED`, `ANALYSIS_INCOMPLETE`), exception expiration checker.
  - `docs_gen.py` — Deterministic generator producing all 14 mandatory Markdown reports + drift detector.
  - `git_ci.py` — Pre-commit / pre-push hook runners & GitHub Actions CI workflow generator.
  - `cli.py` — Full unified CLI implementing both `integrity ...` and `apidacs ...` command groups with documented exit codes (`0`, `1`, `2`, `3`).
- `/APIDACS/reports/` — Destination for the 14 authoritative-registry-derived Markdown documents (plus `PROJECT_INTEGRITY.md` symlink/copy if referenced at repo root).
- `/APIDACS/tests/` — Standalone comprehensive automated test suite exercising real SQLite persistence, real Kotlin PSI parsing on controlled synthetic fixtures AND real repository files, covering every mandatory case in `APIDACS.md` §22 and `APIDACSI.md` §34.

---

## 3. Authoritative SQLite Data Model

The SQLite database (`.apidacs/apidacs.db`) unifies both specifications in one normalized schema:

- **Core Structural Tables:** `schema_migrations`, `projects`, `scans`, `analyzers`, `source_files`, `types`, `functions`, `variables`, `ui_controls`, `features`, `dependency_edges`, `data_flows`, `tests`, `baseline_snapshots`, `source_baselines`
- **APIDACSI Lifecycle Tables:** `findings`, `finding_observations`, `finding_fingerprints`, `investigations`, `investigation_evidence`, `investigation_references`, `investigation_impact`, `finding_classifications`, `finding_dispositions`, `remediation_plans`, `remediation_approvals`, `remediation_runs`, `remediation_changes`, `verification_runs`, `verification_results`, `regression_reports`, `closure_records`, `finding_history`, `finding_relationships`, `evidence_dependencies`, `exceptions`, `audit_events`

Every state mutation executes inside an explicit `BEGIN IMMEDIATE ... COMMIT` SQLite transaction and appends an immutable `finding_history` / `audit_events` row.

---

## 4. Safety & Read-Only Guarantees

1. **Zero modification of `app/src` or `desktop/src` during scans/investigations/docs generation.**
2. **Never execute scanned source code** — PSI parses syntax trees passively in a sandboxed Java process with zero reflection/execution of user classes.
3. **Atomic registry & backup safety:** Pre-migration and pre-scan backups preserve the previous valid SQLite database if any operation is interrupted.
4. **Explicit Uncertainty:** Reflection calls (`Class.forName`, `.javaClass`, `getDeclaredField`, etc.), dynamic dispatch, and indirect Compose/Swing callbacks preserve `Inferred` or `Unknown` edges instead of falsely marking targets unused.
