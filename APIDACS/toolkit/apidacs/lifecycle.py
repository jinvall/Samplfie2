"""APIDACSI Global Finding Lifecycle Engine: reconciliation, 8-step investigation, planning, approval, verification, closure & monitoring."""

from __future__ import annotations

import json
import os
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from .db import RegistryDB, utc_now
from .models import (
    Disposition,
    EvidenceConfidence,
    EvidenceFreshness,
    FindingState,
    ImpactCategory,
    NO_CHANGE_DISPOSITIONS,
    PrimaryClassification,
    VALID_FINDING_TRANSITIONS,
    sha256_text,
    stable_id,
)


def transition_finding_state(
    conn: Any,
    finding_id: str,
    project_id: str,
    new_state: str,
    actor: str,
    action: str,
    reason: str,
    source_revision: str = "",
    evidence_refs: Optional[List[str]] = None,
    approval_ref: str = "",
    result: str = "OK",
) -> None:
    row = conn.execute("SELECT state FROM findings WHERE finding_id = ?", (finding_id,)).fetchone()
    if not row:
        raise ValueError(f"Unknown finding_id: {finding_id}")
    prev_state = row["state"]
    if prev_state == new_state:
        return
    allowed = VALID_FINDING_TRANSITIONS.get(prev_state, set())
    if new_state not in allowed:
        raise ValueError(
            f"Illegal lifecycle transition for {finding_id}: {prev_state} -> {new_state} (allowed: {sorted(allowed)})"
        )
    conn.execute("UPDATE findings SET state = ? WHERE finding_id = ?", (new_state, finding_id))
    now = utc_now()
    hid = stable_id("hist", finding_id, prev_state, new_state, now, str(os.urandom(4).hex()))
    conn.execute(
        """
        INSERT INTO finding_history (
            history_id, finding_id, project_id, actor, action, previous_state, new_state,
            source_revision, evidence_refs_json, approval_ref, reason, result, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            hid,
            finding_id,
            project_id,
            actor,
            action,
            prev_state,
            new_state,
            source_revision,
            json.dumps(evidence_refs or []),
            approval_ref,
            reason,
            result,
            now,
        ),
    )


def _compute_signals(obs: Dict[str, Any]) -> List[Tuple[str, str, int]]:
    rule_id = obs.get("rule_id", "")
    file_path = obs.get("file_path", "")
    symbol = obs.get("symbol_name", "")
    cat = obs.get("category", "")
    signals: List[Tuple[str, str, int]] = []
    if symbol and rule_id:
        signals.append(("rule_symbol", f"{rule_id}::{symbol}", 10))
    if file_path and symbol and rule_id:
        signals.append(("rule_file_symbol", f"{rule_id}::{file_path}::{symbol}", 15))
    if file_path and rule_id and obs.get("title"):
        # Title minus line numbers for compiler warnings
        clean_title = "".join(c for c in obs["title"] if not c.isdigit())
        signals.append(("rule_file_pattern", f"{cat}::{rule_id}::{file_path}::{clean_title}", 6))
    return signals


def reconcile_and_ingest(
    db: RegistryDB,
    project_id: str,
    scan_id: Optional[str],
    observations: Sequence[Dict[str, Any]],
    changed_paths: Optional[Set[str]] = None,
    actor: str = "apidacs_orchestrator",
) -> Dict[str, Any]:
    """
    Reconcile scan observations against persistent findings without creating line-drift duplicates.
    Automatically queues investigations, marks stale evidence on changed dependencies, and reopens recurrences.
    """
    changed_paths = changed_paths or set()
    now = utc_now()
    new_count = 0
    reconciled_count = 0
    reopened_count = 0
    stale_count = 0
    observed_finding_ids: Set[str] = set()

    with db.transaction() as conn:
        proj_row = conn.execute("SELECT source_revision FROM projects WHERE project_id = ?", (project_id,)).fetchone()
        rev = proj_row["source_revision"] if proj_row else ""

        # Load existing fingerprint signals for reconciliation
        fp_rows = conn.execute(
            """
            SELECT ff.finding_id, ff.signal_type, ff.signal_value, ff.weight
            FROM finding_fingerprints ff
            JOIN findings f ON f.finding_id = ff.finding_id
            WHERE f.project_id = ?
            """,
            (project_id,),
        ).fetchall()
        fp_index: Dict[Tuple[str, str], List[Tuple[str, int]]] = defaultdict(list)
        for r in fp_rows:
            fp_index[(r["signal_type"], r["signal_value"])].append((r["finding_id"], r["weight"]))

        for obs in observations:
            signals = _compute_signals(obs)
            score_by_fid: Dict[str, int] = defaultdict(int)
            for stype, sval, weight in signals:
                for fid, w in fp_index.get((stype, sval), []):
                    score_by_fid[fid] += max(weight, w)

            matched_fid: Optional[str] = None
            if score_by_fid:
                best_fid, best_score = max(score_by_fid.items(), key=lambda x: (x[1], x[0]))
                if best_score >= 10:
                    matched_fid = best_fid

            if matched_fid is None:
                # Create persistent finding ID anchored to canonical identity signals
                anchor = f"{obs.get('analyzer_id')}::{obs.get('rule_id')}::{obs.get('file_path')}::{obs.get('symbol_name')}"
                matched_fid = stable_id("FND", project_id, anchor, length=10).upper()
                conn.execute(
                    """
                    INSERT INTO findings (
                        finding_id, project_id, analyzer_id, category, rule_id, title, description,
                        severity, is_blocking, is_baseline, file_path, line_number, symbol_name,
                        state, evidence_freshness, first_seen_scan_id, last_seen_scan_id,
                        first_seen_at, last_seen_at, occurrence_count, reopen_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', 'UNVERIFIED', ?, ?, ?, ?, 1, 0)
                    """,
                    (
                        matched_fid,
                        project_id,
                        obs.get("analyzer_id", "kotlin_psi_ast"),
                        obs.get("category", "GENERAL"),
                        obs.get("rule_id", "UNKNOWN"),
                        obs.get("title", ""),
                        obs.get("description", ""),
                        obs.get("severity", "MEDIUM"),
                        int(bool(obs.get("is_blocking", False))),
                        int(bool(obs.get("is_baseline", False))),
                        obs.get("file_path", ""),
                        int(obs.get("line_number", 0)),
                        obs.get("symbol_name", ""),
                        scan_id,
                        scan_id,
                        now,
                        now,
                    ),
                )
                for stype, sval, weight in signals:
                    fpid = stable_id("fp", matched_fid, stype, sval)
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO finding_fingerprints (fingerprint_id, finding_id, signal_type, signal_value, weight, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (fpid, matched_fid, stype, sval, weight, now),
                    )
                    fp_index[(stype, sval)].append((matched_fid, weight))

                transition_finding_state(
                    conn, matched_fid, project_id, FindingState.REGISTERED.value,
                    actor, "REGISTER_FINDING", "Registered initial observation", rev,
                )
                transition_finding_state(
                    conn, matched_fid, project_id, FindingState.PENDING.value,
                    actor, "QUEUE_INVESTIGATION", "Automatically queued for investigation", rev,
                )
                inv_id = stable_id("inv", matched_fid, "cycle_1")
                conn.execute(
                    """
                    INSERT OR IGNORE INTO investigations (investigation_id, finding_id, cycle_number, status, current_step, source_revision, started_at)
                    VALUES (?, ?, 1, 'QUEUED', 0, ?, ?)
                    """,
                    (inv_id, matched_fid, rev, now),
                )
                new_count += 1
            else:
                reconciled_count += 1
                f_row = conn.execute("SELECT state, reopen_count FROM findings WHERE finding_id = ?", (matched_fid,)).fetchone()
                cur_state = f_row["state"]
                # Update latest location (e.g. shifted line numbers) without creating a duplicate finding!
                conn.execute(
                    """
                    UPDATE findings SET
                        line_number = ?,
                        file_path = COALESCE(NULLIF(?, ''), file_path),
                        last_seen_scan_id = ?,
                        last_seen_at = ?,
                        occurrence_count = occurrence_count + 1
                    WHERE finding_id = ?
                    """,
                    (int(obs.get("line_number", 0)), obs.get("file_path", ""), scan_id, now, matched_fid),
                )
                if cur_state == FindingState.CLOSED.value:
                    # Automatic reopening on recurrence preserving prior closure history!
                    conn.execute(
                        "UPDATE findings SET reopen_count = reopen_count + 1, evidence_freshness = 'INVALIDATED' WHERE finding_id = ?",
                        (matched_fid,),
                    )
                    transition_finding_state(
                        conn, matched_fid, project_id, FindingState.REOPENED.value,
                        actor, "AUTO_REOPEN", "Original diagnostic recurred on subsequent scan", rev,
                    )
                    transition_finding_state(
                        conn, matched_fid, project_id, FindingState.PENDING.value,
                        actor, "QUEUE_REINVESTIGATION", "Re-queued after recurrence", rev,
                    )
                    next_cycle = (conn.execute(
                        "SELECT COALESCE(MAX(cycle_number), 0) FROM investigations WHERE finding_id = ?", (matched_fid,)
                    ).fetchone()[0] or 0) + 1
                    inv_id = stable_id("inv", matched_fid, f"cycle_{next_cycle}")
                    conn.execute(
                        """
                        INSERT INTO investigations (investigation_id, finding_id, cycle_number, status, current_step, source_revision, started_at)
                        VALUES (?, ?, ?, 'QUEUED', 0, ?, ?)
                        """,
                        (inv_id, matched_fid, next_cycle, rev, now),
                    )
                    reopened_count += 1

            observed_finding_ids.add(matched_fid)

            # Record idempotent scan observation
            if scan_id:
                obs_id = stable_id("obs", matched_fid, scan_id, obs.get("analyzer_id", "unknown"))
                conn.execute(
                    """
                    INSERT OR IGNORE INTO finding_observations (
                        observation_id, finding_id, scan_id, analyzer_id, file_path, line_number,
                        symbol_name, raw_message, raw_payload_json, observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        obs_id,
                        matched_fid,
                        scan_id,
                        obs.get("analyzer_id", "unknown"),
                        obs.get("file_path", ""),
                        int(obs.get("line_number", 0)),
                        obs.get("symbol_name", ""),
                        obs.get("description", ""),
                        json.dumps(obs, sort_keys=True),
                        now,
                    ),
                )

        # Evaluate Evidence Freshness against changed_paths (unrelated findings remain untouched!)
        if changed_paths:
            dep_rows = conn.execute(
                """
                SELECT DISTINCT ed.finding_id, ed.target_key, f.state, f.evidence_freshness
                FROM evidence_dependencies ed
                JOIN findings f ON f.finding_id = ed.finding_id
                WHERE f.project_id = ? AND ed.target_kind = 'source_file'
                """,
                (project_id,),
            ).fetchall()
            for dr in dep_rows:
                if dr["target_key"] in changed_paths and dr["evidence_freshness"] == EvidenceFreshness.CURRENT.value:
                    conn.execute(
                        "UPDATE findings SET evidence_freshness = 'STALE' WHERE finding_id = ?",
                        (dr["finding_id"],),
                    )
                    if dr["state"] in (FindingState.INVESTIGATED.value, FindingState.CLOSED.value):
                        transition_finding_state(
                            conn, dr["finding_id"], project_id, FindingState.STALE.value,
                            actor, "EVIDENCE_STALED",
                            f"Dependent source '{dr['target_key']}' modified; reassessment required.",
                            rev,
                        )
                    stale_count += 1

        # Link root-cause relationships automatically (shared symbol or file+category)
        _auto_link_root_causes(conn, project_id, now)

    return {
        "new_findings": new_count,
        "reconciled_findings": reconciled_count,
        "reopened_findings": reopened_count,
        "staled_findings": stale_count,
        "observed_in_scan": len(observed_finding_ids),
    }


def _auto_link_root_causes(conn: Any, project_id: str, now: str) -> None:
    rows = conn.execute(
        "SELECT finding_id, file_path, symbol_name, category FROM findings WHERE project_id = ? AND file_path != ''",
        (project_id,),
    ).fetchall()
    by_file: Dict[str, List[Any]] = defaultdict(list)
    for r in rows:
        by_file[r["file_path"]].append(r)
    for fp, items in by_file.items():
        if len(items) < 2:
            continue
        items = sorted(items, key=lambda r: r["finding_id"])
        anchor = items[0]["finding_id"]
        for other in items[1:]:
            if other["symbol_name"].split("#")[0] == items[0]["symbol_name"].split("#")[0] or other["category"] == items[0]["category"]:
                rid = stable_id("rel", anchor, other["finding_id"], "SHARED_CONTEXT")
                conn.execute(
                    """
                    INSERT OR IGNORE INTO finding_relationships (rel_id, finding_id, related_finding_id, relationship_type, notes, created_at)
                    VALUES (?, ?, ?, 'SHARED_CONTEXT', ?, ?)
                    """,
                    (rid, anchor, other["finding_id"], f"Co-located context in {fp}", now),
                )


def investigate_finding(
    db: RegistryDB,
    finding_id: str,
    stop_after_step: Optional[int] = None,
    actor: str = "apidacs_investigator",
) -> Dict[str, Any]:
    """
    Execute resumable read-only 8-step investigation (APIDACSI §7).
    Never modifies application source code.
    """
    with db.transaction() as conn:
        f = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (finding_id,)).fetchone()
        if not f:
            raise ValueError(f"Finding not found: {finding_id}")
        project_id = f["project_id"]
        rev = (conn.execute("SELECT source_revision FROM projects WHERE project_id = ?", (project_id,)).fetchone()["source_revision"]) or ""

        inv = conn.execute(
            "SELECT * FROM investigations WHERE finding_id = ? ORDER BY cycle_number DESC LIMIT 1",
            (finding_id,),
        ).fetchone()
        if not inv:
            inv_id = stable_id("inv", finding_id, "cycle_1")
            conn.execute(
                """
                INSERT INTO investigations (investigation_id, finding_id, cycle_number, status, current_step, source_revision, started_at)
                VALUES (?, ?, 1, 'IN_PROGRESS', 0, ?, ?)
                """,
                (inv_id, finding_id, rev, utc_now()),
            )
            inv = conn.execute("SELECT * FROM investigations WHERE investigation_id = ?", (inv_id,)).fetchone()

        inv_id = inv["investigation_id"]
        current_step = inv["current_step"]
        completed_steps: List[int] = json.loads(inv["completed_steps_json"] or "[]")

        if f["state"] in (FindingState.PENDING.value, FindingState.STALE.value, FindingState.REOPENED.value):
            transition_finding_state(
                conn, finding_id, project_id, FindingState.INVESTIGATING.value,
                actor, "START_INVESTIGATION", f"Executing APIDACSI 8-step investigation (cycle {inv['cycle_number']})", rev,
            )
        conn.execute("UPDATE investigations SET status = 'IN_PROGRESS' WHERE investigation_id = ?", (inv_id,))

        evidence_collected: List[str] = []
        target_file = f["file_path"]
        target_sym = f["symbol_name"]
        short_sym = target_sym.split(".")[-1].split("#")[-1].split("::")[-1]

        # Context queries from authoritative registry
        file_row = conn.execute(
            "SELECT * FROM source_files WHERE project_id = ? AND rel_path = ?",
            (project_id, target_file),
        ).fetchone() if target_file else None
        var_rows = conn.execute(
            "SELECT * FROM variables WHERE project_id = ? AND (qualified_name = ? OR name = ?)",
            (project_id, target_sym, short_sym),
        ).fetchall()
        fn_rows = conn.execute(
            "SELECT * FROM functions WHERE project_id = ? AND (qualified_name = ? OR name = ?)",
            (project_id, target_sym, short_sym),
        ).fetchall()
        edge_rows = conn.execute(
            "SELECT * FROM dependency_edges WHERE project_id = ? AND (source_label LIKE ? OR target_label LIKE ?)",
            (project_id, f"%{short_sym}%", f"%{short_sym}%"),
        ).fetchall() if short_sym else []

        def record_ev(etype: str, obs_txt: str, supports: str = "SUPPORTS", conf: str = EvidenceConfidence.HIGH.value) -> None:
            eid = stable_id("ev", inv_id, etype, obs_txt[:64])
            conn.execute(
                """
                INSERT OR IGNORE INTO investigation_evidence (
                    evidence_id, investigation_id, finding_id, evidence_type, artifact_ref,
                    source_revision, symbol_or_location, observation, supports_or_contradicts,
                    collection_method, confidence, collected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'sqlite_psi_query', ?, ?)
                """,
                (eid, inv_id, finding_id, etype, target_file or target_sym, rev, f"{target_file}:{f['line_number']}", obs_txt, supports, conf, utc_now()),
            )
            evidence_collected.append(eid)

        def finish_step(step_num: int) -> None:
            if step_num not in completed_steps:
                completed_steps.append(step_num)
            conn.execute(
                "UPDATE investigations SET current_step = ?, completed_steps_json = ? WHERE investigation_id = ?",
                (step_num, json.dumps(sorted(completed_steps)), inv_id),
            )

        # Step 1 — Inspect finding & provenance
        if current_step < 1:
            record_ev("DIAGNOSTIC_PROVENANCE", f"Rule {f['rule_id']} ({f['category']}): {f['description']}")
            finish_step(1)
            if stop_after_step == 1:
                return _inv_snapshot(finding_id, inv_id, 1, "PAUSED")

        # Step 2 — Locate element & direct references
        if current_step < 2:
            ref_count = len(edge_rows)
            for e in edge_rows[:15]:
                rid = stable_id("ref", inv_id, e["edge_id"])
                conn.execute(
                    """
                    INSERT OR IGNORE INTO investigation_references (
                        reference_id, investigation_id, reference_kind, file_path, line_number, symbol_name, confidence, details
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (rid, inv_id, e["edge_type"], target_file, e["line_number"], e["source_label"], e["confidence"], e["evidence"]),
                )
            record_ev("DIRECT_REFERENCES", f"Located {ref_count} direct graph edges involving '{short_sym}'.")
            finish_step(2)
            if stop_after_step == 2:
                return _inv_snapshot(finding_id, inv_id, 2, "PAUSED")

        # Step 3 — Check indirect/framework/reflection references
        if current_step < 3:
            refl = json.loads(file_row["reflection_markers_json"]) if file_row else []
            fw_flags = [fn["qualified_name"] for fn in fn_rows if fn["is_framework_managed"] or fn["is_entry_point"]]
            if refl:
                record_ev(
                    "REFLECTION_SIGNALS",
                    f"Detected {len(refl)} reflection marker(s) in {target_file}: {refl[0].get('expression', '')}",
                    supports="CONTRADICTS_UNUSED",
                    conf=EvidenceConfidence.LOW.value,
                )
            if fw_flags:
                record_ev("FRAMEWORK_LIFECYCLE", f"Matched framework/lifecycle entry points: {fw_flags}")
            finish_step(3)
            if stop_after_step == 3:
                return _inv_snapshot(finding_id, inv_id, 3, "PAUSED")

        # Step 4 — Trace variable/data lifecycle
        if current_step < 4:
            if var_rows:
                vr = var_rows[0]
                reads = json.loads(vr["reads_json"] or "[]")
                log_reads = json.loads(vr["logging_reads_json"] or "[]")
                calls = json.loads(vr["call_sites_json"] or "[]")
                record_ev(
                    "DATA_LIFECYCLE",
                    f"Stage={vr['lifecycle_stage']}; functional_reads={len(reads)}; logging_reads={len(log_reads)}; call_propagations={len(calls)}; actual_consumer='{vr['actual_consumer']}'",
                )
            else:
                record_ev("DATA_LIFECYCLE", "Element is structural/diagnostic rather than a tracked variable.")
            finish_step(4)
            if stop_after_step == 4:
                return _inv_snapshot(finding_id, inv_id, 4, "PAUSED")

        # Step 5 — Intended purpose & consumer
        if current_step < 5:
            intended = var_rows[0]["intended_consumer"] if var_rows else f["category"]
            record_ev("INTENDED_PURPOSE", f"Rule domain={f['category']}, intended_consumer='{intended}'")
            finish_step(5)
            if stop_after_step == 5:
                return _inv_snapshot(finding_id, inv_id, 5, "PAUSED")

        # Step 6 — Architectural context
        if current_step < 6:
            tests_cnt = conn.execute(
                "SELECT COUNT(*) FROM tests WHERE project_id = ? AND target_elements_json LIKE ?",
                (project_id, f"%{short_sym}%"),
            ).fetchone()[0]
            record_ev("ARCHITECTURAL_CONTEXT", f"Related tests referencing '{short_sym}': {tests_cnt}")
            finish_step(6)
            if stop_after_step == 6:
                return _inv_snapshot(finding_id, inv_id, 6, "PAUSED")

        # Step 7 & Step 8 — Classification, Confidence, Impact, Disposition & Evidence Dependencies
        classification, conf, disposition, impact_cat, rationale = _derive_conclusion(
            conn, f, file_row, var_rows, fn_rows, edge_rows
        )

        # Register evidence freshness dependencies (only relevant files/symbols!)
        if target_file:
            dep_id = stable_id("edep", finding_id, target_file)
            fhash = file_row["sha256"] if file_row else ""
            conn.execute(
                """
                INSERT OR IGNORE INTO evidence_dependencies (dep_id, finding_id, investigation_id, target_kind, target_key, target_hash, created_at)
                VALUES (?, ?, ?, 'source_file', ?, ?, ?)
                """,
                (dep_id, finding_id, inv_id, target_file, fhash, utc_now()),
            )

        cls_id = stable_id("cls", finding_id, inv_id, classification)
        conn.execute(
            """
            INSERT OR IGNORE INTO finding_classifications (
                classification_id, finding_id, investigation_id, primary_classification,
                secondary_tags_json, confidence, rationale, classified_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (cls_id, finding_id, inv_id, classification, json.dumps([f["category"], f["rule_id"]]), conf, rationale, utc_now()),
        )

        imp_id = stable_id("imp", finding_id, inv_id)
        affected_els = sorted({e["source_label"] for e in edge_rows[:10]} | {e["target_label"] for e in edge_rows[:10]})
        conn.execute(
            """
            INSERT OR IGNORE INTO investigation_impact (
                impact_id, investigation_id, finding_id, impact_category,
                affected_elements_json, affected_features_json, affected_flows_json, basis, assessed_at
            ) VALUES (?, ?, ?, ?, ?, '[]', '[]', ?, ?)
            """,
            (imp_id, inv_id, finding_id, impact_cat, json.dumps(affected_els), rationale, utc_now()),
        )

        disp_id = stable_id("disp", finding_id, disposition, inv_id)
        conn.execute(
            """
            INSERT OR IGNORE INTO finding_dispositions (disposition_id, finding_id, disposition, rationale, actor, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (disp_id, finding_id, disposition, rationale, actor, utc_now()),
        )

        finish_step(8)
        conn.execute(
            """
            UPDATE investigations SET status = 'COMPLETED', summary = ?, intended_purpose = ?, actual_behavior = ?, completed_at = ?
            WHERE investigation_id = ?
            """,
            (rationale, f["category"], f["description"], utc_now(), inv_id),
        )
        freshness = EvidenceFreshness.CURRENT.value if conf != EvidenceConfidence.INSUFFICIENT.value else EvidenceFreshness.UNVERIFIED.value
        conn.execute(
            """
            UPDATE findings SET
                primary_classification = ?, confidence = ?, impact_category = ?,
                disposition = ?, evidence_freshness = ?
            WHERE finding_id = ?
            """,
            (classification, conf, impact_cat, disposition, freshness, finding_id),
        )

        next_state = (
            FindingState.REVIEW_REQUIRED.value
            if f["is_blocking"] or classification == PrimaryClassification.UNRESOLVED.value
            else FindingState.INVESTIGATED.value
        )
        transition_finding_state(
            conn, finding_id, project_id, next_state,
            actor, "COMPLETE_INVESTIGATION", rationale, rev, evidence_refs=evidence_collected,
        )

        return {
            "finding_id": finding_id,
            "investigation_id": inv_id,
            "status": "COMPLETED",
            "step": 8,
            "classification": classification,
            "confidence": conf,
            "impact_category": impact_cat,
            "disposition": disposition,
            "state": next_state,
        }


def _inv_snapshot(finding_id: str, inv_id: str, step: int, status: str) -> Dict[str, Any]:
    return {"finding_id": finding_id, "investigation_id": inv_id, "step": step, "status": status}


def _derive_conclusion(
    conn: Any,
    finding: Any,
    file_row: Any,
    var_rows: List[Any],
    fn_rows: List[Any],
    edge_rows: List[Any],
) -> Tuple[str, str, str, str, str]:
    rule = finding["rule_id"]
    refl = json.loads(file_row["reflection_markers_json"]) if file_row else []

    if rule == "REFLECTION_UNCERTAINTY" or (refl and rule in ("UNUSED_VARIABLE", "UNUSED_FUNCTION")):
        return (
            PrimaryClassification.UNRESOLVED.value,
            EvidenceConfidence.LOW.value,
            Disposition.FURTHER_INVESTIGATION.value,
            ImpactCategory.UNKNOWN.value,
            "Reflection or dynamic dispatch prevents static confirmation of non-use; preserve element and inspect runtime hooks.",
        )

    if rule in ("LOGGING_ONLY_VARIABLE", "DISCONNECTED_DATA_FLOW", "IGNORED_PARAMETER", "CONFIG_REPLACED_WITH_HARDCODED_VALUE"):
        return (
            PrimaryClassification.DISCONNECTED_DATA_FLOW.value,
            EvidenceConfidence.HIGH.value,
            Disposition.FIX_REQUIRED.value,
            ImpactCategory.FEATURE_LEVEL.value if "CONFIG" in rule else ImpactCategory.LOCALIZED.value,
            f"Data flow check '{rule}' established that value does not produce intended consumer effect.",
        )

    if rule in ("UI_CONTROL_NO_BACKEND", "MISSING_INTERFACE_IMPLEMENTATION", "INCOMPATIBLE_CONTRACT_SIGNATURE", "CIRCULAR_DEPENDENCY", "TEST_DISABLED_OR_SKIPPED", "TEST_NO_ASSERTIONS_OR_EMPTY", "PARSE_FAILURE"):
        return (
            PrimaryClassification.INCOMPLETE_IMPLEMENTATION.value,
            EvidenceConfidence.HIGH.value,
            Disposition.FIX_REQUIRED.value,
            ImpactCategory.CROSS_COMPONENT.value if "CONTRACT" in rule or "CIRCULAR" in rule else ImpactCategory.FEATURE_LEVEL.value,
            f"Structural integrity rule '{rule}' detected broken/incomplete contract or control.",
        )

    if rule == "UNVERIFIED_SUPPRESSION":
        return (
            PrimaryClassification.FRAMEWORK_MANAGED.value if "Android" in finding["title"] else PrimaryClassification.UNRESOLVED.value,
            EvidenceConfidence.MEDIUM.value,
            Disposition.FURTHER_INVESTIGATION.value,
            ImpactCategory.LOCALIZED.value,
            "Diagnostic suppression exists; verify narrow exception justification rather than leaving blanket suppression.",
        )

    if finding["is_baseline"] and finding["category"] == "COMPILER_WARNING":
        return (
            PrimaryClassification.OBSOLETE.value if "redundant" in finding["description"].lower() else PrimaryClassification.INCOMPLETE_IMPLEMENTATION.value,
            EvidenceConfidence.HIGH.value,
            Disposition.FIX_REQUIRED.value if not finding["is_baseline"] else Disposition.DEFERRED.value,
            ImpactCategory.LOCALIZED.value,
            f"Recorded pre-existing compiler warning under initial baseline: {finding['description']}",
        )

    if rule == "UNUSED_VARIABLE":
        return (
            PrimaryClassification.CONFIRMED_UNUSED.value if not refl else PrimaryClassification.UNRESOLVED.value,
            EvidenceConfidence.MEDIUM.value,
            Disposition.FURTHER_INVESTIGATION.value,
            ImpactCategory.LOCALIZED.value,
            "Static AST observed declaration with zero functional reads; requires human/plan authorization before any edit.",
        )

    if fn_rows and (fn_rows[0]["is_framework_managed"] or fn_rows[0]["is_entry_point"]):
        return (
            PrimaryClassification.FRAMEWORK_MANAGED.value,
            EvidenceConfidence.HIGH.value,
            Disposition.RETAIN_FRAMEWORK.value,
            ImpactCategory.NONE_DEMONSTRATED.value,
            "Element is managed by Android/Compose/Test framework lifecycle.",
        )

    return (
        PrimaryClassification.INCOMPLETE_IMPLEMENTATION.value if finding["is_blocking"] else PrimaryClassification.UNRESOLVED.value,
        EvidenceConfidence.MEDIUM.value,
        Disposition.FIX_REQUIRED.value if finding["is_blocking"] else Disposition.FURTHER_INVESTIGATION.value,
        ImpactCategory.LOCALIZED.value,
        f"Investigated finding {finding['finding_id']} under rule {rule}.",
    )


def investigate_batch(
    db: RegistryDB,
    project_id: Optional[str] = None,
    only_stale: bool = False,
    only_unresolved: bool = False,
    resume_only: bool = False,
    limit: Optional[int] = None,
) -> List[Dict[str, Any]]:
    conn = db.connect()
    try:
        conds = ["1=1"]
        params: List[Any] = []
        if project_id:
            conds.append("project_id = ?")
            params.append(project_id)
        if only_stale:
            conds.append("(state = 'STALE' OR evidence_freshness IN ('STALE', 'INVALIDATED'))")
        elif only_unresolved:
            conds.append("state != 'CLOSED'")
        elif resume_only:
            conds.append("state IN ('INVESTIGATING', 'PENDING', 'REOPENED', 'STALE')")
        else:
            conds.append("state IN ('PENDING', 'INVESTIGATING', 'REOPENED', 'STALE')")
        sql = f"SELECT finding_id FROM findings WHERE {' AND '.join(conds)} ORDER BY is_blocking DESC, first_seen_at ASC"
        if limit:
            sql += f" LIMIT {int(limit)}"
        fids = [r["finding_id"] for r in conn.execute(sql, tuple(params)).fetchall()]
    finally:
        conn.close()

    results = []
    for fid in fids:
        results.append(investigate_finding(db, fid))
    return results


def create_remediation_plan(
    db: RegistryDB,
    finding_ids: Sequence[str],
    proposed_changes: str,
    required_behavior: str,
    rollback_strategy: str = "Revert modified lines via git checkout / atomic patch rollback.",
    required_tests: Optional[List[str]] = None,
    affected_files: Optional[List[str]] = None,
) -> str:
    if not finding_ids:
        raise ValueError("At least one finding_id is required to create a remediation plan.")
    with db.transaction() as conn:
        rows = []
        for fid in finding_ids:
            r = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (fid,)).fetchone()
            if not r:
                raise ValueError(f"Unknown finding: {fid}")
            if r["state"] in (FindingState.NEW.value, FindingState.REGISTERED.value, FindingState.PENDING.value):
                raise ValueError(f"Finding {fid} has not been investigated yet (state={r['state']}).")
            rows.append(r)

        project_id = rows[0]["project_id"]
        files = sorted(set(affected_files or [r["file_path"] for r in rows if r["file_path"]]))
        symbols = sorted({r["symbol_name"] for r in rows if r["symbol_name"]})
        inv_ids = []
        for fid in finding_ids:
            ir = conn.execute("SELECT investigation_id FROM investigations WHERE finding_id = ? ORDER BY cycle_number DESC LIMIT 1", (fid,)).fetchone()
            if ir:
                inv_ids.append(ir["investigation_id"])

        plan_id = stable_id("PLN", project_id, ",".join(sorted(finding_ids)), utc_now(), length=10).upper()
        conn.execute(
            """
            INSERT INTO remediation_plans (
                plan_id, project_id, plan_revision, finding_ids_json, investigation_ids_json,
                root_cause, affected_files_json, affected_symbols_json, intended_behavior,
                current_behavior, required_behavior, proposed_changes, dependency_impact,
                required_tests_json, rollback_strategy, approval_scope_json, status, created_at
            ) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?)
            """,
            (
                plan_id,
                project_id,
                json.dumps(list(finding_ids)),
                json.dumps(inv_ids),
                "; ".join(r["description"] for r in rows[:5]),
                json.dumps(files),
                json.dumps(symbols),
                f"Restore intact behavior for {', '.join(symbols) or 'elements'}",
                "; ".join(f"{r['finding_id']}: {r['title']}" for r in rows[:5]),
                required_behavior,
                proposed_changes,
                f"Impacts {len(files)} file(s): {', '.join(files)}",
                json.dumps(required_tests or ["unit_tests", "regression_scan"]),
                rollback_strategy,
                json.dumps({"permitted_files": files, "permitted_symbols": symbols}),
                utc_now(),
            ),
        )
        for r in rows:
            transition_finding_state(
                conn, r["finding_id"], project_id, FindingState.REMEDIATION_PLANNED.value,
                "apidacs_planner", "CREATE_REMEDIATION_PLAN", f"Created remediation plan {plan_id}",
                evidence_refs=[plan_id],
            )
        return plan_id


def approve_remediation_plan(
    db: RegistryDB,
    plan_id: str,
    approver: str,
    restrictions: str = "Modify strictly within approved file scope and preserve external contracts.",
) -> str:
    if not approver or not approver.strip():
        raise ValueError("Explicit approver identity is required.")
    with db.transaction() as conn:
        plan = conn.execute("SELECT * FROM remediation_plans WHERE plan_id = ?", (plan_id,)).fetchone()
        if not plan:
            raise ValueError(f"Plan not found: {plan_id}")
        project_id = plan["project_id"]
        rev = conn.execute("SELECT source_revision FROM projects WHERE project_id = ?", (project_id,)).fetchone()["source_revision"]
        approval_id = stable_id("APR", plan_id, approver, utc_now(), length=10).upper()
        conn.execute(
            """
            INSERT INTO remediation_approvals (
                approval_id, plan_id, project_id, plan_revision, source_revision,
                permitted_files_json, permitted_actions_json, approver, decision, restrictions, approved_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?)
            """,
            (
                approval_id, plan_id, project_id, plan["plan_revision"], rev,
                plan["affected_files_json"], json.dumps(["APPLY_FIX", "UPDATE_TESTS"]),
                approver.strip(), restrictions, utc_now(),
            ),
        )
        conn.execute("UPDATE remediation_plans SET status = 'APPROVED' WHERE plan_id = ?", (plan_id,))
        for fid in json.loads(plan["finding_ids_json"]):
            transition_finding_state(
                conn, fid, project_id, FindingState.APPROVED.value,
                approver.strip(), "APPROVE_REMEDIATION", f"Approved plan {plan_id}",
                source_revision=rev, approval_ref=approval_id,
            )
        return approval_id


def execute_remediation_plan(
    db: RegistryDB,
    plan_id: str,
    actor: str,
    modified_files: Sequence[str],
    modified_symbols: Sequence[str],
    deviations: str = "",
) -> str:
    """
    Enforce explicit approval & scope before recording remediation execution and baseline snapshot.
    Raises PermissionError if unapproved or if modified_files exceed approved scope.
    """
    with db.transaction() as conn:
        plan = conn.execute("SELECT * FROM remediation_plans WHERE plan_id = ?", (plan_id,)).fetchone()
        if not plan:
            raise ValueError(f"Plan not found: {plan_id}")
        project_id = plan["project_id"]
        approval = conn.execute(
            """
            SELECT * FROM remediation_approvals
            WHERE plan_id = ? AND decision = 'APPROVED' AND plan_revision = ?
            ORDER BY approved_at DESC LIMIT 1
            """,
            (plan_id, plan["plan_revision"]),
        ).fetchone()
        if not approval:
            raise PermissionError(
                f"BLOCKED: Remediation plan {plan_id} lacks valid authorization approval. Source modification prohibited."
            )

        permitted_files = set(json.loads(approval["permitted_files_json"] or "[]"))
        out_of_scope = [mf for mf in modified_files if permitted_files and mf not in permitted_files]
        if out_of_scope:
            raise PermissionError(
                f"BLOCKED: Modified file(s) {out_of_scope} exceed approved plan scope {sorted(permitted_files)}. Renew approval required."
            )

        pre_baseline = {
            "source_revision": approval["source_revision"],
            "open_findings": conn.execute(
                "SELECT COUNT(*) FROM findings WHERE project_id = ? AND state != 'CLOSED'", (project_id,)
            ).fetchone()[0],
        }
        run_id = stable_id("RUN", plan_id, utc_now(), length=10).upper()
        report_text = _format_remediation_report(plan, approval, actor, modified_files, modified_symbols, deviations)
        conn.execute(
            """
            INSERT INTO remediation_runs (
                run_id, plan_id, approval_id, project_id, pre_baseline_json,
                status, actor, deviations, report_text, started_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?)
            """,
            (run_id, plan_id, approval["approval_id"], project_id, json.dumps(pre_baseline), actor, deviations, report_text, utc_now(), utc_now()),
        )
        for mf in modified_files:
            cid = stable_id("chg", run_id, mf)
            conn.execute(
                """
                INSERT INTO remediation_changes (change_id, run_id, file_path, symbol_name, change_type, within_approved_scope, recorded_at)
                VALUES (?, ?, ?, ?, 'MODIFIED', 1, ?)
                """,
                (cid, run_id, mf, ",".join(modified_symbols), utc_now()),
            )
        conn.execute("UPDATE remediation_plans SET status = 'EXECUTED' WHERE plan_id = ?", (plan_id,))
        for fid in json.loads(plan["finding_ids_json"]):
            transition_finding_state(
                conn, fid, project_id, FindingState.REMEDIATION_IN_PROGRESS.value,
                actor, "BEGIN_REMEDIATION", f"Executing {run_id}", approval_ref=approval["approval_id"],
            )
            transition_finding_state(
                conn, fid, project_id, FindingState.VERIFICATION_PENDING.value,
                actor, "COMPLETE_REMEDIATION_EDIT", f"Changes recorded in {run_id}; verification required",
                evidence_refs=[run_id],
            )
        return run_id


def _format_remediation_report(
    plan: Any, approval: Any, actor: str, files: Sequence[str], symbols: Sequence[str], deviations: str
) -> str:
    now = utc_now()
    return (
        "REMEDIATION REPORT\n\n"
        f"Project: {plan['project_id']}\n"
        f"Finding ID(s): {plan['finding_ids_json']}\n"
        f"Investigation ID(s): {plan['investigation_ids_json']}\n"
        f"Remediation Plan ID: {plan['plan_id']}\n"
        f"Approval: {approval['approval_id']} ({approval['approver']})\n"
        f"Source revision: {approval['source_revision']}\n\n"
        f"ROOT CAUSE\nEstablished cause: {plan['root_cause']}\n\n"
        f"CHANGES\nModified files: {', '.join(files)}\n"
        f"Modified symbols: {', '.join(symbols)}\n"
        f"Plan deviations: {deviations or 'None'}\n\n"
        f"Actor: {actor}\nTimestamp: {now}\n"
    )


def record_verification(
    db: RegistryDB,
    finding_id: str,
    category: str,
    status: str,
    command: str,
    output_summary: str,
    uses_mocks: bool = False,
    uses_real_hardware: bool = False,
    plan_id: Optional[str] = None,
    actor: str = "apidacs_verifier",
) -> str:
    with db.transaction() as conn:
        f = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (finding_id,)).fetchone()
        if not f:
            raise ValueError(f"Finding not found: {finding_id}")
        project_id = f["project_id"]
        if f["state"] == FindingState.VERIFICATION_PENDING.value:
            transition_finding_state(
                conn, finding_id, project_id, FindingState.VERIFYING.value,
                actor, "START_VERIFICATION", f"Running {category}",
            )
        vid = stable_id("VER", finding_id, category, utc_now(), length=10).upper()
        conn.execute(
            """
            INSERT INTO verification_runs (verification_id, project_id, plan_id, finding_id, overall_status, executed_at, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (vid, project_id, plan_id, finding_id, status, utc_now(), output_summary[:300]),
        )
        rid = stable_id("vr", vid, finding_id)
        conn.execute(
            """
            INSERT INTO verification_results (
                result_id, verification_id, finding_id, category, command, status,
                uses_mocks, uses_real_hardware, output_summary, executed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (rid, vid, finding_id, category, command, status, int(uses_mocks), int(uses_real_hardware), output_summary, utc_now()),
        )
        return vid


def run_regression_comparison(db: RegistryDB, project_id: str, scan_id: Optional[str] = None, plan_id: Optional[str] = None) -> Dict[str, Any]:
    with db.transaction() as conn:
        new_warn = conn.execute(
            "SELECT COUNT(*) FROM compiler_warnings WHERE project_id = ? AND is_baseline = 0", (project_id,)
        ).fetchone()[0]
        failed_tests = conn.execute(
            "SELECT COUNT(*) FROM tests WHERE project_id = ? AND (last_result = 'FAILED' OR is_skipped = 1 OR is_empty = 1 OR assertion_count = 0)",
            (project_id,),
        ).fetchone()[0]
        reopened = conn.execute(
            "SELECT COUNT(*) FROM findings WHERE project_id = ? AND state = 'REOPENED'", (project_id,)
        ).fetchone()[0]
        new_blocking = conn.execute(
            "SELECT COUNT(*) FROM findings WHERE project_id = ? AND is_blocking = 1 AND state = 'PENDING'", (project_id,)
        ).fetchone()[0]

        passed = (new_warn == 0 and failed_tests == 0 and reopened == 0)
        reg_id = stable_id("REG", project_id, utc_now(), length=10).upper()
        conn.execute(
            """
            INSERT INTO regression_reports (
                regression_id, project_id, scan_id, plan_id, new_findings_count,
                reappearing_count, broken_tests_count, warning_delta, passed, details_json, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                reg_id, project_id, scan_id, plan_id, new_blocking, reopened, failed_tests, new_warn, int(passed),
                json.dumps({"new_warnings": new_warn, "failed_or_weak_tests": failed_tests, "reopened": reopened}),
                utc_now(),
            ),
        )
        return {
            "regression_id": reg_id,
            "passed": passed,
            "warning_delta": new_warn,
            "broken_tests_count": failed_tests,
            "reappearing_count": reopened,
        }


def validate_resolution(
    db: RegistryDB,
    finding_id: str,
    original_condition_absent: bool,
    actor: str = "apidacs_validator",
) -> Dict[str, Any]:
    with db.transaction() as conn:
        f = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (finding_id,)).fetchone()
        if not f:
            raise ValueError(f"Finding not found: {finding_id}")
        project_id = f["project_id"]
        disp = f["disposition"]
        is_no_change = disp in NO_CHANGE_DISPOSITIONS

        inv = conn.execute(
            "SELECT * FROM investigations WHERE finding_id = ? AND status = 'COMPLETED' ORDER BY cycle_number DESC LIMIT 1",
            (finding_id,),
        ).fetchone()
        ver_rows = conn.execute(
            "SELECT * FROM verification_results WHERE finding_id = ? ORDER BY executed_at DESC",
            (finding_id,),
        ).fetchall()
        passed_ver = [v for v in ver_rows if v["status"] == "PASSED"]
        failed_ver = [v for v in ver_rows if v["status"] == "FAILED"]

        reasons: List[str] = []
        if not inv:
            reasons.append("Completed investigation missing.")
        if not f["primary_classification"] or not disp:
            reasons.append("Classification or disposition missing.")
        if not is_no_change:
            if not original_condition_absent:
                reasons.append("Original diagnostic condition still present or unverified.")
            if not passed_ver or failed_ver:
                reasons.append("Required verification tests did not pass cleanly.")
            # Prohibit synthetic/mock tests from masquerading as integration proof
            non_mock_pass = [v for v in passed_ver if not v["uses_mocks"]]
            if not non_mock_pass and passed_ver:
                reasons.append("Only mocked verification recorded; real behavior unverified.")

        eligible = len(reasons) == 0
        if eligible and f["state"] in (FindingState.INVESTIGATED.value, FindingState.APPROVED.value, FindingState.VERIFYING.value):
            transition_finding_state(
                conn, finding_id, project_id, FindingState.RESOLUTION_VALIDATION.value,
                actor, "VALIDATE_RESOLUTION", "All resolution checks satisfied.",
            )
        return {"finding_id": finding_id, "eligible_for_closure": eligible, "reasons": reasons}


