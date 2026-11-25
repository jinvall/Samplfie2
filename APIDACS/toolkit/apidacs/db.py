"""Authoritative SQLite Registry for APIDACS & APIDACSI."""

from __future__ import annotations

import json
import os
import shutil
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Tuple

from .models import EnforcementMode, stable_id

SCHEMA_VERSION = 1


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


SCHEMA_SQL = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS projects (
    project_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    root_path TEXT NOT NULL UNIQUE,
    enforcement_mode TEXT NOT NULL DEFAULT 'MONITOR',
    source_revision TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS analyzers (
    analyzer_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    language TEXT NOT NULL,
    capabilities_json TEXT NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS scans (
    scan_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    scan_mode TEXT NOT NULL,
    trigger_type TEXT NOT NULL,
    source_revision TEXT NOT NULL DEFAULT '',
    started_at TEXT NOT NULL,
    completed_at TEXT,
    status TEXT NOT NULL,
    files_scanned INTEGER NOT NULL DEFAULT 0,
    files_skipped INTEGER NOT NULL DEFAULT 0,
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS source_files (
    file_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    rel_path TEXT NOT NULL,
    language TEXT NOT NULL,
    module_name TEXT NOT NULL DEFAULT '',
    package_name TEXT NOT NULL DEFAULT '',
    sha256 TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    line_count INTEGER NOT NULL DEFAULT 0,
    mtime_ns INTEGER NOT NULL DEFAULT 0,
    is_generated INTEGER NOT NULL DEFAULT 0,
    is_third_party INTEGER NOT NULL DEFAULT 0,
    is_test INTEGER NOT NULL DEFAULT 0,
    parse_status TEXT NOT NULL DEFAULT 'OK',
    parse_error TEXT NOT NULL DEFAULT '',
    imports_json TEXT NOT NULL DEFAULT '[]',
    reflection_markers_json TEXT NOT NULL DEFAULT '[]',
    last_scanned_at TEXT NOT NULL,
    UNIQUE(project_id, rel_path)
);

CREATE TABLE IF NOT EXISTS types (
    type_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    file_id TEXT NOT NULL REFERENCES source_files(file_id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    kind TEXT NOT NULL,
    visibility TEXT NOT NULL DEFAULT 'public',
    supertypes_json TEXT NOT NULL DEFAULT '[]',
    annotations_json TEXT NOT NULL DEFAULT '[]',
    line_start INTEGER NOT NULL DEFAULT 0,
    line_end INTEGER NOT NULL DEFAULT 0,
    is_data_class INTEGER NOT NULL DEFAULT 0,
    is_sealed INTEGER NOT NULL DEFAULT 0,
    serialization_contract INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS functions (
    function_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    file_id TEXT NOT NULL REFERENCES source_files(file_id) ON DELETE CASCADE,
    type_id TEXT REFERENCES types(type_id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    signature TEXT NOT NULL,
    visibility TEXT NOT NULL DEFAULT 'public',
    params_json TEXT NOT NULL DEFAULT '[]',
    return_type TEXT NOT NULL DEFAULT 'Unit',
    annotations_json TEXT NOT NULL DEFAULT '[]',
    modifiers_json TEXT NOT NULL DEFAULT '[]',
    line_start INTEGER NOT NULL DEFAULT 0,
    line_end INTEGER NOT NULL DEFAULT 0,
    is_override INTEGER NOT NULL DEFAULT 0,
    is_composable INTEGER NOT NULL DEFAULT 0,
    is_entry_point INTEGER NOT NULL DEFAULT 0,
    is_framework_managed INTEGER NOT NULL DEFAULT 0,
    has_side_effects INTEGER NOT NULL DEFAULT 0,
    body_calls_json TEXT NOT NULL DEFAULT '[]',
    ignored_params_json TEXT NOT NULL DEFAULT '[]',
    return_ignored_by_callers INTEGER NOT NULL DEFAULT 0,
    unused_status TEXT NOT NULL DEFAULT 'USED'
);

CREATE TABLE IF NOT EXISTS variables (
    variable_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    file_id TEXT NOT NULL REFERENCES source_files(file_id) ON DELETE CASCADE,
    type_id TEXT REFERENCES types(type_id) ON DELETE SET NULL,
    function_id TEXT REFERENCES functions(function_id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    scope TEXT NOT NULL,
    var_kind TEXT NOT NULL,
    declared_type TEXT NOT NULL DEFAULT 'Unknown',
    visibility TEXT NOT NULL DEFAULT 'public',
    mutability TEXT NOT NULL DEFAULT 'val',
    initial_value TEXT NOT NULL DEFAULT '',
    is_hardcoded_literal INTEGER NOT NULL DEFAULT 0,
    line_number INTEGER NOT NULL DEFAULT 0,
    annotations_json TEXT NOT NULL DEFAULT '[]',
    assignments_json TEXT NOT NULL DEFAULT '[]',
    reads_json TEXT NOT NULL DEFAULT '[]',
    logging_reads_json TEXT NOT NULL DEFAULT '[]',
    call_sites_json TEXT NOT NULL DEFAULT '[]',
    transformations_json TEXT NOT NULL DEFAULT '[]',
    destinations_json TEXT NOT NULL DEFAULT '[]',
    intended_consumer TEXT NOT NULL DEFAULT '',
    actual_consumer TEXT NOT NULL DEFAULT '',
    lifecycle_stage TEXT NOT NULL DEFAULT 'DECLARED',
    confidence TEXT NOT NULL DEFAULT 'Confirmed'
);

CREATE TABLE IF NOT EXISTS ui_controls (
    control_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    file_id TEXT NOT NULL REFERENCES source_files(file_id) ON DELETE CASCADE,
    function_id TEXT REFERENCES functions(function_id) ON DELETE SET NULL,
    screen_name TEXT NOT NULL,
    component_type TEXT NOT NULL,
    control_label TEXT NOT NULL,
    test_tag TEXT NOT NULL DEFAULT '',
    handler_name TEXT NOT NULL DEFAULT '',
    handler_target TEXT NOT NULL DEFAULT '',
    backend_targets_json TEXT NOT NULL DEFAULT '[]',
    state_updates_json TEXT NOT NULL DEFAULT '[]',
    line_number INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'PARTIALLY_IMPLEMENTED',
    confidence TEXT NOT NULL DEFAULT 'Inferred'
);

CREATE TABLE IF NOT EXISTS features (
    feature_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    module_scope TEXT NOT NULL DEFAULT '',
    entry_points_json TEXT NOT NULL DEFAULT '[]',
    ui_controls_json TEXT NOT NULL DEFAULT '[]',
    backend_functions_json TEXT NOT NULL DEFAULT '[]',
    data_flows_json TEXT NOT NULL DEFAULT '[]',
    test_ids_json TEXT NOT NULL DEFAULT '[]',
    status TEXT NOT NULL DEFAULT 'IMPLEMENTED_UNVERIFIED',
    confidence TEXT NOT NULL DEFAULT 'Strong',
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS dependency_edges (
    edge_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    source_kind TEXT NOT NULL,
    source_id TEXT NOT NULL,
    source_label TEXT NOT NULL,
    target_kind TEXT NOT NULL,
    target_id TEXT NOT NULL,
    target_label TEXT NOT NULL,
    edge_type TEXT NOT NULL,
    confidence TEXT NOT NULL DEFAULT 'Confirmed',
    file_id TEXT REFERENCES source_files(file_id) ON DELETE SET NULL,
    line_number INTEGER NOT NULL DEFAULT 0,
    evidence TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS data_flows (
    flow_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    variable_id TEXT REFERENCES variables(variable_id) ON DELETE CASCADE,
    source_symbol TEXT NOT NULL,
    steps_json TEXT NOT NULL DEFAULT '[]',
    destination_symbol TEXT NOT NULL DEFAULT '',
    intended_consumer TEXT NOT NULL DEFAULT '',
    actual_consumer TEXT NOT NULL DEFAULT '',
    flow_status TEXT NOT NULL DEFAULT 'DISCONNECTED',
    confidence TEXT NOT NULL DEFAULT 'Inferred',
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS tests (
    test_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    file_id TEXT NOT NULL REFERENCES source_files(file_id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    framework TEXT NOT NULL,
    verification_category TEXT NOT NULL,
    target_feature TEXT NOT NULL DEFAULT '',
    target_elements_json TEXT NOT NULL DEFAULT '[]',
    assertion_count INTEGER NOT NULL DEFAULT 0,
    is_skipped INTEGER NOT NULL DEFAULT 0,
    skip_reason TEXT NOT NULL DEFAULT '',
    is_empty INTEGER NOT NULL DEFAULT 0,
    uses_mocks INTEGER NOT NULL DEFAULT 0,
    uses_real_hardware INTEGER NOT NULL DEFAULT 0,
    execution_command TEXT NOT NULL DEFAULT '',
    last_result TEXT NOT NULL DEFAULT 'NOT_RUN',
    last_output TEXT NOT NULL DEFAULT '',
    last_run_at TEXT NOT NULL DEFAULT '',
    line_number INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS compiler_warnings (
    warning_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    source_tool TEXT NOT NULL,
    code TEXT NOT NULL,
    severity TEXT NOT NULL,
    file_path TEXT NOT NULL,
    line_number INTEGER NOT NULL DEFAULT 0,
    message TEXT NOT NULL,
    is_baseline INTEGER NOT NULL DEFAULT 0,
    suppressed INTEGER NOT NULL DEFAULT 0,
    detected_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS suppressions (
    suppression_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    file_id TEXT REFERENCES source_files(file_id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,
    line_number INTEGER NOT NULL DEFAULT 0,
    symbol_name TEXT NOT NULL DEFAULT '',
    annotation_text TEXT NOT NULL,
    suppressed_rules_json TEXT NOT NULL DEFAULT '[]',
    is_authorized INTEGER NOT NULL DEFAULT 0,
    exception_id TEXT DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS baseline_snapshots (
    snapshot_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    snapshot_type TEXT NOT NULL,
    source_revision TEXT NOT NULL DEFAULT '',
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- APIDACSI Global Finding Lifecycle Tables
CREATE TABLE IF NOT EXISTS findings (
    finding_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    analyzer_id TEXT NOT NULL REFERENCES analyzers(analyzer_id),
    category TEXT NOT NULL,
    rule_id TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    severity TEXT NOT NULL,
    is_blocking INTEGER NOT NULL DEFAULT 0,
    is_baseline INTEGER NOT NULL DEFAULT 0,
    file_path TEXT NOT NULL DEFAULT '',
    line_number INTEGER NOT NULL DEFAULT 0,
    symbol_name TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL DEFAULT 'NEW',
    primary_classification TEXT DEFAULT NULL,
    confidence TEXT DEFAULT NULL,
    impact_category TEXT DEFAULT NULL,
    disposition TEXT DEFAULT NULL,
    evidence_freshness TEXT NOT NULL DEFAULT 'UNVERIFIED',
    root_cause_group_id TEXT DEFAULT NULL,
    first_seen_scan_id TEXT REFERENCES scans(scan_id),
    last_seen_scan_id TEXT REFERENCES scans(scan_id),
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    reopen_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS finding_observations (
    observation_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    scan_id TEXT NOT NULL REFERENCES scans(scan_id) ON DELETE CASCADE,
    analyzer_id TEXT NOT NULL REFERENCES analyzers(analyzer_id),
    file_path TEXT NOT NULL DEFAULT '',
    line_number INTEGER NOT NULL DEFAULT 0,
    symbol_name TEXT NOT NULL DEFAULT '',
    raw_message TEXT NOT NULL,
    raw_payload_json TEXT NOT NULL DEFAULT '{}',
    observed_at TEXT NOT NULL,
    UNIQUE(finding_id, scan_id, analyzer_id)
);

CREATE TABLE IF NOT EXISTS finding_fingerprints (
    fingerprint_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    signal_type TEXT NOT NULL,
    signal_value TEXT NOT NULL,
    weight INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS investigations (
    investigation_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    cycle_number INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'QUEUED',
    current_step INTEGER NOT NULL DEFAULT 0,
    completed_steps_json TEXT NOT NULL DEFAULT '[]',
    summary TEXT NOT NULL DEFAULT '',
    intended_purpose TEXT NOT NULL DEFAULT '',
    actual_behavior TEXT NOT NULL DEFAULT '',
    limitations TEXT NOT NULL DEFAULT '',
    source_revision TEXT NOT NULL DEFAULT '',
    started_at TEXT NOT NULL,
    completed_at TEXT DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS investigation_evidence (
    evidence_id TEXT PRIMARY KEY,
    investigation_id TEXT NOT NULL REFERENCES investigations(investigation_id) ON DELETE CASCADE,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    evidence_type TEXT NOT NULL,
    artifact_ref TEXT NOT NULL,
    source_revision TEXT NOT NULL DEFAULT '',
    symbol_or_location TEXT NOT NULL DEFAULT '',
    observation TEXT NOT NULL,
    supports_or_contradicts TEXT NOT NULL DEFAULT 'SUPPORTS',
    collection_method TEXT NOT NULL,
    confidence TEXT NOT NULL DEFAULT 'HIGH',
    limitations TEXT NOT NULL DEFAULT '',
    collected_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS investigation_references (
    reference_id TEXT PRIMARY KEY,
    investigation_id TEXT NOT NULL REFERENCES investigations(investigation_id) ON DELETE CASCADE,
    reference_kind TEXT NOT NULL,
    file_path TEXT NOT NULL DEFAULT '',
    line_number INTEGER NOT NULL DEFAULT 0,
    symbol_name TEXT NOT NULL DEFAULT '',
    confidence TEXT NOT NULL DEFAULT 'Confirmed',
    details TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS investigation_impact (
    impact_id TEXT PRIMARY KEY,
    investigation_id TEXT NOT NULL REFERENCES investigations(investigation_id) ON DELETE CASCADE,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    impact_category TEXT NOT NULL,
    affected_elements_json TEXT NOT NULL DEFAULT '[]',
    affected_features_json TEXT NOT NULL DEFAULT '[]',
    affected_flows_json TEXT NOT NULL DEFAULT '[]',
    basis TEXT NOT NULL,
    assessed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS finding_classifications (
    classification_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    investigation_id TEXT REFERENCES investigations(investigation_id) ON DELETE SET NULL,
    primary_classification TEXT NOT NULL,
    secondary_tags_json TEXT NOT NULL DEFAULT '[]',
    confidence TEXT NOT NULL,
    rationale TEXT NOT NULL,
    classified_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS finding_dispositions (
    disposition_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    disposition TEXT NOT NULL,
    rationale TEXT NOT NULL,
    actor TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS remediation_plans (
    plan_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    plan_revision INTEGER NOT NULL DEFAULT 1,
    finding_ids_json TEXT NOT NULL,
    investigation_ids_json TEXT NOT NULL DEFAULT '[]',
    root_cause TEXT NOT NULL,
    affected_files_json TEXT NOT NULL DEFAULT '[]',
    affected_symbols_json TEXT NOT NULL DEFAULT '[]',
    affected_features_json TEXT NOT NULL DEFAULT '[]',
    intended_behavior TEXT NOT NULL,
    current_behavior TEXT NOT NULL,
    required_behavior TEXT NOT NULL,
    proposed_changes TEXT NOT NULL,
    dependency_impact TEXT NOT NULL,
    compatibility_notes TEXT NOT NULL DEFAULT '',
    risks_and_side_effects TEXT NOT NULL DEFAULT '',
    required_tests_json TEXT NOT NULL DEFAULT '[]',
    rollback_strategy TEXT NOT NULL,
    approval_scope_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'DRAFT',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS remediation_approvals (
    approval_id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL REFERENCES remediation_plans(plan_id) ON DELETE CASCADE,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    plan_revision INTEGER NOT NULL,
    source_revision TEXT NOT NULL DEFAULT '',
    permitted_files_json TEXT NOT NULL DEFAULT '[]',
    permitted_actions_json TEXT NOT NULL DEFAULT '[]',
    approver TEXT NOT NULL,
    decision TEXT NOT NULL,
    restrictions TEXT NOT NULL DEFAULT '',
    approved_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS remediation_runs (
    run_id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL REFERENCES remediation_plans(plan_id) ON DELETE CASCADE,
    approval_id TEXT NOT NULL REFERENCES remediation_approvals(approval_id),
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    pre_baseline_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'STARTED',
    actor TEXT NOT NULL,
    deviations TEXT NOT NULL DEFAULT '',
    report_text TEXT NOT NULL DEFAULT '',
    started_at TEXT NOT NULL,
    completed_at TEXT DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS remediation_changes (
    change_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES remediation_runs(run_id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,
    symbol_name TEXT NOT NULL DEFAULT '',
    change_type TEXT NOT NULL,
    within_approved_scope INTEGER NOT NULL DEFAULT 1,
    recorded_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS verification_runs (
    verification_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    plan_id TEXT REFERENCES remediation_plans(plan_id) ON DELETE SET NULL,
    finding_id TEXT REFERENCES findings(finding_id) ON DELETE SET NULL,
    overall_status TEXT NOT NULL,
    executed_at TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS verification_results (
    result_id TEXT PRIMARY KEY,
    verification_id TEXT NOT NULL REFERENCES verification_runs(verification_id) ON DELETE CASCADE,
    finding_id TEXT REFERENCES findings(finding_id) ON DELETE CASCADE,
    category TEXT NOT NULL,
    command TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    uses_mocks INTEGER NOT NULL DEFAULT 0,
    uses_real_hardware INTEGER NOT NULL DEFAULT 0,
    output_summary TEXT NOT NULL DEFAULT '',
    limitations TEXT NOT NULL DEFAULT '',
    executed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS regression_reports (
    regression_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    scan_id TEXT REFERENCES scans(scan_id) ON DELETE SET NULL,
    plan_id TEXT REFERENCES remediation_plans(plan_id) ON DELETE SET NULL,
    new_findings_count INTEGER NOT NULL DEFAULT 0,
    resolved_findings_count INTEGER NOT NULL DEFAULT 0,
    reappearing_count INTEGER NOT NULL DEFAULT 0,
    broken_tests_count INTEGER NOT NULL DEFAULT 0,
    warning_delta INTEGER NOT NULL DEFAULT 0,
    passed INTEGER NOT NULL DEFAULT 0,
    details_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS closure_records (
    closure_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    cycle_number INTEGER NOT NULL DEFAULT 1,
    final_classification TEXT NOT NULL,
    final_disposition TEXT NOT NULL,
    root_cause_or_rationale TEXT NOT NULL,
    investigation_ids_json TEXT NOT NULL DEFAULT '[]',
    plan_id TEXT DEFAULT NULL,
    approval_id TEXT DEFAULT NULL,
    modified_files_json TEXT NOT NULL DEFAULT '[]',
    modified_symbols_json TEXT NOT NULL DEFAULT '[]',
    source_revision TEXT NOT NULL DEFAULT '',
    verification_summary_json TEXT NOT NULL DEFAULT '{}',
    regression_id TEXT DEFAULT NULL,
    resolution_evidence TEXT NOT NULL,
    actor TEXT NOT NULL,
    closed_at TEXT NOT NULL,
    final_state TEXT NOT NULL DEFAULT 'CLOSED',
    monitoring_status TEXT NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS finding_history (
    history_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    previous_state TEXT NOT NULL,
    new_state TEXT NOT NULL,
    source_revision TEXT NOT NULL DEFAULT '',
    evidence_refs_json TEXT NOT NULL DEFAULT '[]',
    approval_ref TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL,
    result TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS finding_relationships (
    rel_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    related_finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    relationship_type TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    UNIQUE(finding_id, related_finding_id, relationship_type)
);

CREATE TABLE IF NOT EXISTS evidence_dependencies (
    dep_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    investigation_id TEXT REFERENCES investigations(investigation_id) ON DELETE CASCADE,
    target_kind TEXT NOT NULL,
    target_key TEXT NOT NULL,
    target_hash TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS exceptions (
    exception_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    finding_id TEXT REFERENCES findings(finding_id) ON DELETE SET NULL,
    affected_element TEXT NOT NULL,
    exception_type TEXT NOT NULL,
    explanation TEXT NOT NULL,
    risk TEXT NOT NULL DEFAULT 'LOW',
    evidence TEXT NOT NULL,
    approver TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    follow_up TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS audit_events (
    event_id TEXT PRIMARY KEY,
    project_id TEXT REFERENCES projects(project_id) ON DELETE CASCADE,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target_id TEXT NOT NULL DEFAULT '',
    details_json TEXT NOT NULL DEFAULT '{}',
    result TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS change_history (
    change_record_id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    commit_id TEXT NOT NULL DEFAULT '',
    modified_elements_json TEXT NOT NULL DEFAULT '[]',
    impact_summary TEXT NOT NULL DEFAULT '',
    findings_introduced_json TEXT NOT NULL DEFAULT '[]',
    findings_resolved_json TEXT NOT NULL DEFAULT '[]',
    tests_executed_json TEXT NOT NULL DEFAULT '[]',
    compliance_result TEXT NOT NULL,
    recorded_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS doc_hashes (
    doc_name TEXT PRIMARY KEY,
    sha256 TEXT NOT NULL,
    generated_at TEXT NOT NULL
);

CREATE TRIGGER IF NOT EXISTS prevent_finding_history_update
BEFORE UPDATE ON finding_history
BEGIN
    SELECT RAISE(ABORT, 'finding_history is append-only');
END;

CREATE TRIGGER IF NOT EXISTS prevent_finding_history_delete
BEFORE DELETE ON finding_history
BEGIN
    SELECT RAISE(ABORT, 'finding_history is append-only');
END;

CREATE TRIGGER IF NOT EXISTS prevent_closure_records_update
BEFORE UPDATE ON closure_records
BEGIN
    SELECT RAISE(ABORT, 'closure_records is immutable');
END;

CREATE TRIGGER IF NOT EXISTS prevent_closure_records_delete
BEFORE DELETE ON closure_records
BEGIN
    SELECT RAISE(ABORT, 'closure_records is immutable');
END;

CREATE INDEX IF NOT EXISTS idx_files_rel ON source_files(project_id, rel_path);
CREATE INDEX IF NOT EXISTS idx_vars_name ON variables(project_id, name);
CREATE INDEX IF NOT EXISTS idx_funcs_name ON functions(project_id, name);
CREATE INDEX IF NOT EXISTS idx_types_name ON types(project_id, name);
CREATE INDEX IF NOT EXISTS idx_edges_src ON dependency_edges(project_id, source_id);
CREATE INDEX IF NOT EXISTS idx_edges_tgt ON dependency_edges(project_id, target_id);
CREATE INDEX IF NOT EXISTS idx_findings_state ON findings(project_id, state);
CREATE INDEX IF NOT EXISTS idx_findings_rule ON findings(project_id, rule_id, symbol_name);
CREATE INDEX IF NOT EXISTS idx_evdeps_key ON evidence_dependencies(target_key);
"""


class RegistryDB:
    """Thread-safe / process-safe SQLite wrapper enforcing atomic transitions & integrity."""

    def __init__(self, repo_root: Path, db_path: Optional[Path] = None):
        self.repo_root = Path(repo_root).resolve()
        self.runtime_dir = self.repo_root / ".apidacs"
        self.db_path = Path(db_path).resolve() if db_path else (self.runtime_dir / "apidacs.db")
        self.backup_dir = self.runtime_dir / "backups"
        self.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.backup_dir.mkdir(parents=True, exist_ok=True)

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path), timeout=30.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON;")
        conn.execute("PRAGMA busy_timeout = 10000;")
        conn.execute("PRAGMA journal_mode = WAL;")
        return conn

    def initialize(self) -> None:
        is_existing = self.db_path.exists() and self.db_path.stat().st_size > 0
        if is_existing:
            self.backup(reason="pre_migration")
        conn_ddl = self.connect()
        try:
            conn_ddl.executescript(SCHEMA_SQL)
        finally:
            conn_ddl.close()
        with self.transaction() as conn:
            existing = conn.execute(
                "SELECT version FROM schema_migrations WHERE version = ?", (SCHEMA_VERSION,)
            ).fetchone()
            if not existing:
                conn.execute(
                    "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
                    (SCHEMA_VERSION, "initial_apidacs_apidacsi_schema", utc_now()),
                )
            self._seed_default_analyzers(conn)

    def _seed_default_analyzers(self, conn: sqlite3.Connection) -> None:
        analyzers = [
            ("kotlin_psi_ast", "Kotlin PSI AST Analyzer", "2.4.21", "kotlin", ["ast", "psi", "compose", "swing", "tests", "suppressions"]),
            ("dataflow_engine", "APIDACS Data-Flow & Variable Lifecycle Analyzer", "1.0.0", "kotlin", ["variables", "propagation", "consumption"]),
            ("dependency_graph", "APIDACS Dependency & Contract Analyzer", "1.0.0", "multi", ["graph", "cycles", "contracts", "ui_backend"]),
            ("compiler_diagnostics", "Gradle/Kotlin Compiler Diagnostic Ingestor", "2.4.21", "kotlin", ["warnings", "errors", "baseline"]),
            ("android_lint", "Android Lint Diagnostic Ingestor", "8.x", "multi", ["lint", "manifest", "gradle"]),
            ("test_integrity", "APIDACS Test Integrity & Regression Analyzer", "1.0.0", "multi", ["junit", "robolectric", "mocks", "skipped"]),
            ("structured_config", "XML/TOML/Shell/JSON Config Analyzer", "1.0.0", "config", ["xml", "toml", "shell", "json"]),
        ]
        for aid, name, ver, lang, caps in analyzers:
            conn.execute(
                """
                INSERT OR IGNORE INTO analyzers (analyzer_id, name, version, language, capabilities_json)
                VALUES (?, ?, ?, ?, ?)
                """,
                (aid, name, ver, lang, json.dumps(caps)),
            )

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        conn = self.connect()
        try:
            conn.execute("BEGIN IMMEDIATE;")
            yield conn
            conn.execute("COMMIT;")
        except Exception:
            try:
                conn.execute("ROLLBACK;")
            except sqlite3.Error:
                pass
            raise
        finally:
            conn.close()

    def ensure_project(
        self,
        name: Optional[str] = None,
        enforcement_mode: str = EnforcementMode.MONITOR.value,
        source_revision: str = "",
    ) -> str:
        project_id = stable_id("proj", str(self.repo_root))
        proj_name = name or self.repo_root.name or "Samplfie2"
        now = utc_now()
        with self.transaction() as conn:
            row = conn.execute("SELECT project_id FROM projects WHERE project_id = ?", (project_id,)).fetchone()
            if not row:
                conn.execute(
                    """
                    INSERT INTO projects (project_id, name, root_path, enforcement_mode, source_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (project_id, proj_name, str(self.repo_root), enforcement_mode, source_revision, now, now),
                )
            else:
                conn.execute(
                    "UPDATE projects SET updated_at = ?, source_revision = COALESCE(NULLIF(?, ''), source_revision) WHERE project_id = ?",
                    (now, source_revision, project_id),
                )
        return project_id

    def set_enforcement_mode(self, project_id: str, mode: str, actor: str = "system") -> None:
        valid = {m.value for m in EnforcementMode}
        if mode not in valid:
            raise ValueError(f"Invalid enforcement mode '{mode}'. Valid: {sorted(valid)}")
        with self.transaction() as conn:
            conn.execute(
                "UPDATE projects SET enforcement_mode = ?, updated_at = ? WHERE project_id = ?",
                (mode, utc_now(), project_id),
            )
            self.record_audit(conn, project_id, actor, "SET_ENFORCEMENT_MODE", project_id, {"mode": mode}, "OK")

    def backup(self, reason: str = "manual") -> Optional[Path]:
        if not self.db_path.exists():
            return None
        ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        dest = self.backup_dir / f"apidacs_{ts}_{reason}.db"
        src_conn = sqlite3.connect(str(self.db_path))
        dst_conn = sqlite3.connect(str(dest))
        try:
            src_conn.backup(dst_conn)
        finally:
            dst_conn.close()
            src_conn.close()
        # Keep only the 15 most recent backups to avoid unbounded growth
        backups = sorted(self.backup_dir.glob("apidacs_*.db"), key=lambda p: p.stat().st_mtime)
        for old in backups[:-15]:
            try:
                old.unlink()
            except OSError:
                pass
        return dest

    def restore(self, backup_path: Path) -> None:
        backup_path = Path(backup_path).resolve()
        if not backup_path.exists():
            raise FileNotFoundError(f"Backup not found: {backup_path}")
        shutil.copy2(backup_path, self.db_path)

    def clear_structural_tables(self, conn: sqlite3.Connection, project_id: str) -> None:
        """Clears structural registries for clean rebuild while preserving findings/closures/history."""
        for tbl in (
            "data_flows",
            "dependency_edges",
            "ui_controls",
            "features",
            "tests",
            "suppressions",
            "variables",
            "functions",
            "types",
            "source_files",
        ):
            conn.execute(f"DELETE FROM {tbl} WHERE project_id = ?", (project_id,))

    def record_audit(
        self,
        conn: sqlite3.Connection,
        project_id: Optional[str],
        actor: str,
        action: str,
        target_id: str,
        details: Dict[str, Any],
        result: str,
    ) -> str:
        now = utc_now()
        eid = stable_id("evt", project_id or "global", actor, action, target_id, now, str(os.urandom(4).hex()))
        conn.execute(
            """
            INSERT INTO audit_events (event_id, project_id, actor, action, target_id, details_json, result, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (eid, project_id, actor, action, target_id, json.dumps(details, sort_keys=True), result, now),
        )
        return eid

    def validate(self, project_id: Optional[str] = None) -> Dict[str, Any]:
        """Validate SQLite integrity, FKs, duplicate IDs, orphaned edges, and stale source paths."""
        issues: List[Dict[str, str]] = []
        stats: Dict[str, int] = {}
        conn = self.connect()
        try:
            ic = conn.execute("PRAGMA integrity_check;").fetchone()
            if not ic or ic[0] != "ok":
                issues.append({"kind": "SQLITE_INTEGRITY", "detail": str(ic[0] if ic else "unknown")})

            fk_rows = conn.execute("PRAGMA foreign_key_check;").fetchall()
            for r in fk_rows:
                issues.append({"kind": "FOREIGN_KEY", "detail": str(dict(r))})

            # Check stale source files missing on disk
            q = "SELECT file_id, rel_path, sha256 FROM source_files"
            params: Tuple[Any, ...] = ()
            if project_id:
                q += " WHERE project_id = ?"
                params = (project_id,)
            files = conn.execute(q, params).fetchall()
            stale_paths = 0
            modified_paths = 0
            for f in files:
                fp = self.repo_root / f["rel_path"]
                if not fp.exists():
                    stale_paths += 1
                    issues.append({"kind": "MISSING_SOURCE_FILE", "detail": f["rel_path"]})
                else:
                    import hashlib
                    cur_hash = hashlib.sha256(fp.read_bytes()).hexdigest()
                    if cur_hash != f["sha256"]:
                        modified_paths += 1
                        issues.append({"kind": "UNSCANNED_SOURCE_CHANGE", "detail": f["rel_path"]})

            # Check invalid lifecycle states on closed findings lacking closure records
            closed_without_closure = conn.execute(
                """
                SELECT f.finding_id FROM findings f
                LEFT JOIN closure_records c ON c.finding_id = f.finding_id
                WHERE f.state = 'CLOSED' AND c.closure_id IS NULL
                """
            ).fetchall()
            for row in closed_without_closure:
                issues.append({"kind": "CLOSED_WITHOUT_RECORD", "detail": row["finding_id"]})

            for tbl in (
                "source_files",
                "types",
                "functions",
                "variables",
                "ui_controls",
                "features",
                "dependency_edges",
                "data_flows",
                "tests",
                "findings",
                "closure_records",
                "exceptions",
            ):
                stats[tbl] = conn.execute(f"SELECT COUNT(*) FROM {tbl}").fetchone()[0]

            stats["stale_source_paths"] = stale_paths
            stats["unscanned_modified_files"] = modified_paths
        finally:
            conn.close()

        return {
            "valid": len(issues) == 0,
            "issue_count": len(issues),
            "issues": issues,
            "stats": stats,
        }
