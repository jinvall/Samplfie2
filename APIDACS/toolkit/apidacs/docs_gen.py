"""Deterministic SQLite-derived Markdown documentation generator and drift validator (14 mandatory files)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

from .db import RegistryDB, utc_now
from .impact import analyze_impact, evaluate_compliance
from .models import MANDATORY_REPORT_FILES, sha256_bytes


def reports_dir(repo_root: Path) -> Path:
    return Path(repo_root).resolve() / "APIDACS" / "reports"


def generate_docs(db: RegistryDB, project_id: str) -> Dict[str, Any]:
    """Generate all 14 mandatory Markdown reports deterministically from authoritative SQLite tables."""
    rdir = reports_dir(db.repo_root)
    rdir.mkdir(parents=True, exist_ok=True)

    comp = evaluate_compliance(db, project_id)
    imp = analyze_impact(db, project_id)

    conn = db.connect()
    try:
        proj = dict(conn.execute("SELECT * FROM projects WHERE project_id = ?", (project_id,)).fetchone())
        last_scan_row = conn.execute(
            "SELECT * FROM scans WHERE project_id = ? ORDER BY started_at DESC LIMIT 1", (project_id,)
        ).fetchone()
        last_scan = dict(last_scan_row) if last_scan_row else {"scan_id": "NONE", "completed_at": "NONE", "scan_mode": "NONE", "source_revision": ""}
        anchor_ts = last_scan.get("completed_at") or proj.get("updated_at") or "UNSCANNED"

        files = [dict(r) for r in conn.execute("SELECT * FROM source_files WHERE project_id = ? ORDER BY rel_path", (project_id,)).fetchall()]
        edges = [dict(r) for r in conn.execute("SELECT * FROM dependency_edges WHERE project_id = ? ORDER BY source_label, edge_type, target_label, line_number", (project_id,)).fetchall()]
        variables = [dict(r) for r in conn.execute("SELECT * FROM variables WHERE project_id = ? ORDER BY qualified_name, line_number", (project_id,)).fetchall()]
        types_list = [dict(r) for r in conn.execute("SELECT * FROM types WHERE project_id = ? ORDER BY qualified_name, line_start", (project_id,)).fetchall()]
        functions = [dict(r) for r in conn.execute("SELECT * FROM functions WHERE project_id = ? ORDER BY qualified_name, line_start", (project_id,)).fetchall()]
        flows = [dict(r) for r in conn.execute("SELECT * FROM data_flows WHERE project_id = ? ORDER BY source_symbol", (project_id,)).fetchall()]
        features = [dict(r) for r in conn.execute("SELECT * FROM features WHERE project_id = ? ORDER BY name", (project_id,)).fetchall()]
        ui_controls = [dict(r) for r in conn.execute("SELECT * FROM ui_controls WHERE project_id = ? ORDER BY screen_name, line_number", (project_id,)).fetchall()]
        findings = [dict(r) for r in conn.execute("SELECT * FROM findings WHERE project_id = ? ORDER BY is_blocking DESC, state, finding_id", (project_id,)).fetchall()]
        warnings = [dict(r) for r in conn.execute("SELECT * FROM compiler_warnings WHERE project_id = ? ORDER BY is_baseline, file_path, line_number", (project_id,)).fetchall()]
        tests = [dict(r) for r in conn.execute("SELECT * FROM tests WHERE project_id = ? ORDER BY qualified_name", (project_id,)).fetchall()]
        exceptions = [dict(r) for r in conn.execute("SELECT * FROM exceptions WHERE project_id = ? ORDER BY exception_id", (project_id,)).fetchall()]
        closures = [dict(r) for r in conn.execute("SELECT * FROM closure_records WHERE project_id = ? ORDER BY closed_at DESC, closure_id", (project_id,)).fetchall()]
        ch_hist = [dict(r) for r in conn.execute("SELECT * FROM change_history WHERE project_id = ? ORDER BY recorded_at DESC, change_record_id", (project_id,)).fetchall()]
        remediations = [dict(r) for r in conn.execute("SELECT * FROM remediation_plans WHERE project_id = ? ORDER BY plan_id", (project_id,)).fetchall()]
    finally:
        conn.close()

    file_by_id = {f["file_id"]: f["rel_path"] for f in files}
    docs: Dict[str, str] = {}

    # 1. PROJECT_INTEGRITY.md
    docs["PROJECT_INTEGRITY.md"] = "\n".join([
        "# PROJECT_INTEGRITY",
        "",
        "Generated strictly from `.apidacs/apidacs.db` (Authoritative Registry). Do not edit manually.",
        "",
        "## System Overview",
        f"- **Project ID:** `{proj['project_id']}`",
        f"- **Project Name:** {proj['name']}",
        f"- **Enforcement Mode:** `{proj['enforcement_mode']}`",
        f"- **Compliance Decision:** `{comp['verdict']}`",
        f"- **Source Revision:** `{proj['source_revision'] or 'workspace'}`",
        f"- **Last Scan Anchor:** `{anchor_ts}` (`{last_scan['scan_id']}`)",
        "",
        "## Inventory & Lifecycle Summary",
        f"- Source Files: {len(files)}",
        f"- Types & Contracts: {len(types_list)}",
        f"- Functions & Methods: {len(functions)}",
        f"- Tracked Variables: {len(variables)}",
        f"- UI Controls: {len(ui_controls)}",
        f"- Dependency Edges: {len(edges)}",
        f"- Data Flows: {len(flows)}",
        f"- Automated Tests: {len(tests)}",
        f"- Total Findings: {len(findings)} (Closed: {len(closures)})",
        f"- Baseline Warnings: {comp['baseline_warnings']} | New Warning Regressions: {comp['new_warnings']}",
        "",
        "## Governing Rules",
        "- Never delete or weaken code/tests merely to silence a warning.",
        "- All source modifications require an approved APIDACSI remediation plan.",
        "- Markdown reports are derived artifacts; SQLite is authoritative.",
        "",
    ])

    # 2. DEPENDENCIES.md
    dep_lines = [
        "# DEPENDENCIES",
        "",
        f"Total directed dependency edges: {len(edges)}",
        "",
        "| Edge Type | Confidence | Source | Target | Evidence |",
        "|---|---|---|---|---|",
    ]
    for e in edges[:400]:
        dep_lines.append(
            f"| `{e['edge_type']}` | `{e['confidence']}` | `{_esc(e['source_label'])}` | `{_esc(e['target_label'])}` | `{_esc(e['evidence'][:60])}` |"
        )
    if len(edges) > 400:
        dep_lines.append(f"\n_Showing first 400 of {len(edges)} directed edges. Query SQLite or `./integrity dependencies` for full graph._")
    dep_lines.append("")
    docs["DEPENDENCIES.md"] = "\n".join(dep_lines)

    # 3. VARIABLES.md
    var_lines = [
        "# VARIABLES",
        "",
        f"Total tracked variables/properties/parameters: {len(variables)}",
        "",
        "| Name | Scope | Type | Mutability | Lifecycle Stage | Consumer | Confidence | Location |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for v in variables[:400]:
        rel = file_by_id.get(v["file_id"], "")
        var_lines.append(
            f"| `{_esc(v['qualified_name'])}` | `{v['scope']}` | `{_esc(v['declared_type'])}` | `{v['mutability']}` | `{v['lifecycle_stage']}` | `{_esc(v['actual_consumer'] or '-')}` | `{v['confidence']}` | `{rel}:{v['line_number']}` |"
        )
    if len(variables) > 400:
        var_lines.append(f"\n_Showing first 400 of {len(variables)} variables._")
    var_lines.append("")
    docs["VARIABLES.md"] = "\n".join(var_lines)

    # 4. TYPES.md
    type_lines = [
        "# TYPES",
        "",
        f"Total types, interfaces, enums & data classes: {len(types_list)}",
        "",
        "| Qualified Name | Kind | Visibility | Supertypes | Serialization | Location |",
        "|---|---|---|---|---|---|",
    ]
    for t in types_list[:400]:
        rel = file_by_id.get(t["file_id"], "")
        sts = ", ".join(json.loads(t["supertypes_json"] or "[]")) or "-"
        ser = "Yes" if t["serialization_contract"] else "No"
        type_lines.append(
            f"| `{_esc(t['qualified_name'])}` | `{t['kind']}` | `{t['visibility']}` | `{_esc(sts)}` | `{ser}` | `{rel}:{t['line_start']}` |"
        )
    type_lines.append("")
    docs["TYPES.md"] = "\n".join(type_lines)

    # 5. FUNCTIONS.md
    fn_lines = [
        "# FUNCTIONS",
        "",
        f"Total functions & methods: {len(functions)}",
        "",
        "| Qualified Name | Signature | Visibility | Framework/Entry | Unused Status | Location |",
        "|---|---|---|---|---|---|",
    ]
    for fn in functions[:400]:
        rel = file_by_id.get(fn["file_id"], "")
        fw = "Yes" if (fn["is_framework_managed"] or fn["is_entry_point"]) else "No"
        fn_lines.append(
            f"| `{_esc(fn['qualified_name'])}` | `{_esc(fn['signature'][:70])}` | `{fn['visibility']}` | `{fw}` | `{fn['unused_status']}` | `{rel}:{fn['line_start']}` |"
        )
    if len(functions) > 400:
        fn_lines.append(f"\n_Showing first 400 of {len(functions)} functions._")
    fn_lines.append("")
    docs["FUNCTIONS.md"] = "\n".join(fn_lines)

    # 6. DATA_FLOW.md
    flow_lines = [
        "# DATA_FLOW",
        "",
        f"Total tracked configuration/state data-flow chains: {len(flows)}",
        "",
        "| Source Symbol | Flow Status | Consumer | Confidence | Chain Steps |",
        "|---|---|---|---|---|",
    ]
    for fl in flows[:300]:
        steps = " -> ".join(json.loads(fl["steps_json"] or "[]"))
        flow_lines.append(
            f"| `{_esc(fl['source_symbol'])}` | `{fl['flow_status']}` | `{_esc(fl['actual_consumer'] or '-')}` | `{fl['confidence']}` | `{_esc(steps[:90])}` |"
        )
    flow_lines.append("")
    docs["DATA_FLOW.md"] = "\n".join(flow_lines)

    # 7. FEATURE_MAP.md
    feat_lines = [
        "# FEATURE_MAP",
        "",
        f"Total mapped application domains/features: {len(features)}",
        "",
        "| Feature | Status | Confidence | Entry Points | UI Controls | Functions | Tests |",
        "|---|---|---|---|---|---|---|",
    ]
    for ft in features:
        entries = len(json.loads(ft["entry_points_json"] or "[]"))
        uis = len(json.loads(ft["ui_controls_json"] or "[]"))
        fns = len(json.loads(ft["backend_functions_json"] or "[]"))
        tsts = len(json.loads(ft["test_ids_json"] or "[]"))
        feat_lines.append(
            f"| `{_esc(ft['name'])}` | `{ft['status']}` | `{ft['confidence']}` | {entries} | {uis} | {fns} | {tsts} |"
        )
    feat_lines.append("")
    docs["FEATURE_MAP.md"] = "\n".join(feat_lines)

    # 8. UI_BACKEND_MAP.md
    ui_lines = [
        "# UI_BACKEND_MAP",
        "",
        f"Total discovered Compose / Swing UI controls: {len(ui_controls)}",
        "",
        "| Screen / Composable | Component | Label | TestTag | Backend Target(s) | Status | Confidence | Location |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for u in ui_controls[:400]:
        rel = file_by_id.get(u["file_id"], "")
        targets = ", ".join(json.loads(u["backend_targets_json"] or "[]")) or "<NONE>"
        ui_lines.append(
            f"| `{_esc(u['screen_name'])}` | `{u['component_type']}` | `{_esc(u['control_label'])}` | `{_esc(u['test_tag'] or '-')}` | `{_esc(targets[:60])}` | `{u['status']}` | `{u['confidence']}` | `{rel}:{u['line_number']}` |"
        )
    ui_lines.append("")
    docs["UI_BACKEND_MAP.md"] = "\n".join(ui_lines)

    # 9. ISSUES.md
    iss_lines = [
        "# ISSUES",
        "",
        f"Persistent Findings: {len(findings)} | Compiler/Lint Diagnostics: {len(warnings)}",
        "",
        "## Finding Lifecycle Register",
        "| Finding ID | State | Blocking | Baseline | Classification | Confidence | Disposition | Location | Title |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for fnd in findings[:300]:
        iss_lines.append(
            f"| `{fnd['finding_id']}` | `{fnd['state']}` | `{bool(fnd['is_blocking'])}` | `{bool(fnd['is_baseline'])}` | `{fnd['primary_classification'] or '-'}` | `{fnd['confidence'] or '-'}` | `{fnd['disposition'] or '-'}` | `{fnd['file_path']}:{fnd['line_number']}` | {_esc(fnd['title'][:75])} |"
        )
    iss_lines.extend(["", "## Diagnostics Baseline vs Regressions", "| Tool | Code | Baseline | Location | Message |", "|---|---|---|---|---|"])
    for w in warnings[:200]:
        iss_lines.append(
            f"| `{w['source_tool']}` | `{w['code']}` | `{bool(w['is_baseline'])}` | `{w['file_path']}:{w['line_number']}` | {_esc(w['message'][:70])} |"
        )
    iss_lines.append("")
    docs["ISSUES.md"] = "\n".join(iss_lines)

    # 10. CHANGE_IMPACT.md
    imp_lines = [
        "# CHANGE_IMPACT",
        "",
        f"- **Baseline Present:** {imp['has_baseline']}",
        f"- **Blocking Impact Count:** {imp['blocking_count']}",
        f"- **Destructive Changes:** {len(imp['destructive_changes'])}",
        f"- **Dependency Direction Violations:** {len(imp['direction_violations'])}",
        "",
        "## Remediation Plans",
        "| Plan ID | Status | Findings | Affected Files |",
        "|---|---|---|---|",
    ]
    for rp in remediations:
        imp_lines.append(
            f"| `{rp['plan_id']}` | `{rp['status']}` | `{_esc(rp['finding_ids_json'])}` | `{_esc(rp['affected_files_json'])}` |"
        )
    imp_lines.append("")
    docs["CHANGE_IMPACT.md"] = "\n".join(imp_lines)

    # 11. TEST_COVERAGE.md
    tst_lines = [
        "# TEST_COVERAGE",
        "",
        f"Total registered tests: {len(tests)}",
        "",
        "| Qualified Name | Framework | Category | Assertions | Skipped | Empty | Mocks | Last Result |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for t in tests:
        tst_lines.append(
            f"| `{_esc(t['qualified_name'])}` | `{t['framework']}` | `{t['verification_category']}` | {t['assertion_count']} | `{bool(t['is_skipped'])}` | `{bool(t['is_empty'])}` | `{bool(t['uses_mocks'])}` | `{t['last_result']}` |"
        )
    tst_lines.append("")
    docs["TEST_COVERAGE.md"] = "\n".join(tst_lines)

    # 12. EXCEPTIONS.md
    exc_lines = [
        "# EXCEPTIONS",
        "",
        f"Registered narrow exceptions: {len(exceptions)}",
        "",
        "| Exception ID | Element | Type | Approver | Expires At | Status |",
        "|---|---|---|---|---|---|",
    ]
    for ex in exceptions:
        exc_lines.append(
            f"| `{ex['exception_id']}` | `{_esc(ex['affected_element'])}` | `{ex['exception_type']}` | `{ex['approver']}` | `{ex['expires_at']}` | `{ex['status']}` |"
        )
    exc_lines.append("")
    docs["EXCEPTIONS.md"] = "\n".join(exc_lines)

    # 13. REGISTRY_STATUS.md
    val = db.validate(project_id)
    reg_lines = [
        "# REGISTRY_STATUS",
        "",
        f"- **Database Path:** `.apidacs/apidacs.db`",
        f"- **Last Scan ID:** `{last_scan['scan_id']}`",
        f"- **Last Scan Status:** `{last_scan.get('status', 'NONE')}`",
        f"- **Registry Valid:** `{val['valid']}` (issues: {val['issue_count']})",
        f"- **Fresh Findings Pending/Investigating:** {sum(1 for x in findings if x['state'] in ('NEW', 'REGISTERED', 'PENDING', 'INVESTIGATING'))}",
        f"- **Stale/Reopened Findings:** {sum(1 for x in findings if x['state'] in ('STALE', 'REOPENED'))}",
        f"- **Closed Findings (Immutable History):** {len(closures)}",
        "",
    ]
    docs["REGISTRY_STATUS.md"] = "\n".join(reg_lines)

    # 14. CHANGE_HISTORY.md
    hist_lines = [
        "# CHANGE_HISTORY",
        "",
        f"Recorded integrity change events: {len(ch_hist)}",
        "",
        "| Record ID | Commit | Compliance Result | Recorded At | Impact Summary |",
        "|---|---|---|---|---|",
    ]
    for ch in ch_hist:
        hist_lines.append(
            f"| `{ch['change_record_id']}` | `{ch['commit_id'] or '-'}` | `{ch['compliance_result']}` | `{ch['recorded_at']}` | {_esc(ch['impact_summary'][:80])} |"
        )
    hist_lines.append("")
    docs["CHANGE_HISTORY.md"] = "\n".join(hist_lines)

    # Write files deterministically & record expected hashes
    written: List[str] = []
    with db.transaction() as conn:
        for fname in MANDATORY_REPORT_FILES:
            content = docs[fname]
            target = rdir / fname
            target.write_text(content, encoding="utf-8")
            written.append(str(target.relative_to(db.repo_root)))
            h = sha256_bytes(content.encode("utf-8"))
            conn.execute(
                """
                INSERT INTO doc_hashes (doc_name, sha256, generated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(doc_name) DO UPDATE SET sha256 = excluded.sha256, generated_at = excluded.generated_at
                """,
                (fname, h, anchor_ts),
            )
            if fname == "PROJECT_INTEGRITY.md":
                mirror = db.repo_root / "APIDACS" / "PROJECT_INTEGRITY.md"
                mirror.write_text(content, encoding="utf-8")

    return {"reports_dir": str(rdir), "files_written": written, "count": len(written)}


def validate_docs(db: RegistryDB) -> Dict[str, Any]:
    """Detect missing Markdown reports or manual drift away from authoritative SQLite hashes."""
    rdir = reports_dir(db.repo_root)
    conn = db.connect()
    try:
        stored = {
            r["doc_name"]: r["sha256"]
            for r in conn.execute("SELECT doc_name, sha256 FROM doc_hashes").fetchall()
        }
    finally:
        conn.close()

    drifted: List[Dict[str, str]] = []
    for fname in MANDATORY_REPORT_FILES:
        target = rdir / fname
        if not target.exists():
            drifted.append({"file": fname, "reason": "MISSING"})
            continue
        actual_hash = sha256_bytes(target.read_bytes())
        expected_hash = stored.get(fname)
        if not expected_hash:
            drifted.append({"file": fname, "reason": "NO_BASELINE_HASH"})
        elif actual_hash != expected_hash:
            drifted.append({"file": fname, "reason": "MANUAL_DRIFT_DETECTED"})

    mirror = db.repo_root / "APIDACS" / "PROJECT_INTEGRITY.md"
    if mirror.exists() and "PROJECT_INTEGRITY.md" in stored:
        if sha256_bytes(mirror.read_bytes()) != stored["PROJECT_INTEGRITY.md"]:
            drifted.append({"file": "APIDACS/PROJECT_INTEGRITY.md", "reason": "MANUAL_DRIFT_DETECTED"})

    return {
        "valid": len(drifted) == 0,
        "checked": len(MANDATORY_REPORT_FILES),
        "drifted": drifted,
    }


def _esc(val: Any) -> str:
    return str(val).replace("|", "\\|").replace("\n", " ")
