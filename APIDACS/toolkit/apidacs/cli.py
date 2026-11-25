"""Unified CLI implementing all `integrity` (APIDACS) and `apidacs` (APIDACSI) commands."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from .analyzer import query_graph, run_scan
from .db import RegistryDB, utc_now
from .diagnostics import (
    audit_test_integrity,
    ingest_diagnostics,
    parse_kotlin_compiler_log,
    snapshot_test_baseline,
)
from .docs_gen import generate_docs, validate_docs
from .git_ci import (
    ensure_ci_workflow,
    install_git_hooks,
    run_pre_commit,
    run_pre_push,
)
from .impact import (
    add_exception,
    analyze_impact,
    check_expired_exceptions,
    evaluate_compliance,
    snapshot_structural_baseline,
)
from .lifecycle import (
    approve_remediation_plan,
    close_finding,
    create_remediation_plan,
    execute_remediation_plan,
    investigate_batch,
    investigate_finding,
    reconcile_and_ingest,
    record_verification,
    reopen_finding,
    run_regression_comparison,
    validate_resolution,
)
from .models import Disposition, EvidenceConfidence, PrimaryClassification


def _emit(data: Any, as_json: bool = False) -> None:
    if as_json:
        print(json.dumps(data, indent=2, sort_keys=True))
    elif isinstance(data, str):
        print(data)
    else:
        print(json.dumps(data, indent=2, sort_keys=True))


def _project_ctx(db: RegistryDB) -> str:
    db.initialize()
    return db.ensure_project()


def main(argv: Optional[List[str]] = None, prog_name: Optional[str] = None) -> int:
    parser = argparse.ArgumentParser(
        prog=prog_name or "integrity",
        description="APIDACS & APIDACSI Unified Integrity & Global Finding Lifecycle CLI",
    )
    parser.add_argument("--repo", default=".", help="Repository root path")
    parser.add_argument("--db", default=None, help="Override SQLite database path")
    parser.add_argument("--json", action="store_true", help="Output structured JSON")

    sub = parser.add_subparsers(dest="group", required=True)

    # ---------------- APIDACS (`integrity`) ----------------
    p_scan = sub.add_parser("scan", help="Scan repository source files")
    p_scan.add_argument("--changed", action="store_true", help="Incremental scan for modified files & neighborhood")

    p_reg = sub.add_parser("registry", help="Registry maintenance")
    p_reg.add_argument("action", choices=["rebuild", "validate", "reconcile", "backup"])

    p_vars = sub.add_parser("variables", help="Variable & data-flow lifecycle")
    p_vars.add_argument("action", choices=["list", "inspect", "trace", "audit"])
    p_vars.add_argument("target", nargs="?", default=None)

    p_funcs = sub.add_parser("functions", help="Function & caller registry")
    p_funcs.add_argument("action", choices=["list", "inspect", "audit"])
    p_funcs.add_argument("target", nargs="?", default=None)

    p_types = sub.add_parser("types", help="Types & interface contract registry")
    p_types.add_argument("action", choices=["list", "inspect", "audit"])
    p_types.add_argument("target", nargs="?", default=None)

    p_deps = sub.add_parser("dependencies", help="Directed dependency graph")
    p_deps.add_argument("action", choices=["graph", "trace", "upstream", "downstream", "cycles"])
    p_deps.add_argument("target", nargs="?", default=None)

    p_feat = sub.add_parser("features", help="Feature mapping & status")
    p_feat.add_argument("action", choices=["list", "inspect", "audit"])
    p_feat.add_argument("target", nargs="?", default=None)

    p_ui = sub.add_parser("ui", help="UI-to-backend mapping")
    p_ui.add_argument("action", choices=["audit", "trace"])
    p_ui.add_argument("target", nargs="?", default=None)

    p_imp = sub.add_parser("impact", help="Change-impact & destructive edit analyzer")
    p_imp.add_argument("action", choices=["analyze", "inspect", "snapshot"])
    p_imp.add_argument("target", nargs="?", default=None)
    p_imp.add_argument("--changed", action="store_true")

    p_warn = sub.add_parser("warnings", help="Compiler & lint warning baseline manager")
    p_warn.add_argument("action", choices=["list", "baseline", "audit"])
    p_warn.add_argument("--establish", action="store_true")
    p_warn.add_argument("--kotlinc-log", default=None)
    p_warn.add_argument("--lint-report", default=None)

    p_tst = sub.add_parser("tests", help="Test integrity & verification checks")
    p_tst.add_argument("action", choices=["audit", "verify", "snapshot"])
    p_tst.add_argument("target", nargs="?", default=None)

    p_comp = sub.add_parser("compliance", help="Compliance verdicts & enforcement mode")
    p_comp.add_argument("action", choices=["check", "report", "mode"])
    p_comp.add_argument("target", nargs="?", default=None)
    p_comp.add_argument("--strict", action="store_true")

    p_docs = sub.add_parser("docs", help="Deterministic Markdown report generator & drift validator")
    p_docs.add_argument("action", choices=["generate", "validate"])

    sub.add_parser("audit", help="Run full project integrity audit end-to-end")

    p_git = sub.add_parser("git", help="Git hook & CI enforcement")
    p_git.add_argument("action", choices=["install-hooks", "pre-commit", "pre-push", "write-ci"])

    p_exc = sub.add_parser("exceptions", help="Narrow time-bound exceptions")
    p_exc.add_argument("action", choices=["list", "add", "check"])
    p_exc.add_argument("--element", default="")
    p_exc.add_argument("--type", default="KNOWN_LIMITATION")
    p_exc.add_argument("--explanation", default="")
    p_exc.add_argument("--evidence", default="")
    p_exc.add_argument("--approver", default="")
    p_exc.add_argument("--expires", default="2099-01-01T00:00:00Z")
    p_exc.add_argument("--finding-id", default=None)

    # ---------------- APIDACSI (`apidacs`) ----------------
    p_fnd = sub.add_parser("findings", help="Global Finding Lifecycle operations")
    p_fnd.add_argument(
        "action",
        choices=[
            "status", "list", "inspect", "history", "reconcile",
            "classify", "impact", "relationships", "validate-resolution",
            "close", "monitor", "reopen", "report",
        ],
    )
    p_fnd.add_argument("target", nargs="?", default=None)
    p_fnd.add_argument("--status", default=None)
    p_fnd.add_argument("--classification", default=None)
    p_fnd.add_argument("--confidence", default=None)
    p_fnd.add_argument("--disposition", default=None)
    p_fnd.add_argument("--rationale", default="")
    p_fnd.add_argument("--project", default=None)
    p_fnd.add_argument("--history", action="store_true")
    p_fnd.add_argument("--actor", default="apidacs_operator")
    p_fnd.add_argument("--evidence", default="Verified via APIDACS lifecycle resolution validation.")
    p_fnd.add_argument("--reason", default="Manual monitoring reopen")
    p_fnd.add_argument("--condition-absent", action="store_true", default=True)

    p_inv = sub.add_parser("investigate", help="Read-only 8-step investigation engine")
    p_inv.add_argument(
        "action",
        choices=["status", "list", "finding", "all", "batch", "resume", "stale", "unresolved", "evidence", "trace"],
    )
    p_inv.add_argument("target", nargs="?", default=None)
    p_inv.add_argument("--stop-after-step", type=int, default=None)
    p_inv.add_argument("--limit", type=int, default=None)

    p_rem = sub.add_parser("remediation", help="Plan, approve & execute source remediation")
    p_rem.add_argument(
        "action",
        choices=["plan", "plans", "inspect", "approve", "execute", "status", "history"],
    )
    p_rem.add_argument("target", nargs="?", default=None)
    p_rem.add_argument("--changes", default="")
    p_rem.add_argument("--required", default="")
    p_rem.add_argument("--files", default="")
    p_rem.add_argument("--symbols", default="")
    p_rem.add_argument("--approver", default="")
    p_rem.add_argument("--actor", default="coding_agent")
    p_rem.add_argument("--deviations", default="")

    p_ver = sub.add_parser("verify", help="Verification categories & regression analysis")
    p_ver.add_argument("action", choices=["finding", "remediation", "regressions"])
    p_ver.add_argument("target", nargs="?", default=None)
    p_ver.add_argument("--category", default="Unit testing")
    p_ver.add_argument("--status", default="PASSED")
    p_ver.add_argument("--command", default="unittest")
    p_ver.add_argument("--summary", default="Verified")
    p_ver.add_argument("--mocks", action="store_true")

    args = parser.parse_args(argv)
    repo_root = Path(args.repo).resolve()
    db_path = Path(args.db).resolve() if args.db else None
    db = RegistryDB(repo_root=repo_root, db_path=db_path)

    try:
        return _dispatch(args, db)
    except PermissionError as exc:
        _emit({"error": str(exc), "blocked": True}, args.json)
        return 1
    except ValueError as exc:
        _emit({"error": str(exc)}, args.json)
        return 2
    except Exception as exc:
        _emit({"error": f"{type(exc).__name__}: {exc}"}, args.json)
        return 2


def _dispatch(args: argparse.Namespace, db: RegistryDB) -> int:
    grp = args.group
    pid = _project_ctx(db)

    if grp == "scan":
        mode = "changed" if args.changed else "full"
        res = run_scan(db, mode=mode, trigger="cli_scan", lifecycle_callback=reconcile_and_ingest)
        _emit(res, args.json)
        return 0

    if grp == "registry":
        if args.action == "backup":
            b = db.backup("manual")
            _emit({"backup_path": str(b) if b else None}, args.json)
            return 0
        if args.action == "rebuild":
            db.backup("pre_rebuild")
            res = run_scan(db, mode="full", trigger="registry_rebuild", lifecycle_callback=reconcile_and_ingest)
            generate_docs(db, pid)
            _emit({"rebuilt": True, **res}, args.json)
            return 0
        if args.action == "reconcile":
            res = run_scan(db, mode="changed", trigger="registry_reconcile", lifecycle_callback=reconcile_and_ingest)
            generate_docs(db, pid)
            _emit({"reconciled": True, **res}, args.json)
            return 0
        if args.action == "validate":
            val = db.validate(pid)
            _emit(val, args.json)
            return 0 if val["valid"] else 1

    if grp == "variables":
        conn = db.connect()
        try:
            if args.action == "list":
                rows = [dict(r) for r in conn.execute("SELECT variable_id, qualified_name, scope, declared_type, lifecycle_stage, actual_consumer FROM variables WHERE project_id = ? ORDER BY qualified_name", (pid,)).fetchall()]
                _emit(rows, args.json)
                return 0
            if args.action == "inspect":
                if not args.target:
                    raise ValueError("Target variable name required.")
                rows = [dict(r) for r in conn.execute("SELECT * FROM variables WHERE project_id = ? AND (name = ? OR qualified_name LIKE ?)", (pid, args.target, f"%{args.target}%")).fetchall()]
                _emit(rows, args.json)
                return 0 if rows else 2
            if args.action == "trace":
                if not args.target:
                    raise ValueError("Target variable name required.")
                flows = [dict(r) for r in conn.execute("SELECT * FROM data_flows WHERE project_id = ? AND source_symbol LIKE ?", (pid, f"%{args.target}%")).fetchall()]
                vars_m = [dict(r) for r in conn.execute("SELECT qualified_name, lifecycle_stage, initial_value, actual_consumer, call_sites_json FROM variables WHERE project_id = ? AND (name = ? OR qualified_name LIKE ?)", (pid, args.target, f"%{args.target}%")).fetchall()]
                _emit({"query": args.target, "data_flows": flows, "variables": vars_m}, args.json)
                return 0
            if args.action == "audit":
                rows = [dict(r) for r in conn.execute("SELECT qualified_name, scope, lifecycle_stage, actual_consumer, line_number FROM variables WHERE project_id = ? AND lifecycle_stage IN ('DECLARED', 'INITIALIZED', 'ASSIGNED', 'DISCONNECTED', 'UNRESOLVED') ORDER BY lifecycle_stage, qualified_name", (pid,)).fetchall()]
                _emit({"issue_variables_count": len(rows), "variables": rows}, args.json)
                return 0
        finally:
            conn.close()

    if grp == "functions":
        conn = db.connect()
        try:
            if args.action == "list":
                rows = [dict(r) for r in conn.execute("SELECT function_id, qualified_name, signature, visibility, unused_status FROM functions WHERE project_id = ? ORDER BY qualified_name", (pid,)).fetchall()]
                _emit(rows, args.json)
                return 0
            if args.action == "inspect":
                if not args.target:
                    raise ValueError("Target function name required.")
                rows = [dict(r) for r in conn.execute("SELECT * FROM functions WHERE project_id = ? AND (name = ? OR qualified_name LIKE ?)", (pid, args.target, f"%{args.target}%")).fetchall()]
                _emit(rows, args.json)
                return 0 if rows else 2
            if args.action == "audit":
                rows = [dict(r) for r in conn.execute("SELECT qualified_name, visibility, unused_status, ignored_params_json FROM functions WHERE project_id = ? AND (unused_status != 'USED' OR ignored_params_json != '[]')", (pid,)).fetchall()]
                _emit({"flagged_functions_count": len(rows), "functions": rows}, args.json)
                return 0
        finally:
            conn.close()

    if grp == "types":
        conn = db.connect()
        try:
            if args.action == "list":
                rows = [dict(r) for r in conn.execute("SELECT type_id, qualified_name, kind, visibility, supertypes_json FROM types WHERE project_id = ? ORDER BY qualified_name", (pid,)).fetchall()]
                _emit(rows, args.json)
                return 0
            if args.action == "inspect":
                if not args.target:
                    raise ValueError("Target type name required.")
                rows = [dict(r) for r in conn.execute("SELECT * FROM types WHERE project_id = ? AND (name = ? OR qualified_name LIKE ?)", (pid, args.target, f"%{args.target}%")).fetchall()]
                _emit(rows, args.json)
                return 0 if rows else 2
            if args.action == "audit":
                contracts = [dict(r) for r in conn.execute("SELECT * FROM findings WHERE project_id = ? AND category = 'CONTRACT_VIOLATION'", (pid,)).fetchall()]
                _emit({"contract_violations": contracts, "count": len(contracts)}, args.json)
                return 1 if contracts else 0
        finally:
            conn.close()

    if grp == "dependencies":
        if args.action == "graph":
            conn = db.connect()
            try:
                rows = [dict(r) for r in conn.execute("SELECT edge_type, source_label, target_label, confidence FROM dependency_edges WHERE project_id = ? ORDER BY source_label", (pid,)).fetchall()]
                _emit({"edge_count": len(rows), "edges": rows}, args.json)
                return 0
            finally:
                conn.close()
        if args.action == "cycles":
            from .analyzer import detect_cycles
            conn = db.connect()
            try:
                edges = [dict(r) for r in conn.execute("SELECT * FROM dependency_edges WHERE project_id = ?", (pid,)).fetchall()]
            finally:
                conn.close()
            cycs = detect_cycles(edges)
            _emit({"cycle_count": len(cycs), "cycles": cycs}, args.json)
            return 1 if cycs else 0
        if not args.target:
            raise ValueError("Element argument required.")
        direction = "both" if args.action == "trace" else args.action
        _emit(query_graph(db, args.target, direction=direction), args.json)
        return 0

    if grp == "features":
        conn = db.connect()
        try:
            if args.action == "list":
                rows = [dict(r) for r in conn.execute("SELECT feature_id, name, status, confidence FROM features WHERE project_id = ? ORDER BY name", (pid,)).fetchall()]
                _emit(rows, args.json)
                return 0
            if args.action == "inspect":
                if not args.target:
                    raise ValueError("Feature name required.")
                rows = [dict(r) for r in conn.execute("SELECT * FROM features WHERE project_id = ? AND name LIKE ?", (pid, f"%{args.target}%")).fetchall()]
                _emit(rows, args.json)
                return 0 if rows else 2
            if args.action == "audit":
                rows = [dict(r) for r in conn.execute("SELECT name, status, confidence FROM features WHERE project_id = ? ORDER BY status, name", (pid,)).fetchall()]
                incomplete = [r for r in rows if r["status"] in ("STUBBED", "PARTIALLY_IMPLEMENTED", "BROKEN")]
                _emit({"features": rows, "incomplete_count": len(incomplete)}, args.json)
                return 0
        finally:
            conn.close()

    if grp == "ui":
        conn = db.connect()
        try:
            if args.action == "audit":
                rows = [dict(r) for r in conn.execute("SELECT control_id, screen_name, component_type, control_label, test_tag, handler_target, status, line_number FROM ui_controls WHERE project_id = ? ORDER BY screen_name, line_number", (pid,)).fetchall()]
                disc = [r for r in rows if r["status"] in ("PARTIALLY_IMPLEMENTED", "STUBBED")]
                _emit({"total_controls": len(rows), "disconnected_count": len(disc), "disconnected": disc}, args.json)
                return 0
            if args.action == "trace":
                if not args.target:
                    raise ValueError("Control name/tag required.")
                rows = [dict(r) for r in conn.execute("SELECT * FROM ui_controls WHERE project_id = ? AND (control_label LIKE ? OR test_tag LIKE ? OR screen_name LIKE ?)", (pid, f"%{args.target}%", f"%{args.target}%", f"%{args.target}%")).fetchall()]
                _emit(rows, args.json)
                return 0 if rows else 2
        finally:
            conn.close()

    if grp == "impact":
        if args.action == "snapshot":
            sid = snapshot_structural_baseline(db, pid)
            _emit({"snapshot_id": sid}, args.json)
            return 0
        res = analyze_impact(db, pid, target_element=args.target, changed_only=args.changed)
        _emit(res, args.json)
        return 1 if res["blocking_count"] > 0 else 0

    if grp == "warnings":
        if args.action == "baseline":
            klog = Path(args.kotlinc_log).read_text(encoding="utf-8") if args.kotlinc_log else ""
            cwarns = parse_kotlin_compiler_log(db.repo_root, klog) if klog else []
            lpath = Path(args.lint_report) if args.lint_report else None
            res = ingest_diagnostics(
                db, pid, compiler_warnings=cwarns, lint_report_path=lpath,
                establish_baseline=True, lifecycle_callback=reconcile_and_ingest,
            )
            _emit(res, args.json)
            return 0
        if args.action == "audit":
            klog = Path(args.kotlinc_log).read_text(encoding="utf-8") if args.kotlinc_log else ""
            cwarns = parse_kotlin_compiler_log(db.repo_root, klog) if klog else []
            lpath = Path(args.lint_report) if args.lint_report else None
            if cwarns or lpath:
                res = ingest_diagnostics(
                    db, pid, compiler_warnings=cwarns, lint_report_path=lpath,
                    establish_baseline=args.establish, lifecycle_callback=reconcile_and_ingest,
                )
            else:
                conn = db.connect()
                try:
                    base_c = conn.execute("SELECT COUNT(*) FROM compiler_warnings WHERE project_id = ? AND is_baseline = 1", (pid,)).fetchone()[0]
                    new_c = conn.execute("SELECT COUNT(*) FROM compiler_warnings WHERE project_id = ? AND is_baseline = 0", (pid,)).fetchone()[0]
                finally:
                    conn.close()
                res = {"baseline_count": base_c, "new_regression_count": new_c}
            _emit(res, args.json)
            return 1 if res.get("new_regression_count", 0) > 0 else 0
        if args.action == "list":
            conn = db.connect()
            try:
                rows = [dict(r) for r in conn.execute("SELECT source_tool, code, file_path, line_number, message, is_baseline FROM compiler_warnings WHERE project_id = ?", (pid,)).fetchall()]
                _emit(rows, args.json)
                return 0
            finally:
                conn.close()

    if grp == "tests":
        if args.action == "snapshot":
            _emit({"snapshot_id": snapshot_test_baseline(db, pid)}, args.json)
            return 0
        if args.action == "audit":
            res = audit_test_integrity(db, pid)
            _emit(res, args.json)
            return 0 if res["passed"] else 1
        if args.action == "verify":
            conn = db.connect()
            try:
                tgt = args.target or ""
                rows = [dict(r) for r in conn.execute("SELECT qualified_name, verification_category, assertion_count, is_skipped, last_result FROM tests WHERE project_id = ? AND (target_feature LIKE ? OR qualified_name LIKE ?)", (pid, f"%{tgt}%", f"%{tgt}%")).fetchall()]
                _emit({"feature": tgt, "matching_tests": rows, "missing_required_test": len(rows) == 0}, args.json)
                return 0 if rows else 3
            finally:
                conn.close()

    if grp == "compliance":
        if args.action == "mode":
            if not args.target:
                raise ValueError("Mode required: AUDIT, MONITOR, or ENFORCEMENT")
            db.set_enforcement_mode(pid, args.target.upper())
            _emit({"enforcement_mode": args.target.upper()}, args.json)
            return 0
        comp = evaluate_compliance(db, pid, strict=args.strict)
        _emit(comp, args.json)
        return comp["exit_code"]

    if grp == "docs":
        if args.action == "generate":
            res = generate_docs(db, pid)
            _emit(res, args.json)
            return 0
        if args.action == "validate":
            res = validate_docs(db)
            _emit(res, args.json)
            return 0 if res["valid"] else 1

    if grp == "audit":
        scan_res = run_scan(db, mode="full", trigger="full_audit", lifecycle_callback=reconcile_and_ingest)
        inv_res = investigate_batch(db, project_id=pid)
        tst_res = audit_test_integrity(db, pid)
        doc_res = generate_docs(db, pid)
        comp = evaluate_compliance(db, pid)
        _emit(
            {
                "scan": {k: v for k, v in scan_res.items() if k != "cycles"},
                "cycles_detected": len(scan_res["cycles"]),
                "investigations_completed": len(inv_res),
                "test_integrity": {k: v for k, v in tst_res.items() if isinstance(v, (int, bool))},
                "docs_generated": doc_res["count"],
                "compliance": comp,
            },
            args.json,
        )
        return comp["exit_code"]

    if grp == "git":
        if args.action == "install-hooks":
            res = install_git_hooks(db.repo_root)
            wf = ensure_ci_workflow(db.repo_root)
            res["ci_workflow"] = str(wf.relative_to(db.repo_root))
            _emit(res, args.json)
            return 0
        if args.action == "write-ci":
            wf = ensure_ci_workflow(db.repo_root)
            _emit({"ci_workflow": str(wf.relative_to(db.repo_root))}, args.json)
            return 0
        if args.action == "pre-commit":
            res = run_pre_commit(db)
            _emit(res, args.json)
            return res["exit_code"]
        if args.action == "pre-push":
            res = run_pre_push(db)
            _emit(res, args.json)
            return res["exit_code"]

    if grp == "exceptions":
        if args.action == "list":
            conn = db.connect()
            try:
                rows = [dict(r) for r in conn.execute("SELECT * FROM exceptions WHERE project_id = ?", (pid,)).fetchall()]
                _emit(rows, args.json)
                return 0
            finally:
                conn.close()
        if args.action == "check":
            exp = check_expired_exceptions(db, pid)
            _emit({"expired": exp}, args.json)
            return 3 if exp else 0
        if args.action == "add":
            eid = add_exception(
                db, pid, args.element, args.type, args.explanation,
                args.evidence, args.approver, args.expires, finding_id=args.finding_id,
            )
            _emit({"exception_id": eid}, args.json)
            return 0

    # ---------------- APIDACSI (`apidacs`) ----------------
    if grp == "findings":
        return _handle_findings(args, db, pid)
    if grp == "investigate":
        return _handle_investigate(args, db, pid)
    if grp == "remediation":
        return _handle_remediation(args, db, pid)
    if grp == "verify":
        return _handle_verify(args, db, pid)

    return 2


def _handle_findings(args: argparse.Namespace, db: RegistryDB, pid: str) -> int:
    act = args.action
    if act == "reconcile":
        res = run_scan(db, mode="changed", trigger="findings_reconcile", lifecycle_callback=reconcile_and_ingest)
        _emit(res, args.json)
        return 0
    if act == "monitor":
        exp = check_expired_exceptions(db, pid)
        res = run_scan(db, mode="changed", trigger="continuous_monitor", lifecycle_callback=reconcile_and_ingest)
        _emit({"expired_exceptions": exp, **res}, args.json)
        return 0
    if act in ("status", "report"):
        conn = db.connect()
        try:
            target_pid = args.project or pid
            conds = ["project_id = ?"]
            params: List[Any] = [target_pid]
            if args.status:
                conds.append("state = ?")
                params.append(args.status)
            if args.classification:
                conds.append("primary_classification = ?")
                params.append(args.classification)
            rows = [dict(r) for r in conn.execute(f"SELECT * FROM findings WHERE {' AND '.join(conds)} ORDER BY state, finding_id", tuple(params)).fetchall()]
            by_state: Dict[str, int] = {}
            by_fresh: Dict[str, int] = {}
            for r in conn.execute("SELECT state, evidence_freshness FROM findings WHERE project_id = ?", (target_pid,)).fetchall():
                by_state[r["state"]] = by_state.get(r["state"], 0) + 1
                by_fresh[r["evidence_freshness"]] = by_fresh.get(r["evidence_freshness"], 0) + 1
            hist = []
            if args.history:
                hist = [dict(h) for h in conn.execute("SELECT * FROM finding_history WHERE project_id = ? ORDER BY created_at DESC LIMIT 50", (target_pid,)).fetchall()]
            _emit(
                {
                    "project_id": target_pid,
                    "total_findings": sum(by_state.values()),
                    "by_state": by_state,
                    "by_freshness": by_fresh,
                    "filtered_count": len(rows),
                    "findings": rows if act == "report" else rows[:25],
                    "history": hist,
                },
                args.json,
            )
            return 0
        finally:
            conn.close()
    if act == "list":
        conn = db.connect()
        try:
            conds = ["project_id = ?"]
            params: List[Any] = [pid]
            if args.status:
                conds.append("state = ?")
                params.append(args.status)
            if args.classification:
                conds.append("primary_classification = ?")
                params.append(args.classification)
            rows = [dict(r) for r in conn.execute(f"SELECT finding_id, state, severity, is_blocking, primary_classification, disposition, file_path, line_number, title FROM findings WHERE {' AND '.join(conds)} ORDER BY is_blocking DESC, finding_id", tuple(params)).fetchall()]
            _emit(rows, args.json)
            return 0
        finally:
            conn.close()
    if not args.target:
        raise ValueError("Finding ID required.")
    fid = args.target
    if act == "inspect":
        conn = db.connect()
        try:
            f = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (fid,)).fetchone()
            if not f:
                raise ValueError(f"Finding not found: {fid}")
            invs = [dict(r) for r in conn.execute("SELECT * FROM investigations WHERE finding_id = ?", (fid,)).fetchall()]
            evs = [dict(r) for r in conn.execute("SELECT * FROM investigation_evidence WHERE finding_id = ?", (fid,)).fetchall()]
            closures = [dict(r) for r in conn.execute("SELECT * FROM closure_records WHERE finding_id = ?", (fid,)).fetchall()]
            _emit({"finding": dict(f), "investigations": invs, "evidence": evs, "closures": closures}, args.json)
            return 0
        finally:
            conn.close()
    if act == "history":
        conn = db.connect()
        try:
            rows = [dict(r) for r in conn.execute("SELECT * FROM finding_history WHERE finding_id = ? ORDER BY created_at ASC", (fid,)).fetchall()]
            _emit(rows, args.json)
            return 0
        finally:
            conn.close()
    if act == "classify":
        with db.transaction() as conn:
            f = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (fid,)).fetchone()
            if not f:
                raise ValueError(f"Finding not found: {fid}")
            cls_val = args.classification or PrimaryClassification.UNRESOLVED.value
            conf_val = args.confidence or EvidenceConfidence.MEDIUM.value
            disp_val = args.disposition or Disposition.FURTHER_INVESTIGATION.value
            conn.execute(
                "UPDATE findings SET primary_classification = ?, confidence = ?, disposition = ? WHERE finding_id = ?",
                (cls_val, conf_val, disp_val, fid),
            )
            _emit({"finding_id": fid, "classification": cls_val, "confidence": conf_val, "disposition": disp_val}, args.json)
            return 0
    if act == "impact":
        conn = db.connect()
        try:
            rows = [dict(r) for r in conn.execute("SELECT * FROM investigation_impact WHERE finding_id = ?", (fid,)).fetchall()]
            _emit(rows, args.json)
            return 0
        finally:
            conn.close()
    if act == "relationships":
        conn = db.connect()
        try:
            rows = [dict(r) for r in conn.execute("SELECT * FROM finding_relationships WHERE finding_id = ? OR related_finding_id = ?", (fid, fid)).fetchall()]
            _emit(rows, args.json)
            return 0
        finally:
            conn.close()
    if act == "validate-resolution":
        res = validate_resolution(db, fid, original_condition_absent=args.condition_absent, actor=args.actor)
        _emit(res, args.json)
        return 0 if res["eligible_for_closure"] else 1
    if act == "close":
        cid = close_finding(db, fid, actor=args.actor, resolution_evidence=args.evidence, original_condition_absent=args.condition_absent)
        _emit({"finding_id": fid, "closure_id": cid, "state": "CLOSED"}, args.json)
        return 0
    if act == "reopen":
        reopen_finding(db, fid, reason=args.reason, actor=args.actor)
        _emit({"finding_id": fid, "state": "PENDING", "reopened": True}, args.json)
        return 0
    return 2


def _handle_investigate(args: argparse.Namespace, db: RegistryDB, pid: str) -> int:
    act = args.action
    if act == "status":
        conn = db.connect()
        try:
            rows = conn.execute("SELECT status, COUNT(*) as cnt FROM investigations GROUP BY status").fetchall()
            _emit({r["status"]: r["cnt"] for r in rows}, args.json)
            return 0
        finally:
            conn.close()
    if act == "list":
        conn = db.connect()
        try:
            rows = [dict(r) for r in conn.execute("SELECT investigation_id, finding_id, cycle_number, status, current_step FROM investigations ORDER BY started_at DESC").fetchall()]
            _emit(rows, args.json)
            return 0
        finally:
            conn.close()
    if act == "finding":
        if not args.target:
            raise ValueError("Finding ID required.")
        res = investigate_finding(db, args.target, stop_after_step=args.stop_after_step)
        _emit(res, args.json)
        return 0
    if act in ("all", "batch", "resume", "stale", "unresolved"):
        res = investigate_batch(
            db,
            project_id=pid,
            only_stale=(act == "stale"),
            only_unresolved=(act == "unresolved"),
            resume_only=(act == "resume"),
            limit=args.limit,
        )
        _emit({"investigated_count": len(res), "results": res[:30]}, args.json)
        return 0
    if not args.target:
        raise ValueError("Finding ID required.")
    conn = db.connect()
    try:
        if act == "evidence":
            rows = [dict(r) for r in conn.execute("SELECT * FROM investigation_evidence WHERE finding_id = ?", (args.target,)).fetchall()]
            _emit(rows, args.json)
            return 0
        if act == "trace":
            refs = [dict(r) for r in conn.execute("SELECT ir.* FROM investigation_references ir JOIN investigations i ON i.investigation_id = ir.investigation_id WHERE i.finding_id = ?", (args.target,)).fetchall()]
            _emit(refs, args.json)
            return 0
    finally:
        conn.close()
    return 2


def _handle_remediation(args: argparse.Namespace, db: RegistryDB, pid: str) -> int:
    act = args.action
    if act == "plans":
        conn = db.connect()
        try:
            rows = [dict(r) for r in conn.execute("SELECT plan_id, status, finding_ids_json, affected_files_json, created_at FROM remediation_plans WHERE project_id = ?", (pid,)).fetchall()]
            _emit(rows, args.json)
            return 0
        finally:
            conn.close()
    if act == "status":
        conn = db.connect()
        try:
            plans = [dict(r) for r in conn.execute("SELECT status, COUNT(*) as cnt FROM remediation_plans WHERE project_id = ? GROUP BY status", (pid,)).fetchall()]
            runs = [dict(r) for r in conn.execute("SELECT run_id, plan_id, status, actor, started_at FROM remediation_runs WHERE project_id = ?", (pid,)).fetchall()]
            _emit({"plans": plans, "runs": runs}, args.json)
            return 0
        finally:
            conn.close()
    if not args.target:
        raise ValueError("Target finding_id or plan_id required.")
    if act == "plan":
        fids = [s.strip() for s in args.target.split(",") if s.strip()]
        files = [s.strip() for s in args.files.split(",") if s.strip()] if args.files else None
        plan_id = create_remediation_plan(
            db, fids,
            proposed_changes=args.changes or "Repair root cause while preserving contracts.",
            required_behavior=args.required or "Data flow / behavior verified intact.",
            affected_files=files,
        )
        _emit({"plan_id": plan_id}, args.json)
        return 0
    if act == "inspect":
        conn = db.connect()
        try:
            p = conn.execute("SELECT * FROM remediation_plans WHERE plan_id = ?", (args.target,)).fetchone()
            if not p:
                raise ValueError(f"Plan not found: {args.target}")
            aprs = [dict(r) for r in conn.execute("SELECT * FROM remediation_approvals WHERE plan_id = ?", (args.target,)).fetchall()]
            _emit({"plan": dict(p), "approvals": aprs}, args.json)
            return 0
        finally:
            conn.close()
    if act == "approve":
        aid = approve_remediation_plan(db, args.target, approver=args.approver or "human_reviewer")
        _emit({"approval_id": aid, "plan_id": args.target}, args.json)
        return 0
    if act == "execute":
        files = [s.strip() for s in args.files.split(",") if s.strip()]
        syms = [s.strip() for s in args.symbols.split(",") if s.strip()]
        rid = execute_remediation_plan(db, args.target, actor=args.actor, modified_files=files, modified_symbols=syms, deviations=args.deviations)
        _emit({"run_id": rid, "plan_id": args.target}, args.json)
        return 0
    if act == "history":
        conn = db.connect()
        try:
            runs = [dict(r) for r in conn.execute("SELECT rr.* FROM remediation_runs rr JOIN remediation_plans rp ON rp.plan_id = rr.plan_id WHERE rp.finding_ids_json LIKE ?", (f"%{args.target}%",)).fetchall()]
            _emit(runs, args.json)
            return 0
        finally:
            conn.close()
    return 2


def _handle_verify(args: argparse.Namespace, db: RegistryDB, pid: str) -> int:
    act = args.action
    if act == "regressions":
        res = run_regression_comparison(db, pid, plan_id=args.target)
        _emit(res, args.json)
        return 0 if res["passed"] else 1
    if not args.target:
        raise ValueError("Target finding_id or plan_id required.")
    if act == "finding":
        vid = record_verification(
            db, args.target, category=args.category, status=args.status,
            command=args.command, output_summary=args.summary, uses_mocks=args.mocks,
        )
        _emit({"verification_id": vid, "finding_id": args.target, "status": args.status}, args.json)
        return 0 if args.status == "PASSED" else 1
    if act == "remediation":
        conn = db.connect()
        try:
            p = conn.execute("SELECT finding_ids_json FROM remediation_plans WHERE plan_id = ?", (args.target,)).fetchone()
            if not p:
                raise ValueError(f"Plan not found: {args.target}")
            fids = json.loads(p["finding_ids_json"])
        finally:
            conn.close()
        vids = [
            record_verification(
                db, fid, category=args.category, status=args.status,
                command=args.command, output_summary=args.summary, uses_mocks=args.mocks, plan_id=args.target,
            )
            for fid in fids
        ]
        _emit({"plan_id": args.target, "verifications": vids}, args.json)
        return 0
    return 2
