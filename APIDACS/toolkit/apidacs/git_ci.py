"""Git pre-commit / pre-push hooks, change-history logging, and CI workflow generation."""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path
from typing import Any, Dict

from .analyzer import get_git_changed_paths, get_git_revision, run_scan
from .db import RegistryDB, utc_now
from .diagnostics import audit_test_integrity
from .docs_gen import validate_docs
from .impact import evaluate_compliance
from .lifecycle import reconcile_and_ingest
from .models import stable_id

PRE_COMMIT_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
exec "$ROOT/integrity" git pre-commit
"""

PRE_PUSH_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
exec "$ROOT/integrity" git pre-push
"""

CI_WORKFLOW_YAML = """name: APIDACS Integrity & Compliance Enforcement

on:
  push:
    branches: [ main, master ]
  pull_request:

jobs:
  apidacs-compliance:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Python 3.11
        uses: actions/setup-python@v5
        with:
          python-version: '3.11'

      - name: Run Desktop & Pure JUnit4 Unit Tests
        run: gradle :desktop:test :app:compileDebugKotlin --console=plain

      - name: Run Independent APIDACS Registry Rebuild & Compliance Gate
        run: |
          chmod +x ./integrity ./apidacs
          ./integrity scan
          ./integrity registry validate
          ./integrity dependencies cycles
          ./integrity tests audit
          ./integrity docs generate
          ./integrity docs validate
          ./integrity compliance check --strict
          ./apidacs findings status
"""


def ensure_ci_workflow(repo_root: Path) -> Path:
    wf_dir = Path(repo_root).resolve() / ".github" / "workflows"
    wf_dir.mkdir(parents=True, exist_ok=True)
    wf_path = wf_dir / "apidacs-integrity.yml"
    wf_path.write_text(CI_WORKFLOW_YAML, encoding="utf-8")
    return wf_path


def install_git_hooks(repo_root: Path) -> Dict[str, Any]:
    repo_root = Path(repo_root).resolve()
    git_dir = repo_root / ".git"
    if not git_dir.exists():
        # Store reproducible templates under APIDACS/hooks so non-git workspaces remain compliant
        tmpl_dir = repo_root / "APIDACS" / "hooks"
        tmpl_dir.mkdir(parents=True, exist_ok=True)
        pc = tmpl_dir / "pre-commit"
        pp = tmpl_dir / "pre-push"
        pc.write_text(PRE_COMMIT_SCRIPT, encoding="utf-8")
        pp.write_text(PRE_PUSH_SCRIPT, encoding="utf-8")
        os.chmod(pc, 0o755)
        os.chmod(pp, 0o755)
        return {
            "installed_to_git": False,
            "templates_written": [str(pc.relative_to(repo_root)), str(pp.relative_to(repo_root))],
            "note": ".git repository not initialized yet; hook templates installed to APIDACS/hooks/.",
        }

    hooks_dir = git_dir / "hooks"
    hooks_dir.mkdir(parents=True, exist_ok=True)
    for name, body in (("pre-commit", PRE_COMMIT_SCRIPT), ("pre-push", PRE_PUSH_SCRIPT)):
        hp = hooks_dir / name
        hp.write_text(body, encoding="utf-8")
        os.chmod(hp, 0o755)
    return {"installed_to_git": True, "hooks": ["pre-commit", "pre-push"]}


def run_pre_commit(db: RegistryDB) -> Dict[str, Any]:
    """Execute APIDACS §16.1 pre-commit gate: incremental scan, impact, data-flow, test integrity, doc drift, compliance."""
    changed = get_git_changed_paths(db.repo_root)
    scan_res = run_scan(
        db,
        mode="changed",
        trigger="git_pre_commit",
        lifecycle_callback=reconcile_and_ingest,
    )
    project_id = scan_res["project_id"]
    test_res = audit_test_integrity(db, project_id)
    doc_res = validate_docs(db)
    comp = evaluate_compliance(db, project_id)

    blocked = comp["exit_code"] in (1, 2) or (not test_res["passed"]) or (not doc_res["valid"])
    if not doc_res["valid"]:
        comp["reasons"].append(f"Documentation drift detected ({len(doc_res['drifted'])} file(s)); run './integrity docs generate'.")

    _record_change_history(
        db,
        project_id,
        get_git_revision(db.repo_root),
        sorted(changed),
        comp["verdict"],
        scan_res.get("lifecycle", {}),
    )
    return {
        "gate": "pre-commit",
        "blocked": blocked,
        "exit_code": 1 if blocked else comp["exit_code"],
        "compliance": comp,
        "test_integrity": test_res,
        "docs_validation": doc_res,
    }


def run_pre_push(db: RegistryDB) -> Dict[str, Any]:
    """Execute APIDACS §16.2 pre-push gate: full check, registry validity, tests, doc drift."""
    scan_res = run_scan(
        db,
        mode="full",
        trigger="git_pre_push",
        lifecycle_callback=reconcile_and_ingest,
    )
    project_id = scan_res["project_id"]
    val = db.validate(project_id)
    test_res = audit_test_integrity(db, project_id)
    doc_res = validate_docs(db)
    comp = evaluate_compliance(db, project_id, strict=True)

    blocked = comp["exit_code"] != 0 or not val["valid"] or not doc_res["valid"] or not test_res["passed"]
    return {
        "gate": "pre-push",
        "blocked": blocked,
        "exit_code": 1 if blocked else 0,
        "registry_valid": val["valid"],
        "docs_validation": doc_res,
        "test_integrity": test_res,
        "compliance": comp,
    }


def _record_change_history(
    db: RegistryDB,
    project_id: str,
    commit_id: str,
    modified_elements: Any,
    compliance_result: str,
    lifecycle_stats: Dict[str, Any],
) -> None:
    rec_id = stable_id("chg", project_id, commit_id, utc_now(), str(os.urandom(3).hex()))
    with db.transaction() as conn:
        conn.execute(
            """
            INSERT INTO change_history (
                change_record_id, project_id, commit_id, modified_elements_json,
                impact_summary, findings_introduced_json, findings_resolved_json,
                tests_executed_json, compliance_result, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, '[]', '[]', ?, ?)
            """,
            (
                rec_id,
                project_id,
                commit_id,
                json.dumps(modified_elements[:100]),
                f"new={lifecycle_stats.get('new_findings', 0)}, reopened={lifecycle_stats.get('reopened_findings', 0)}",
                json.dumps([f"new:{lifecycle_stats.get('new_findings', 0)}"]),
                compliance_result,
                utc_now(),
            ),
        )