def close_finding(
    db: RegistryDB,
    finding_id: str,
    actor: str,
    resolution_evidence: str,
    original_condition_absent: bool = True,
) -> str:
    """Enforce closure gate (APIDACSI §21-22) and append immutable closure record."""
    val = validate_resolution(db, finding_id, original_condition_absent=original_condition_absent, actor=actor)
    if not val["eligible_for_closure"]:
        raise PermissionError(f"Closure denied for {finding_id}: {'; '.join(val['reasons'])}")

    with db.transaction() as conn:
        f = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (finding_id,)).fetchone()
        project_id = f["project_id"]
        rev = conn.execute("SELECT source_revision FROM projects WHERE project_id = ?", (project_id,)).fetchone()["source_revision"]
        inv_rows = conn.execute("SELECT investigation_id, cycle_number FROM investigations WHERE finding_id = ?", (finding_id,)).fetchall()
        cycle = max((r["cycle_number"] for r in inv_rows), default=1)
        plan = conn.execute(
            "SELECT plan_id FROM remediation_plans WHERE finding_ids_json LIKE ? ORDER BY created_at DESC LIMIT 1",
            (f"%{finding_id}%",),
        ).fetchone()
        plan_id = plan["plan_id"] if plan else None
        apr_id = None
        mod_files: List[str] = []
        mod_syms: List[str] = []
        if plan_id:
            ar = conn.execute("SELECT approval_id FROM remediation_approvals WHERE plan_id = ? ORDER BY approved_at DESC LIMIT 1", (plan_id,)).fetchone()
            apr_id = ar["approval_id"] if ar else None
            chgs = conn.execute(
                """
                SELECT rc.file_path, rc.symbol_name FROM remediation_changes rc
                JOIN remediation_runs rr ON rr.run_id = rc.run_id WHERE rr.plan_id = ?
                """,
                (plan_id,),
            ).fetchall()
            mod_files = sorted({c["file_path"] for c in chgs})
            mod_syms = sorted({c["symbol_name"] for c in chgs if c["symbol_name"]})

        closure_id = stable_id("CLS", finding_id, str(cycle), utc_now(), length=10).upper()
        conn.execute(
            """
            INSERT INTO closure_records (
                closure_id, finding_id, project_id, cycle_number, final_classification,
                final_disposition, root_cause_or_rationale, investigation_ids_json,
                plan_id, approval_id, modified_files_json, modified_symbols_json,
                source_revision, resolution_evidence, actor, closed_at, final_state, monitoring_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLOSED', 'ACTIVE')
            """,
            (
                closure_id,
                finding_id,
                project_id,
                cycle,
                f["primary_classification"] or PrimaryClassification.UNRESOLVED.value,
                f["disposition"] or Disposition.NO_CHANGE_REQUIRED.value,
                f["description"],
                json.dumps([r["investigation_id"] for r in inv_rows]),
                plan_id,
                apr_id,
                json.dumps(mod_files),
                json.dumps(mod_syms),
                rev,
                resolution_evidence,
                actor,
                utc_now(),
            ),
        )
        transition_finding_state(
            conn, finding_id, project_id, FindingState.CLOSED.value,
            actor, "CLOSE_FINDING", resolution_evidence, source_revision=rev, evidence_refs=[closure_id],
        )
        return closure_id


def reopen_finding(db: RegistryDB, finding_id: str, reason: str, actor: str = "apidacs_monitor") -> None:
    with db.transaction() as conn:
        f = conn.execute("SELECT * FROM findings WHERE finding_id = ?", (finding_id,)).fetchone()
        if not f:
            raise ValueError(f"Finding not found: {finding_id}")
        project_id = f["project_id"]
        conn.execute("UPDATE findings SET reopen_count = reopen_count + 1, evidence_freshness = 'INVALIDATED' WHERE finding_id = ?", (finding_id,))
        transition_finding_state(
            conn, finding_id, project_id, FindingState.REOPENED.value,
            actor, "REOPEN_FINDING", reason,
        )
        transition_finding_state(
            conn, finding_id, project_id, FindingState.PENDING.value,
            actor, "QUEUE_REINVESTIGATION", f"Re-queued: {reason}",
        )
