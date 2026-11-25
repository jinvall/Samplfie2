"""Compiler warning baseline, Android Lint ingestion, JUnit result tracking, and test integrity checks."""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .db import RegistryDB, utc_now
from .models import stable_id

KOTLIN_WARNING_RE = re.compile(
    r"^w:\s+(?:file://)?(?P<path>[^:\n]+):(?P<line>\d+):(?P<col>\d+)\s+(?P<msg>.+)$"
)
LINT_HEADER_RE = re.compile(
    r"^(?P<path>[^:\n]+):(?P<line>\d+):\s+(?P<severity>Warning|Error|Information):\s+(?P<msg>.+?)\s+\[(?P<id>[^\]]+)\]\s*$"
)
LINT_FILELESS_RE = re.compile(
    r"^(?P<path>[^:\n]+):\s+(?P<severity>Warning|Error|Information):\s+(?P<msg>.+?)\s+\[(?P<id>[^\]]+)\]\s*$"
)


def _normalize_path(repo_root: Path, raw_path: str) -> str:
    p = raw_path.strip()
    root_str = str(repo_root.resolve())
    if p.startswith(root_str + "/"):
        return p[len(root_str) + 1 :]
    if p.startswith("/app/applet/"):
        return p[len("/app/applet/") :]
    return p.lstrip("./")


def parse_kotlin_compiler_log(repo_root: Path, log_text: str) -> List[Dict[str, Any]]:
    warnings: List[Dict[str, Any]] = []
    for line in log_text.splitlines():
        m = KOTLIN_WARNING_RE.match(line.strip())
        if m:
            warnings.append(
                {
                    "source_tool": "kotlinc",
                    "code": "KOTLIN_WARNING",
                    "severity": "WARNING",
                    "file_path": _normalize_path(repo_root, m.group("path")),
                    "line_number": int(m.group("line")),
                    "message": m.group("msg").strip(),
                }
            )
    return warnings


def parse_android_lint_report(repo_root: Path, lint_path: Path) -> List[Dict[str, Any]]:
    if not lint_path.exists():
        return []
    warnings: List[Dict[str, Any]] = []
    text = lint_path.read_text(encoding="utf-8", errors="replace")
    for line in text.splitlines():
        s = line.strip()
        m = LINT_HEADER_RE.match(s)
        if m:
            warnings.append(
                {
                    "source_tool": "android_lint",
                    "code": m.group("id"),
                    "severity": m.group("severity").upper(),
                    "file_path": _normalize_path(repo_root, m.group("path")),
                    "line_number": int(m.group("line")),
                    "message": m.group("msg").strip(),
                }
            )
            continue
        m2 = LINT_FILELESS_RE.match(s)
        if m2:
            warnings.append(
                {
                    "source_tool": "android_lint",
                    "code": m2.group("id"),
                    "severity": m2.group("severity").upper(),
                    "file_path": _normalize_path(repo_root, m2.group("path")),
                    "line_number": 0,
                    "message": m2.group("msg").strip(),
                }
            )
    return warnings


def _warning_key(w: Dict[str, Any]) -> str:
    # Message + normalized path + code without brittle line-number dependence
    return f"{w['source_tool']}::{w['code']}::{w['file_path']}::{w['message']}"


