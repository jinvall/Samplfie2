"""Change-impact analysis, destructive-change blocking, dependency direction enforcement, exceptions, and compliance verdicts."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set

from .db import RegistryDB, utc_now
from .models import (
    ComplianceDecision,
    EnforcementMode,
    FindingState,
    stable_id,
)


# Architectural dependency-direction rules for Samplfie2:
# Domain/DSP/Audio/Network/Tools layers must NOT depend on UI screens/composables.
LAYER_DIRECTION_FORBIDDEN = [
    ("com/example/audio/", ("com.example.ui", "androidx.compose")),
    ("com/example/dsp/", ("com.example.ui", "androidx.compose")),
    ("com/example/drums/", ("com.example.ui", "androidx.compose")),
    ("com/example/stems/", ("com.example.ui", "androidx.compose")),
    ("com/example/tools/", ("com.example.ui", "androidx.compose")),
    ("desktop/src/main/kotlin/com/samplfie/desktop/audio/", ("com.samplfie.desktop.ui", "javax.swing")),
]


def snapshot_structural_baseline(db: RegistryDB, project_id: str) -> str:
    """Capture structural snapshot of functions, variables, types, callers, and tests for diffing edits."""
    conn = db.connect()
    try:
        funcs = [
            dict(r)
            for r in conn.execute(
                "SELECT function_id, qualified_name, signature, visibility, return_type, file_id FROM functions WHERE project_id = ?",
                (project_id,),
            ).fetchall()
        ]
        vars_list = [
            dict(r)
            for r in conn.execute(
                "SELECT variable_id, qualified_name, declared_type, is_hardcoded_literal, initial_value, lifecycle_stage FROM variables WHERE project_id = ?",
                (project_id,),
            ).fetchall()
        ]
        types_list = [
            dict(r)
            for r in conn.execute(
                "SELECT type_id, qualified_name, kind, supertypes_json FROM types WHERE project_id = ?",
                (project_id,),
            ).fetchall()
        ]
        call_edges = [
            dict(r)
            for r in conn.execute(
                "SELECT source_label, target_label FROM dependency_edges WHERE project_id = ? AND edge_type = 'CALLS'",
                (project_id,),
            ).fetchall()
        ]
        tests_list = [
            dict(r)
            for r in conn.execute(
                "SELECT qualified_name, assertion_count, is_skipped, is_empty FROM tests WHERE project_id = ?",
                (project_id,),
            ).fetchall()
        ]
        files_map = {
            r["file_id"]: r["rel_path"]
            for r in conn.execute("SELECT file_id, rel_path FROM source_files WHERE project_id = ?", (project_id,)).fetchall()
        }
    finally:
        conn.close()

    callers_map: Dict[str, List[str]] = {}
    for ce in call_edges:
        callers_map.setdefault(ce["target_label"], []).append(ce["source_label"])

    payload = {
        "functions": {
            f["qualified_name"]: {
                "signature": f["signature"],
                "file": files_map.get(f["file_id"], ""),
                "callers": sorted(set(callers_map.get(f["qualified_name"], []))),
            }
            for f in funcs
        },
        "variables": {v["qualified_name"]: v for v in vars_list},
        "types": {t["qualified_name"]: t for t in types_list},
        "tests": {t["qualified_name"]: t for t in tests_list},
    }
    snap_id = stable_id("snap", project_id, "STRUCTURAL_BASELINE", utc_now())
    with db.transaction() as conn:
        conn.execute(
            """
            INSERT INTO baseline_snapshots (snapshot_id, project_id, snapshot_type, payload_json, created_at)
            VALUES (?, ?, 'STRUCTURAL_BASELINE', ?, ?)
            """,
            (snap_id, project_id, json.dumps(payload, sort_keys=True), utc_now()),
        )
    return snap_id


def analyze_impact(
    db: RegistryDB,
    project_id: str,
    target_element: Optional[str] = None,
    changed_only: bool = False,
) -> Dict[str, Any]:
    """Compare current registry state against STRUCTURAL_BASELINE to catch destructive/risky changes."""
    conn = db.connect()
    try:
        base_row = conn.execute(
            """
            SELECT payload_json, created_at FROM baseline_snapshots
            WHERE project_id = ? AND snapshot_type = 'STRUCTURAL_BASELINE'
            ORDER BY created_at DESC LIMIT 1
            """,
            (project_id,),
        ).fetchone()
        cur_funcs = {
            r["qualified_name"]: dict(r)
            for r in conn.execute("SELECT * FROM functions WHERE project_id = ?", (project_id,)).fetchall()
        }
        cur_vars = {
            r["qualified_name"]: dict(r)
            for r in conn.execute("SELECT * FROM variables WHERE project_id = ?", (project_id,)).fetchall()
        }
        cur_types = {
            r["qualified_name"]: dict(r)
            for r in conn.execute("SELECT * FROM types WHERE project_id = ?", (project_id,)).fetchall()
        }
        cur_tests = {
            r["qualified_name"]: dict(r)
            for r in conn.execute("SELECT * FROM tests WHERE project_id = ?", (project_id,)).fetchall()
        }
        edges = [dict(r) for r in conn.execute("SELECT * FROM dependency_edges WHERE project_id = ?", (project_id,)).fetchall()]
        approved_files: Set[str] = set()
        for ar in conn.execute(
            "SELECT permitted_files_json FROM remediation_approvals WHERE project_id = ? AND decision = 'APPROVED'",
            (project_id,),
        ).fetchall():
            approved_files.update(json.loads(ar["permitted_files_json"] or "[]"))
    finally:
        conn.close()

    destructive_changes: List[Dict[str, Any]] = []
    direction_violations: List[Dict[str, Any]] = []

    # Check architecture dependency direction rules
    for e in edges:
        if e["edge_type"] != "IMPORTS":
            continue
        src = e["source_label"]
        tgt = e["target_label"]
        ev = e.get("evidence", "")
        imp_pkg = ev.replace("import ", "", 1).strip() if ev.startswith("import ") else tgt
        for path_prefix, forbidden_pkgs in LAYER_DIRECTION_FORBIDDEN:
            if src.startswith(path_prefix) and any(
                imp_pkg.startswith(fp) or fp.replace(".", "/") in tgt for fp in forbidden_pkgs
            ):
                direction_violations.append(
                    {
                        "source": src,
                        "target": imp_pkg,
                        "rule": f"Lower layer {path_prefix} must not import UI layer {imp_pkg}",
                        "line": e["line_number"],
                    }
                )

    if base_row:
        baseline = json.loads(base_row["payload_json"])
        base_funcs = baseline.get("functions", {})
        base_vars = baseline.get("variables", {})
        base_types = baseline.get("types", {})
        base_tests = baseline.get("tests", {})

        for fn_q, info in base_funcs.items():
            if fn_q not in cur_funcs:
                if target_element and target_element.lower() not in fn_q.lower():
                    continue
                callers = info.get("callers", [])
                if callers or info.get("file") not in approved_files:
                    destructive_changes.append(
                        {
                            "kind": "FUNCTION_DELETED",
                            "symbol": fn_q,
                            "file": info.get("file", ""),
                            "active_callers": callers,
                            "has_approval": info.get("file", "") in approved_files,
                            "blocking": bool(callers) or info.get("file", "") not in approved_files,
                        }
                    )
            else:
                if cur_funcs[fn_q]["signature"] != info["signature"]:
                    destructive_changes.append(
                        {
                            "kind": "SIGNATURE_CHANGED",
                            "symbol": fn_q,
                            "old_signature": info["signature"],
                            "new_signature": cur_funcs[fn_q]["signature"],
                            "has_approval": info.get("file", "") in approved_files,
                            "blocking": info.get("file", "") not in approved_files and bool(info.get("callers")),
                        }
                    )

        for v_q, v_info in base_vars.items():
            if v_q not in cur_vars and v_info.get("scope") in ("member", "top_level"):
                destructive_changes.append(
                    {
                        "kind": "TRACKED_VARIABLE_DELETED",
                        "symbol": v_q,
                        "blocking": True,
                    }
                )

        for t_q, t_info in base_types.items():
            if t_q not in cur_types:
                destructive_changes.append(
                    {
                        "kind": "TYPE_OR_CONTRACT_DELETED",
                        "symbol": t_q,
                        "blocking": True,
                    }
                )

        for test_q, t_info in base_tests.items():
            if test_q not in cur_tests:
                destructive_changes.append(
                    {
                        "kind": "TEST_REMOVED",
                        "symbol": test_q,
                        "blocking": True,
                    }
                )
            else:
                ct = cur_tests[test_q]
                if ct["assertion_count"] < t_info["assertion_count"] or (ct["is_skipped"] and not t_info["is_skipped"]):
                    destructive_changes.append(
                        {
                            "kind": "TEST_WEAKENED_OR_DISABLED",
                            "symbol": test_q,
                            "old_assertions": t_info["assertion_count"],
                            "new_assertions": ct["assertion_count"],
                            "blocking": True,
                        }
                    )

    if target_element:
        # Element-focused inspection across callers, callees, and variables
        matched_edges = [
            e for e in edges
            if target_element.lower() in e["source_label"].lower() or target_element.lower() in e["target_label"].lower()
        ]
    else:
        matched_edges = []

    return {
        "has_baseline": base_row is not None,
        "destructive_changes": destructive_changes,
        "direction_violations": direction_violations,
        "element_edges": matched_edges[:40],
        "blocking_count": sum(1 for d in destructive_changes if d.get("blocking")) + len(direction_violations),
    }


def add_exception(
    db: RegistryDB,
    project_id: str,
    affected_element: str,
    exception_type: str,
    explanation: str,
    evidence: str,
    approver: str,
    expires_at: str,
    follow_up: str = "",
    finding_id: Optional[str] = None,
    risk: str = "LOW",
) -> str:
    if "*" in affected_element or affected_element.strip() in ("", "all", "project"):
        raise ValueError("Blanket project-wide exceptions are prohibited; specify narrow affected element.")
    exc_id = stable_id("EXC", project_id, affected_element, exception_type, utc_now(), length=10).upper()
    with db.transaction() as conn:
        conn.execute(
            """
            INSERT INTO exceptions (
                exception_id, project_id, finding_id, affected_element, exception_type,
                explanation, risk, evidence, approver, created_at, expires_at, follow_up, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            """,
            (exc_id, project_id, finding_id, affected_element, exception_type, explanation, risk, evidence, approver, utc_now(), expires_at, follow_up),
        )
        if finding_id:
            conn.execute(
                "UPDATE suppressions SET is_authorized = 1, exception_id = ? WHERE project_id = ? AND symbol_name LIKE ?",
                (exc_id, project_id, f"%{affected_element}%"),
            )
    return exc_id


def check_expired_exceptions(db: RegistryDB, project_id: str) -> List[str]:
    now_dt = datetime.now(timezone.utc)
    expired_ids: List[str] = []
    with db.transaction() as conn:
        rows = conn.execute(
            "SELECT * FROM exceptions WHERE project_id = ? AND status = 'ACTIVE'",
            (project_id,),
        ).fetchall()
        for r in rows:
            try:
                exp_str = r["expires_at"].replace("Z", "+00:00")
                exp_dt = datetime.fromisoformat(exp_str)
                if exp_dt.tzinfo is None:
                    exp_dt = exp_dt.replace(tzinfo=timezone.utc)
                if exp_dt <= now_dt:
                    conn.execute("UPDATE exceptions SET status = 'EXPIRED' WHERE exception_id = ?", (r["exception_id"],))
                    expired_ids.append(r["exception_id"])
                    if r["finding_id"]:
                        conn.execute(
                            "UPDATE findings SET state = 'REVIEW_REQUIRED' WHERE finding_id = ?",
                            (r["finding_id"],),
                        )
            except Exception:
                continue
    return expired_ids


def evaluate_compliance(
    db: RegistryDB,
    project_id: str,
    strict: bool = False,
) -> Dict[str, Any]:
    """Compute final compliance verdict (PASS / PASS_WITH_WARNINGS / REVIEW_REQUIRED / BLOCKED / ANALYSIS_INCOMPLETE)."""
    val = db.validate(project_id)
    expired_excs = check_expired_exceptions(db, project_id)
    imp = analyze_impact(db, project_id)

    conn = db.connect()
    try:
        proj = conn.execute("SELECT * FROM projects WHERE project_id = ?", (project_id,)).fetchone()
        mode = proj["enforcement_mode"] if proj else EnforcementMode.MONITOR.value
        parse_errors = conn.execute(
            "SELECT COUNT(*) FROM source_files WHERE project_id = ? AND parse_status != 'OK'", (project_id,)
        ).fetchone()[0]
        new_warnings = conn.execute(
            "SELECT COUNT(*) FROM compiler_warnings WHERE project_id = ? AND is_baseline = 0", (project_id,)
        ).fetchone()[0]
        baseline_warnings = conn.execute(
            "SELECT COUNT(*) FROM compiler_warnings WHERE project_id = ? AND is_baseline = 1", (project_id,)
        ).fetchone()[0]
        blocking_findings = [
            dict(r)
            for r in conn.execute(
                """
                SELECT finding_id, rule_id, title, file_path, line_number, state
                FROM findings
                WHERE project_id = ? AND is_blocking = 1 AND is_baseline = 0 AND state != 'CLOSED'
                """,
                (project_id,),
            ).fetchall()
        ]
        review_findings = [
            dict(r)
            for r in conn.execute(
                """
                SELECT finding_id, rule_id, title, file_path, state
                FROM findings
                WHERE project_id = ? AND state IN ('REVIEW_REQUIRED', 'STALE', 'REOPENED') AND is_baseline = 0
                """,
                (project_id,),
            ).fetchall()
        ]
        failed_or_weak_tests = conn.execute(
            "SELECT COUNT(*) FROM tests WHERE project_id = ? AND (is_skipped = 1 OR is_empty = 1 OR assertion_count = 0 OR last_result = 'FAILED')",
            (project_id,),
        ).fetchone()[0]
        scans_cnt = conn.execute(
            "SELECT COUNT(*) FROM scans WHERE project_id = ? AND status = 'COMPLETED'", (project_id,)
        ).fetchone()[0]
    finally:
        conn.close()

    reasons: List[str] = []
    if scans_cnt == 0 or parse_errors > 0:
        verdict = ComplianceDecision.ANALYSIS_INCOMPLETE.value
        exit_code = 2
        if scans_cnt == 0:
            reasons.append("No completed source scan exists.")
        if parse_errors > 0:
            reasons.append(f"{parse_errors} file(s) failed AST/config parsing.")
    elif not val["valid"] and strict:
        verdict = ComplianceDecision.BLOCKED.value
        exit_code = 1
        reasons.append(f"Registry consistency errors: {val['issue_count']}")
    elif (
        imp["blocking_count"] > 0
        or len(blocking_findings) > 0
        or new_warnings > 0
        or failed_or_weak_tests > 0
    ):
        if mode == EnforcementMode.AUDIT.value and not strict:
            verdict = ComplianceDecision.REVIEW_REQUIRED.value
            exit_code = 3
        else:
            verdict = ComplianceDecision.BLOCKED.value
            exit_code = 1
        if imp["blocking_count"] > 0:
            reasons.append(f"{imp['blocking_count']} destructive/direction violation(s) detected.")
        if blocking_findings:
            reasons.append(f"{len(blocking_findings)} unresolved non-baseline blocking finding(s).")
        if new_warnings > 0:
            reasons.append(f"{new_warnings} newly introduced compiler/lint warning regression(s).")
        if failed_or_weak_tests > 0:
            reasons.append(f"{failed_or_weak_tests} skipped, empty, zero-assertion, or failing test(s).")
    elif review_findings or expired_excs or not val["valid"]:
        verdict = ComplianceDecision.REVIEW_REQUIRED.value
        exit_code = 3
        if review_findings:
            reasons.append(f"{len(review_findings)} finding(s) require human review or reassessment.")
        if expired_excs:
            reasons.append(f"{len(expired_excs)} exception(s) expired.")
        if not val["valid"]:
            reasons.append(f"Registry drift/unscanned files: {val['issue_count']}")
    elif baseline_warnings > 0:
        verdict = ComplianceDecision.PASS_WITH_WARNINGS.value
        exit_code = 0
        reasons.append(f"0 regressions; {baseline_warnings} pre-existing baseline warning(s) tracked.")
    else:
        verdict = ComplianceDecision.PASS.value
        exit_code = 0
        reasons.append("All integrity, data-flow, dependency, and test gates passed cleanly.")

    return {
        "verdict": verdict,
        "exit_code": exit_code,
        "enforcement_mode": mode,
        "reasons": reasons,
        "registry_valid": val["valid"],
        "destructive_changes": imp["destructive_changes"],
        "direction_violations": imp["direction_violations"],
        "blocking_findings": blocking_findings,
        "review_findings": review_findings,
        "new_warnings": new_warnings,
        "baseline_warnings": baseline_warnings,
        "failed_or_weak_tests": failed_or_weak_tests,
        "expired_exceptions": expired_excs,
    }