def ingest_diagnostics(
    db: RegistryDB,
    project_id: str,
    compiler_warnings: Optional[List[Dict[str, Any]]] = None,
    lint_report_path: Optional[Path] = None,
    establish_baseline: bool = False,
    lifecycle_callback: Any = None,
    scan_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Ingest real compiler and lint warnings, separating pre-existing baseline vs new regressions."""
    all_warnings: List[Dict[str, Any]] = list(compiler_warnings or [])
    default_lint = db.repo_root / "app" / "build" / "reports" / "lint-results-debug.txt"
    lp = lint_report_path or (default_lint if default_lint.exists() else None)
    if lp and lp.exists():
        all_warnings.extend(parse_android_lint_report(db.repo_root, lp))

    conn = db.connect()
    try:
        baseline_row = conn.execute(
            """
            SELECT payload_json FROM baseline_snapshots
            WHERE project_id = ? AND snapshot_type = 'WARNING_BASELINE'
            ORDER BY created_at DESC LIMIT 1
            """,
            (project_id,),
        ).fetchone()
        baseline_keys = set(json.loads(baseline_row["payload_json"]).get("keys", [])) if baseline_row else set()
    finally:
        conn.close()

    if establish_baseline or (not baseline_keys and all_warnings):
        baseline_keys = {_warning_key(w) for w in all_warnings}
        snap_id = stable_id("snap", project_id, "WARNING_BASELINE", utc_now())
        with db.transaction() as conn:
            conn.execute(
                """
                INSERT INTO baseline_snapshots (snapshot_id, project_id, snapshot_type, payload_json, created_at)
                VALUES (?, ?, 'WARNING_BASELINE', ?, ?)
                """,
                (
                    snap_id,
                    project_id,
                    json.dumps({"keys": sorted(baseline_keys), "warnings": all_warnings}, sort_keys=True),
                    utc_now(),
                ),
            )

    new_regressions: List[Dict[str, Any]] = []
    baseline_matched: List[Dict[str, Any]] = []
    raw_findings: List[Dict[str, Any]] = []

    with db.transaction() as conn:
        conn.execute("DELETE FROM compiler_warnings WHERE project_id = ?", (project_id,))
        for idx, w in enumerate(all_warnings):
            wk = _warning_key(w)
            is_base = wk in baseline_keys
            wid = stable_id("warn", project_id, wk, str(idx))
            conn.execute(
                """
                INSERT INTO compiler_warnings (
                    warning_id, project_id, source_tool, code, severity,
                    file_path, line_number, message, is_baseline, suppressed, detected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                (
                    wid, project_id, w["source_tool"], w["code"], w["severity"],
                    w["file_path"], w["line_number"], w["message"], int(is_base), utc_now(),
                ),
            )
            if is_base:
                baseline_matched.append(w)
            else:
                new_regressions.append(w)
                raw_findings.append(
                    {
                        "analyzer_id": "compiler_diagnostics" if w["source_tool"] == "kotlinc" else "android_lint",
                        "category": "WARNING_REGRESSION",
                        "rule_id": f"NEW_{w['code']}",
                        "title": f"New warning regression in {w['file_path']}:{w['line_number']}",
                        "description": w["message"],
                        "severity": "HIGH",
                        "is_blocking": True,
                        "file_path": w["file_path"],
                        "line_number": w["line_number"],
                        "symbol_name": f"{w['file_path']}::{w['code']}::{w['message'][:48]}",
                        "is_baseline": False,
                    }
                )
            if is_base and w["source_tool"] == "kotlinc":
                raw_findings.append(
                    {
                        "analyzer_id": "compiler_diagnostics",
                        "category": "COMPILER_WARNING",
                        "rule_id": w["code"],
                        "title": f"Baseline compiler warning in {w['file_path']}:{w['line_number']}",
                        "description": w["message"],
                        "severity": "LOW",
                        "is_blocking": False,
                        "file_path": w["file_path"],
                        "line_number": w["line_number"],
                        "symbol_name": f"{w['file_path']}::{w['message'][:48]}",
                        "is_baseline": True,
                    }
                )

    if lifecycle_callback is not None and raw_findings:
        lifecycle_callback(
            db=db,
            project_id=project_id,
            scan_id=scan_id,
            observations=raw_findings,
            changed_paths=set(),
        )

    return {
        "total_warnings": len(all_warnings),
        "baseline_count": len(baseline_matched),
        "new_regression_count": len(new_regressions),
        "new_regressions": new_regressions,
        "baseline_warnings": baseline_matched,
    }


def snapshot_test_baseline(db: RegistryDB, project_id: str) -> str:
    """Record assertion counts and active tests to detect subsequent test weakening/removal."""
    conn = db.connect()
    try:
        rows = conn.execute(
            "SELECT qualified_name, assertion_count, is_skipped, is_empty FROM tests WHERE project_id = ?",
            (project_id,),
        ).fetchall()
        payload = {
            r["qualified_name"]: {
                "assertion_count": r["assertion_count"],
                "is_skipped": bool(r["is_skipped"]),
                "is_empty": bool(r["is_empty"]),
            }
            for r in rows
        }
    finally:
        conn.close()

    snap_id = stable_id("snap", project_id, "TEST_BASELINE", utc_now())
    with db.transaction() as conn:
        conn.execute(
            """
            INSERT INTO baseline_snapshots (snapshot_id, project_id, snapshot_type, payload_json, created_at)
            VALUES (?, ?, 'TEST_BASELINE', ?, ?)
            """,
            (snap_id, project_id, json.dumps(payload, sort_keys=True), utc_now()),
        )
    return snap_id


def audit_test_integrity(db: RegistryDB, project_id: str) -> Dict[str, Any]:
    """Inspect tests for disabled, empty, weakened assertions, mock limitations, and JUnit results."""
    ingest_junit_results(db, project_id)
    conn = db.connect()
    try:
        tests = [dict(r) for r in conn.execute("SELECT * FROM tests WHERE project_id = ? ORDER BY qualified_name", (project_id,)).fetchall()]
        base_row = conn.execute(
            """
            SELECT payload_json FROM baseline_snapshots
            WHERE project_id = ? AND snapshot_type = 'TEST_BASELINE'
            ORDER BY created_at DESC LIMIT 1
            """,
            (project_id,),
        ).fetchone()
        baseline = json.loads(base_row["payload_json"]) if base_row else {}
    finally:
        conn.close()

    weakened: List[Dict[str, Any]] = []
    skipped: List[Dict[str, Any]] = []
    empty_or_no_assert: List[Dict[str, Any]] = []
    mock_limited: List[Dict[str, Any]] = []

    current_names = {t["qualified_name"] for t in tests}
    removed_tests = sorted(set(baseline.keys()) - current_names)

    for t in tests:
        if t["is_skipped"]:
            skipped.append(t)
        if t["is_empty"] or t["assertion_count"] == 0:
            empty_or_no_assert.append(t)
        if t["uses_mocks"]:
            mock_limited.append(t)
        prev = baseline.get(t["qualified_name"])
        if prev and t["assertion_count"] < prev["assertion_count"]:
            weakened.append(
                {
                    "qualified_name": t["qualified_name"],
                    "previous_assertions": prev["assertion_count"],
                    "current_assertions": t["assertion_count"],
                }
            )

    return {
        "total_tests": len(tests),
        "skipped_count": len(skipped),
        "empty_or_zero_assert_count": len(empty_or_no_assert),
        "weakened_count": len(weakened),
        "removed_count": len(removed_tests),
        "mock_limited_count": len(mock_limited),
        "skipped": [t["qualified_name"] for t in skipped],
        "empty_or_zero_assert": [t["qualified_name"] for t in empty_or_no_assert],
        "weakened": weakened,
        "removed": removed_tests,
        "mock_limited": [t["qualified_name"] for t in mock_limited],
        "passed": len(skipped) == 0 and len(empty_or_no_assert) == 0 and len(weakened) == 0 and len(removed_tests) == 0,
    }


def ingest_junit_results(db: RegistryDB, project_id: str) -> int:
    """Parse Gradle JUnit XML reports if present on disk and update test execution outcomes."""
    result_dirs = [
        db.repo_root / "app" / "build" / "test-results",
        db.repo_root / "desktop" / "build" / "test-results",
    ]
    outcomes: Dict[str, Tuple[str, str]] = {}
    for rdir in result_dirs:
        if not rdir.exists():
            continue
        for xml_file in rdir.rglob("TEST-*.xml"):
            try:
                root = ET.parse(xml_file).getroot()
                for tc in root.findall("testcase"):
                    cls = tc.attrib.get("classname", "")
                    name = tc.attrib.get("name", "")
                    qname = f"{cls}.{name}" if cls else name
                    if tc.find("skipped") is not None:
                        outcomes[qname] = ("SKIPPED", "Marked skipped in JUnit report")
                    elif tc.find("failure") is not None or tc.find("error") is not None:
                        err = tc.find("failure") or tc.find("error")
                        msg = (err.attrib.get("message", "") if err is not None else "")[:200]
                        outcomes[qname] = ("FAILED", msg)
                    else:
                        outcomes[qname] = ("PASSED", f"time={tc.attrib.get('time', '0')}s")
            except Exception:
                continue

    if not outcomes:
        return 0
    updated = 0
    now = utc_now()
    with db.transaction() as conn:
        rows = conn.execute("SELECT test_id, qualified_name FROM tests WHERE project_id = ?", (project_id,)).fetchall()
        for r in rows:
            res = outcomes.get(r["qualified_name"])
            if res:
                conn.execute(
                    "UPDATE tests SET last_result = ?, last_output = ?, last_run_at = ? WHERE test_id = ?",
                    (res[0], res[1], now, r["test_id"]),
                )
                updated += 1
    return updated
