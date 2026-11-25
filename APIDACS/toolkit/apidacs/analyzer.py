"""AST-backed structural analysis, variable lifecycle, data-flow, dependency graph, and UI/feature mapping."""

from __future__ import annotations

import json
import os
import subprocess
from collections import defaultdict, deque
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from .db import RegistryDB, utc_now
from .models import (
    Confidence,
    FeatureStatus,
    VariableStage,
    stable_id,
)
from .parsers import (
    DiscoveredFile,
    discover_source_files,
    parse_json_file,
    parse_kotlin_batch,
    parse_shell_file,
    parse_toml_file,
    parse_xml_file,
)


ANDROID_LIFECYCLE_ENTRYPOINTS = {
    "main",
    "onCreate",
    "onStart",
    "onResume",
    "onPause",
    "onStop",
    "onDestroy",
    "onAttach",
    "onDetach",
    "onCleared",
    "onReceive",
    "onBind",
    "onStartCommand",
    "setUp",
    "tearDown",
}


def get_git_revision(repo_root: Path) -> str:
    if not (repo_root / ".git").exists():
        return "no-git-workspace"
    proc = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode == 0 and proc.stdout.strip():
        return proc.stdout.strip()
    return "uncommitted-head"


def get_git_changed_paths(repo_root: Path) -> Set[str]:
    if not (repo_root / ".git").exists():
        return set()
    changed: Set[str] = set()
    proc = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode == 0:
        for line in proc.stdout.splitlines():
            if len(line) >= 4:
                path_part = line[3:].strip()
                if " -> " in path_part:
                    path_part = path_part.split(" -> ", 1)[1].strip()
                changed.add(path_part.replace("\\", "/"))
    return changed


def _infer_feature_domain(rel_path: str, package_name: str) -> str:
    p = rel_path.lower()
    if "drum" in p:
        return "Drum Machine & Sequencer"
    if "dsp" in p or "fx" in p or "effect" in p:
        return "DSP & Effects Chain"
    if "stem" in p or "disband" in p or "network" in p or "collab" in p:
        return "Stem Separation & Cloud Sync"
    if "tool" in p or "plugin" in p or "spectrogram" in p:
        return "Audio Tools & Plugin Host"
    if "midi" in p or "vmc" in p or "desktop" in p or "swing" in p:
        return "Desktop Studio & Controller"
    if "audio" in p or "mic" in p or "wav" in p or "sample" in p:
        return "Audio Engine & Capture"
    if "ui" in p or "screen" in p or "viewmodel" in p:
        return "Application UI & State"
    if package_name:
        return f"Module ({package_name})"
    return "Core Build & Configuration"


def run_scan(
    db: RegistryDB,
    mode: str = "full",
    trigger: str = "cli",
    include_tests: bool = True,
    lifecycle_callback: Any = None,
) -> Dict[str, Any]:
    """
    Execute full or incremental repository scan and update the authoritative SQLite registry.
    Never modifies application source code.
    """
    repo_root = db.repo_root
    db.initialize()
    rev = get_git_revision(repo_root)
    project_id = db.ensure_project(source_revision=rev)

    # Snapshot pre-scan variables & baselines before rewriting structural records for regression checks
    conn = db.connect()
    try:
        prev_files = {
            r["rel_path"]: dict(r)
            for r in conn.execute("SELECT * FROM source_files WHERE project_id = ?", (project_id,)).fetchall()
        }
        prev_vars = {
            r["qualified_name"]: dict(r)
            for r in conn.execute("SELECT * FROM variables WHERE project_id = ?", (project_id,)).fetchall()
        }
        prev_edges = conn.execute("SELECT * FROM dependency_edges WHERE project_id = ?", (project_id,)).fetchall()
    finally:
        conn.close()

    discovered = discover_source_files(repo_root)
    discovered_map = {d.rel_path: d for d in discovered}

    changed_paths: Set[str] = set()
    deleted_paths: Set[str] = set(prev_files.keys()) - set(discovered_map.keys())
    if mode == "changed" and prev_files:
        git_dirty = get_git_changed_paths(repo_root)
        for rel, df in discovered_map.items():
            old = prev_files.get(rel)
            if old is None or old["sha256"] != df.sha256 or rel in git_dirty:
                changed_paths.add(rel)

        # Expand 1-hop dependency neighborhood so callers/consumers of changed files are re-analyzed!
        reverse_adj: Dict[str, Set[str]] = defaultdict(set)
        file_id_to_rel = {f["file_id"]: r for r, f in prev_files.items()}
        # Use imports / edge references to compute neighborhood
        for e in prev_edges:
            if e["file_id"] in file_id_to_rel:
                src_rel = file_id_to_rel[e["file_id"]]
                for rel in prev_files:
                    if e["target_label"] and e["target_label"].split(".")[0] in rel:
                        reverse_adj[rel].add(src_rel)
        neighborhood = set(changed_paths)
        for cp in list(changed_paths) + list(deleted_paths):
            neighborhood.update(reverse_adj.get(cp, set()))
        files_to_parse = [discovered_map[r] for r in sorted(neighborhood) if r in discovered_map]
    else:
        changed_paths = set(discovered_map.keys())
        files_to_parse = discovered

    # Parse ASTs passively (uses SHA-256 disk cache so unchanged files are instant!)
    kotlin_results = parse_kotlin_batch(repo_root, discovered if mode == "full" or not prev_files else discovered)
    # Note: parse_kotlin_batch hits `.apidacs/cache/<sha>.psi.json` for unchanged files in microseconds,
    # while always returning complete ASTs for global graph/lifecycle resolution!
    xml_results: Dict[str, Dict[str, Any]] = {}
    toml_results: Dict[str, Dict[str, Any]] = {}
    shell_results: Dict[str, Dict[str, Any]] = {}
    json_results: Dict[str, Dict[str, Any]] = {}

    for df in discovered:
        if df.language == "xml":
            xml_results[df.rel_path] = parse_xml_file(df)
        elif df.language == "toml":
            toml_results[df.rel_path] = parse_toml_file(df)
        elif df.language == "shell":
            shell_results[df.rel_path] = parse_shell_file(df)
        elif df.language == "json":
            json_results[df.rel_path] = parse_json_file(df)

    scan_id = stable_id("scan", project_id, utc_now(), mode, str(os.urandom(4).hex()))
    started_at = utc_now()

    raw_findings: List[Dict[str, Any]] = []
    manifest_components: Set[str] = set()
    for xdata in xml_results.values():
        for comp in xdata.get("components", []):
            manifest_components.add(comp["name"].lstrip("."))

    # Atomic rebuild of structural tables from exact current ASTs + file metadata
    with db.transaction() as conn:
        conn.execute(
            """
            INSERT INTO scans (scan_id, project_id, scan_mode, trigger_type, source_revision, started_at, status)
            VALUES (?, ?, ?, ?, ?, ?, 'RUNNING')
            """,
            (scan_id, project_id, mode, trigger, rev, started_at),
        )
        db.clear_structural_tables(conn, project_id)

        file_id_by_rel: Dict[str, str] = {}
        file_pkg_by_rel: Dict[str, str] = {}
        file_has_reflection: Dict[str, bool] = {}

        for df in discovered:
            file_id = stable_id("file", project_id, df.rel_path)
            file_id_by_rel[df.rel_path] = file_id
            pkg = ""
            parse_status = "OK"
            parse_error = ""
            imports: List[Any] = []
            refl: List[Any] = []

            if df.language in ("kotlin", "kotlin_script"):
                ast = kotlin_results.get(df.rel_path, {})
                pkg = ast.get("package", "")
                parse_status = ast.get("parseStatus", "OK")
                parse_error = ast.get("parseError", "")
                imports = ast.get("imports", [])
                refl = ast.get("reflectionMarkers", [])
            elif df.language == "xml":
                ast = xml_results[df.rel_path]
                parse_status = ast["parseStatus"]
                parse_error = ast["parseError"]
                imports = ast.get("references", [])
            elif df.language == "toml":
                ast = toml_results[df.rel_path]
                parse_status = ast["parseStatus"]
                parse_error = ast["parseError"]
            elif df.language == "shell":
                ast = shell_results[df.rel_path]
                parse_status = ast["parseStatus"]
                parse_error = ast["parseError"]
            elif df.language == "json":
                ast = json_results[df.rel_path]
                parse_status = ast["parseStatus"]
                parse_error = ast["parseError"]

            file_pkg_by_rel[df.rel_path] = pkg
            file_has_reflection[df.rel_path] = len(refl) > 0

            if parse_status == "ERROR":
                raw_findings.append(
                    _make_finding(
                        analyzer_id="kotlin_psi_ast" if "kotlin" in df.language else "structured_config",
                        category="PARSE_ERROR",
                        rule_id="PARSE_FAILURE",
                        title=f"Parse error in {df.rel_path}",
                        description=parse_error,
                        severity="HIGH",
                        is_blocking=True,
                        file_path=df.rel_path,
                        line_number=1,
                        symbol_name=df.rel_path,
                    )
                )

            conn.execute(
                """
                INSERT INTO source_files (
                    file_id, project_id, rel_path, language, module_name, package_name,
                    sha256, size_bytes, line_count, mtime_ns, is_generated, is_third_party,
                    is_test, parse_status, parse_error, imports_json, reflection_markers_json, last_scanned_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    file_id, project_id, df.rel_path, df.language, df.module_name, pkg,
                    df.sha256, df.size_bytes, df.line_count, df.mtime_ns,
                    int(df.is_generated), int(df.is_third_party), int(df.is_test),
                    parse_status, parse_error, json.dumps(imports), json.dumps(refl), utc_now(),
                ),
            )

        # Phase 2 & 3 collection indices across entire project
        type_rows: Dict[str, Dict[str, Any]] = {}
        type_id_by_name: Dict[str, List[str]] = defaultdict(list)
        func_rows: Dict[str, Dict[str, Any]] = {}
        funcs_by_short_name: Dict[str, List[str]] = defaultdict(list)
        funcs_by_owner_type: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        var_rows: List[Dict[str, Any]] = []
        ui_rows: List[Dict[str, Any]] = []
        test_rows: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []
        any_project_reflection = any(file_has_reflection.values())

        # Pass 1: Register Types, Functions, Variables, Tests, Suppressions, UI Controls
        for df in discovered:
            if df.language not in ("kotlin", "kotlin_script"):
                continue
            file_id = file_id_by_rel[df.rel_path]
            ast = kotlin_results.get(df.rel_path, {})

            for sup in ast.get("suppressions", []):
                sid = stable_id("sup", project_id, df.rel_path, str(sup.get("line", 0)), sup.get("annotationText", ""))
                rules_list = sup.get("rules", [])
                conn.execute(
                    """
                    INSERT OR IGNORE INTO suppressions (
                        suppression_id, project_id, file_id, file_path, line_number,
                        symbol_name, annotation_text, suppressed_rules_json, is_authorized
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    (
                        sid, project_id, file_id, df.rel_path, sup.get("line", 0),
                        sup.get("symbol", ""), sup.get("annotationText", ""), json.dumps(rules_list),
                    ),
                )
                raw_findings.append(
                    _make_finding(
                        analyzer_id="kotlin_psi_ast",
                        category="DIAGNOSTIC_SUPPRESSION",
                        rule_id="UNVERIFIED_SUPPRESSION",
                        title=f"Diagnostic suppression {sup.get('annotationText', '')} on {sup.get('symbol', '')}",
                        description="Code uses @Suppress/@SuppressLint annotation; verify justification or approved exception.",
                        severity="LOW",
                        is_blocking=False,
                        file_path=df.rel_path,
                        line_number=sup.get("line", 0),
                        symbol_name=sup.get("symbol", ""),
                    )
                )

            for t in ast.get("types", []):
                tid = stable_id("typ", project_id, df.rel_path, t["qualifiedName"], str(t["lineStart"]))
                t_rec = {
                    "type_id": tid,
                    "file_id": file_id,
                    "rel_path": df.rel_path,
                    "is_test": df.is_test,
                    **t,
                }
                type_rows[t["qualifiedName"]] = t_rec
                type_id_by_name[t["name"]].append(t["qualifiedName"])
                conn.execute(
                    """
                    INSERT OR IGNORE INTO types (
                        type_id, project_id, file_id, name, qualified_name, kind, visibility,
                        supertypes_json, annotations_json, line_start, line_end,
                        is_data_class, is_sealed, serialization_contract
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        tid, project_id, file_id, t["name"], t["qualifiedName"], t["kind"],
                        t["visibility"], json.dumps(t["supertypes"]), json.dumps(t["annotations"]),
                        t["lineStart"], t["lineEnd"], int(t["isData"]), int(t["isSealed"]),
                        int(t["serializationContract"]),
                    ),
                )

            for f in ast.get("functions", []):
                fid = stable_id("fn", project_id, df.rel_path, f["qualifiedName"], str(f["lineStart"]))
                owner_tid = type_rows[f["ownerType"]]["type_id"] if f["ownerType"] in type_rows else None
                anns = set(f.get("annotations", []))
                is_fw = bool(
                    f.get("isOverride")
                    or f.get("isComposable")
                    or f["name"] in ANDROID_LIFECYCLE_ENTRYPOINTS
                    or f["ownerType"].split(".")[-1] in manifest_components
                    or bool(anns & {"Composable", "Test", "Before", "After", "BeforeClass", "AfterClass", "Preview", "JvmStatic"})
                )
                is_entry = f["name"] == "main" or f["ownerType"].split(".")[-1] in manifest_components
                f_rec = {
                    "function_id": fid,
                    "file_id": file_id,
                    "rel_path": df.rel_path,
                    "owner_tid": owner_tid,
                    "is_test_file": df.is_test,
                    "is_framework_managed": is_fw,
                    "is_entry_point": is_entry,
                    "callers": [],
                    **f,
                }
                func_rows[fid] = f_rec
                funcs_by_short_name[f["name"]].append(fid)
                if f["ownerType"]:
                    funcs_by_owner_type[f["ownerType"]].append(f_rec)

                # Test extraction (@Test functions only; exclude helper builders like testTone())
                if f.get("isTest"):
                    ver_cat = "Unit testing"
                    if "androidTest" in df.rel_path:
                        ver_cat = "UI testing"
                    elif "Robolectric" in df.rel_path or any("Robolectric" in imp.get("fqName", "") for imp in ast.get("imports", [])):
                        ver_cat = "Integration testing"
                    fw = "Robolectric/JUnit4" if ver_cat == "Integration testing" else "JUnit4"
                    test_rows.append(
                        {
                            "test_id": stable_id("tst", project_id, df.rel_path, f["qualifiedName"]),
                            "file_id": file_id,
                            "rel_path": df.rel_path,
                            "name": f["name"],
                            "qualified_name": f["qualifiedName"],
                            "framework": fw,
                            "verification_category": ver_cat,
                            "target_feature": _infer_feature_domain(df.rel_path, ast.get("package", "")),
                            "target_elements": [c["callee"] for c in f.get("calls", [])[:15]],
                            "assertion_count": f.get("assertionCount", 0),
                            "is_skipped": bool(f.get("skipReason")),
                            "skip_reason": f.get("skipReason", ""),
                            "is_empty": bool(f.get("isEmpty")),
                            "uses_mocks": bool(f.get("usesMocks")),
                            "uses_real_hardware": False,
                            "execution_command": (
                                f"gradle :{df.module_name}:test"
                                if df.module_name == "desktop"
                                else f"gradle :{df.module_name}:testDebugUnitTest"
                            ),
                            "line_number": f["lineStart"],
                        }
                    )

            for v in ast.get("variables", []):
                vid = stable_id("var", project_id, df.rel_path, v["qualifiedName"], str(v["line"]))
                owner_tid = type_rows[v["ownerType"]]["type_id"] if v["ownerType"] in type_rows else None
                var_rows.append(
                    {
                        "variable_id": vid,
                        "file_id": file_id,
                        "rel_path": df.rel_path,
                        "package_name": ast.get("package", ""),
                        "owner_tid": owner_tid,
                        "is_test_file": df.is_test,
                        **v,
                    }
                )

            for ui in ast.get("uiControls", []):
                uid = stable_id("ui", project_id, df.rel_path, ui["screenName"], str(ui["line"]), ui["label"])
                ui_rows.append(
                    {
                        "control_id": uid,
                        "file_id": file_id,
                        "rel_path": df.rel_path,
                        "package_name": ast.get("package", ""),
                        **ui,
                    }
                )

        # Pass 2: Build File Imports & Type Inheritance/Contract Edges
        pkg_to_files: Dict[str, List[str]] = defaultdict(list)
        for rel, pkg in file_pkg_by_rel.items():
            if pkg:
                pkg_to_files[pkg].append(rel)

        for df in discovered:
            if df.language not in ("kotlin", "kotlin_script"):
                continue
            src_fid = file_id_by_rel[df.rel_path]
            ast = kotlin_results.get(df.rel_path, {})
            for imp in ast.get("imports", []):
                fq = imp.get("fqName", "")
                if not fq:
                    continue
                parts = fq.split(".")
                target_type_q = fq
                target_id = stable_id("ext", fq)
                target_kind = "external_symbol"
                target_label = fq
                conf = Confidence.CONFIRMED.value
                parent_pkg = ".".join(parts[:-1]) if len(parts) > 1 else ""
                if target_type_q in type_rows:
                    target_id = type_rows[target_type_q]["file_id"]
                    target_kind = "source_file"
                    target_label = type_rows[target_type_q]["rel_path"]
                elif fq in pkg_to_files:
                    target_rel = pkg_to_files[fq][0]
                    target_id = file_id_by_rel[target_rel]
                    target_kind = "source_file"
                    target_label = target_rel
                elif parent_pkg in pkg_to_files:
                    target_rel = pkg_to_files[parent_pkg][0]
                    target_id = file_id_by_rel[target_rel]
                    target_kind = "source_file"
                    target_label = target_rel

                edges.append(
                    {
                        "source_kind": "source_file",
                        "source_id": src_fid,
                        "source_label": df.rel_path,
                        "target_kind": target_kind,
                        "target_id": target_id,
                        "target_label": target_label,
                        "edge_type": "IMPORTS",
                        "confidence": conf,
                        "file_id": src_fid,
                        "line_number": imp.get("line", 0),
                        "evidence": f"import {fq}",
                    }
                )

        # Interface & Supertype Contract Verification
        for qname, t_rec in type_rows.items():
            tid = t_rec["type_id"]
            for st_raw in t_rec.get("supertypes", []):
                st_clean = st_raw.split("(")[0].split("<")[0].strip()
                matched_qs = type_id_by_name.get(st_clean, [])
                if matched_qs:
                    tgt_q = matched_qs[0]
                    tgt_type = type_rows[tgt_q]
                    edges.append(
                        {
                            "source_kind": "type",
                            "source_id": tid,
                            "source_label": qname,
                            "target_kind": "type",
                            "target_id": tgt_type["type_id"],
                            "target_label": tgt_q,
                            "edge_type": "IMPLEMENTS" if tgt_type["kind"] == "interface" else "EXTENDS",
                            "confidence": Confidence.CONFIRMED.value if len(matched_qs) == 1 else Confidence.INFERRED.value,
                            "file_id": t_rec["file_id"],
                            "line_number": t_rec["lineStart"],
                            "evidence": f"{t_rec['name']} : {st_raw}",
                        }
                    )
                    # Verify interface contract methods for concrete non-abstract classes
                    if (
                        tgt_type["kind"] == "interface"
                        and "abstract" not in t_rec.get("modifiers", [])
                        and t_rec["kind"] not in ("interface", "enum_entry")
                    ):
                        iface_methods = [
                            m for m in funcs_by_owner_type.get(tgt_q, [])
                            if m.get("lineStart", 0) > 0 and not _method_has_default_body(m, kotlin_results.get(tgt_type["rel_path"], {}))
                        ]
                        impl_names = {m["name"]: m for m in funcs_by_owner_type.get(qname, [])}
                        for im in iface_methods:
                            if im["name"] not in impl_names:
                                raw_findings.append(
                                    _make_finding(
                                        analyzer_id="dependency_graph",
                                        category="CONTRACT_VIOLATION",
                                        rule_id="MISSING_INTERFACE_IMPLEMENTATION",
                                        title=f"{t_rec['name']} misses interface method {im['name']} from {tgt_type['name']}",
                                        description=(
                                            f"Class {qname} implements interface {tgt_q}, but abstract contract method "
                                            f"'{im['signature']}' is not implemented."
                                        ),
                                        severity="HIGH",
                                        is_blocking=True,
                                        file_path=t_rec["rel_path"],
                                        line_number=t_rec["lineStart"],
                                        symbol_name=f"{qname}.{im['name']}",
                                    )
                                )
                            else:
                                cm = impl_names[im["name"]]
                                if len(cm.get("params", [])) != len(im.get("params", [])):
                                    raw_findings.append(
                                        _make_finding(
                                            analyzer_id="dependency_graph",
                                            category="CONTRACT_VIOLATION",
                                            rule_id="INCOMPATIBLE_CONTRACT_SIGNATURE",
                                            title=f"{qname}.{im['name']} signature mismatches {tgt_q}",
                                            description=(
                                                f"Expected {len(im['params'])} parameter(s) matching {im['signature']}, "
                                                f"found {len(cm['params'])} parameter(s)."
                                            ),
                                            severity="HIGH",
                                            is_blocking=True,
                                            file_path=cm["rel_path"],
                                            line_number=cm["lineStart"],
                                            symbol_name=cm["qualifiedName"],
                                        )
                                    )

        # Pass 3: Function Call Graph, Callers/Callees, Ignored Parameters & Indirect Calls
        # Index property/member reads/writes across all functions in the same file/project
        reads_by_var_name: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        writes_by_var_name: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        all_call_args_by_ident: Dict[str, List[Dict[str, Any]]] = defaultdict(list)

        for fid, f_rec in func_rows.items():
            for rd in f_rec.get("reads", []):
                reads_by_var_name[rd["name"]].append(
                    {
                        "function_id": fid,
                        "function": f_rec["qualifiedName"],
                        "file": f_rec["rel_path"],
                        "context": rd["context"],
                        "qualifier": rd.get("qualifier", ""),
                        "line": rd["line"],
                    }
                )
            for asg in f_rec.get("assignments", []):
                lhs_base = asg["lhs"].split(".")[-1].strip()
                writes_by_var_name[lhs_base].append(
                    {
                        "function_id": fid,
                        "function": f_rec["qualifiedName"],
                        "file": f_rec["rel_path"],
                        "rhs": asg["rhs"],
                        "is_hardcoded": asg.get("isHardcoded", False),
                        "rhs_identifiers": asg.get("rhsIdentifiers", []),
                        "line": asg["line"],
                    }
                )

            for call in f_rec.get("calls", []):
                callee_name = call["callee"]
                for arg in call.get("args", []):
                    for ident in arg.get("identifiers", []):
                        all_call_args_by_ident[ident].append(
                            {
                                "caller": f_rec["qualifiedName"],
                                "callee": callee_name,
                                "receiver": call.get("receiver", ""),
                                "is_logging": call.get("isLogging", False),
                                "is_hardcoded": arg.get("isHardcoded", False),
                                "arg_text": arg.get("text", ""),
                                "line": call["line"],
                                "file": f_rec["rel_path"],
                            }
                        )

                callee_receiver = call.get("receiver", "")
                candidates = funcs_by_short_name.get(callee_name, [])
                if candidates:
                    best = None
                    conf = Confidence.INFERRED.value
                    same_owner = [
                        cid for cid in candidates
                        if f_rec["ownerType"] and func_rows[cid]["ownerType"] == f_rec["ownerType"]
                    ]
                    if not callee_receiver or callee_receiver == "this":
                        # Unqualified call or `this.foo()`: prefer same owner, then same file, then unique top-level
                        if len(same_owner) == 1:
                            best = same_owner[0]
                            conf = Confidence.CONFIRMED.value
                        else:
                            same_file = [cid for cid in candidates if func_rows[cid]["rel_path"] == f_rec["rel_path"]]
                            if same_file:
                                best = same_file[0]
                                conf = Confidence.STRONG.value
                            elif len(candidates) == 1:
                                best = candidates[0]
                                conf = Confidence.INFERRED.value
                    else:
                        # Qualified receiver call `obj.foo()`: match owner type by receiver name/hint or unique non-same-owner symbol
                        recv_low = callee_receiver.lower()
                        owner_matched = [
                            cid for cid in candidates
                            if func_rows[cid]["ownerType"]
                            and (
                                func_rows[cid]["ownerType"].split(".")[-1].lower() in recv_low
                                or recv_low in func_rows[cid]["ownerType"].split(".")[-1].lower()
                            )
                        ]
                        if len(owner_matched) == 1:
                            best = owner_matched[0]
                            conf = Confidence.STRONG.value
                        elif len(owner_matched) > 1:
                            same_file = [cid for cid in owner_matched if func_rows[cid]["rel_path"] == f_rec["rel_path"]]
                            best = same_file[0] if same_file else owner_matched[0]
                            conf = Confidence.INFERRED.value
                        elif len(candidates) == 1 and not same_owner:
                            # Only match unique external project function if it isn't a common stdlib/Android method name
                            if callee_name not in {"play", "stop", "start", "close", "read", "write", "flush", "reset", "clear", "add", "remove", "get", "set", "update"}:
                                best = candidates[0]
                                conf = Confidence.INFERRED.value

                    if best is not None:
                        tgt_f = func_rows[best]
                        tgt_f["callers"].append(f_rec["qualifiedName"])
                        edges.append(
                            {
                                "source_kind": "function",
                                "source_id": fid,
                                "source_label": f_rec["qualifiedName"],
                                "target_kind": "function",
                                "target_id": best,
                                "target_label": tgt_f["qualifiedName"],
                                "edge_type": "CALLS",
                                "confidence": conf,
                                "file_id": f_rec["file_id"],
                                "line_number": call["line"],
                                "evidence": f"{callee_receiver + '.' if callee_receiver else ''}{callee_name}()",
                            }
                        )

        # Persist Function records & evaluate function findings
        for fid, f_rec in func_rows.items():
            ignored = f_rec.get("ignoredParams", [])
            is_concrete = "abstract" not in f_rec.get("modifiers", []) and bool(
                f_rec.get("hasBody", f_rec["lineEnd"] > f_rec["lineStart"])
            )
            # Check unused function (non-test, private or internal with 0 callers)
            unused_status = "USED"
            if (
                not f_rec["is_test_file"]
                and not f_rec["is_framework_managed"]
                and not f_rec["is_entry_point"]
                and not f_rec["callers"]
            ):
                if file_has_reflection.get(f_rec["rel_path"]) or any_project_reflection:
                    unused_status = "UNCERTAIN_REFLECTION"
                elif f_rec["visibility"] == "private":
                    unused_status = "UNUSED_PRIVATE"
                    raw_findings.append(
                        _make_finding(
                            analyzer_id="kotlin_psi_ast",
                            category="DEAD_OR_UNUSED_CODE",
                            rule_id="UNUSED_FUNCTION",
                            title=f"Private function {f_rec['qualifiedName']} has no direct callers",
                            description="Static AST found no direct callers. Investigate before any removal.",
                            severity="LOW",
                            is_blocking=False,
                            file_path=f_rec["rel_path"],
                            line_number=f_rec["lineStart"],
                            symbol_name=f_rec["qualifiedName"],
                        )
                    )
                else:
                    unused_status = "UNREFERENCED_PUBLIC"

            if (
                ignored
                and not f_rec["is_test_file"]
                and not f_rec["isOverride"]
                and is_concrete
            ):
                # Ignore Compose Modifier convention false alerts only if unused elsewhere? Actually flag real ignored params
                for ip in ignored:
                    if ip == "modifier" and f_rec["isComposable"]:
                        continue
                    raw_findings.append(
                        _make_finding(
                            analyzer_id="dataflow_engine",
                            category="DATA_FLOW",
                            rule_id="IGNORED_PARAMETER",
                            title=f"Parameter '{ip}' ignored inside {f_rec['qualifiedName']}",
                            description=(
                                f"Function {f_rec['signature']} declares parameter '{ip}', "
                                f"but '{ip}' is never referenced in its body."
                            ),
                            severity="MEDIUM",
                            is_blocking=False,
                            file_path=f_rec["rel_path"],
                            line_number=f_rec["lineStart"],
                            symbol_name=f"{f_rec['qualifiedName']}#{ip}",
                        )
                    )

            conn.execute(
                """
                INSERT OR IGNORE INTO functions (
                    function_id, project_id, file_id, type_id, name, qualified_name, signature,
                    visibility, params_json, return_type, annotations_json, modifiers_json,
                    line_start, line_end, is_override, is_composable, is_entry_point,
                    is_framework_managed, has_side_effects, body_calls_json, ignored_params_json,
                    unused_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    fid, project_id, f_rec["file_id"], f_rec["owner_tid"], f_rec["name"],
                    f_rec["qualifiedName"], f_rec["signature"], f_rec["visibility"],
                    json.dumps(f_rec["params"]), f_rec["returnType"],
                    json.dumps(f_rec["annotations"]), json.dumps(f_rec["modifiers"]),
                    f_rec["lineStart"], f_rec["lineEnd"], int(f_rec["isOverride"]),
                    int(f_rec["isComposable"]), int(f_rec["is_entry_point"]),
                    int(f_rec["is_framework_managed"]), int(f_rec["hasSideEffects"]),
                    json.dumps(f_rec["calls"][:40]), json.dumps(ignored), unused_status,
                ),
            )

        # Pass 4: Variable Lifecycle & End-to-End Data Flow Analysis
        for v in var_rows:
            name = v["name"]
            qname = v["qualifiedName"]
            scope = v["scope"]

            # Scope-appropriate matching: locals match ownerFunction; members/top-level match project reads
            if scope in ("local", "parameter"):
                raw_reads = [r for r in reads_by_var_name.get(name, []) if r["function"] == v["ownerFunction"]]
                raw_writes = [w for w in writes_by_var_name.get(name, []) if w["function"] == v["ownerFunction"]]
                raw_calls = [c for c in all_call_args_by_ident.get(name, []) if c["caller"] == v["ownerFunction"]]
            else:
                raw_reads = reads_by_var_name.get(name, [])
                raw_writes = writes_by_var_name.get(name, [])
                raw_calls = all_call_args_by_ident.get(name, [])

            logging_reads = [r for r in raw_reads if r["context"] == "logging"]
            functional_reads = [r for r in raw_reads if r["context"] != "logging"]
            propagations = [c for c in raw_calls if not c["is_logging"]]
            transformations = [r for r in functional_reads if r["context"] in ("computation", "assignment_rhs")]

            # Determine lifecycle stage strictly following APIDACS.md §5.2
            stage = VariableStage.DECLARED.value
            if v["initialValue"]:
                stage = VariableStage.INITIALIZED.value
            if raw_writes:
                stage = VariableStage.ASSIGNED.value

            has_reflection = file_has_reflection.get(v["rel_path"], False)
            conf = Confidence.CONFIRMED.value
            if has_reflection and not functional_reads:
                stage = VariableStage.UNRESOLVED.value
                conf = Confidence.UNKNOWN.value
            elif logging_reads and not functional_reads and not propagations:
                stage = VariableStage.READ.value
            elif propagations:
                # Trace downstream: did target callee actually consume/return/write it or override with hardcoded literal?
                stage = VariableStage.CONSUMED.value if _downstream_consumes(propagations, func_rows) else VariableStage.DISCONNECTED.value
            elif functional_reads:
                if any(r["context"] in ("return", "control_flow", "call_arg", "computation") for r in functional_reads):
                    stage = VariableStage.CONSUMED.value
                elif transformations:
                    stage = VariableStage.TRANSFORMED.value
                else:
                    stage = VariableStage.READ.value
            elif scope == "member" and v["visibility"] in ("public", "internal") and raw_writes:
                stage = VariableStage.DISCONNECTED.value

            # Regression check: was this variable dynamically initialized in previous scan and now replaced by hardcoded literal?
            prev_v = prev_vars.get(qname)
            if prev_v and not prev_v["is_hardcoded_literal"] and v["isHardcodedLiteral"] and prev_v["initial_value"]:
                raw_findings.append(
                    _make_finding(
                        analyzer_id="dataflow_engine",
                        category="DATA_FLOW",
                        rule_id="CONFIG_REPLACED_WITH_HARDCODED_VALUE",
                        title=f"Dynamic source of {qname} replaced with hardcoded literal '{v['initialValue']}'",
                        description=(
                            f"Previous scan recorded dynamic expression '{prev_v['initial_value']}', "
                            f"now replaced with hardcoded '{v['initialValue']}'."
                        ),
                        severity="HIGH",
                        is_blocking=True,
                        file_path=v["rel_path"],
                        line_number=v["line"],
                        symbol_name=qname,
                    )
                )

            # Variable-level lifecycle findings (strictly non-test files, NEVER auto-delete)
            is_config_like = any(
                kw in (v["rel_path"] + qname).lower()
                for kw in ("config", "samplerate", "sample_rate", "gain", "threshold", "format", "fade", "preset", "mode")
            )
            if not v["is_test_file"] and scope != "enum_entry":
                if has_reflection and stage == VariableStage.UNRESOLVED.value:
                    raw_findings.append(
                        _make_finding(
                            analyzer_id="dataflow_engine",
                            category="UNCERTAINTY",
                            rule_id="REFLECTION_UNCERTAINTY",
                            title=f"Reference status of {qname} uncertain due to reflection in {v['rel_path']}",
                            description="Static analysis observed reflection API calls; cannot classify element as unused.",
                            severity="INFO",
                            is_blocking=False,
                            file_path=v["rel_path"],
                            line_number=v["line"],
                            symbol_name=qname,
                        )
                    )
                elif logging_reads and not functional_reads and not propagations:
                    raw_findings.append(
                        _make_finding(
                            analyzer_id="dataflow_engine",
                            category="DATA_FLOW",
                            rule_id="LOGGING_ONLY_VARIABLE",
                            title=f"Variable {qname} is read only for logging",
                            description="Value is passed only to logging/printing APIs and never functionally consumed.",
                            severity="MEDIUM",
                            is_blocking=False,
                            file_path=v["rel_path"],
                            line_number=v["line"],
                            symbol_name=qname,
                        )
                    )
                elif stage == VariableStage.DISCONNECTED.value and scope != "local":
                    raw_findings.append(
                        _make_finding(
                            analyzer_id="dataflow_engine",
                            category="DATA_FLOW",
                            rule_id="DISCONNECTED_DATA_FLOW",
                            title=f"Variable {qname} assigned/propagated but disconnected from active consumer",
                            description="Value is assigned or passed without verified functional consumption.",
                            severity="HIGH" if is_config_like else "MEDIUM",
                            is_blocking=False,
                            file_path=v["rel_path"],
                            line_number=v["line"],
                            symbol_name=qname,
                        )
                    )
                elif stage in (VariableStage.DECLARED.value, VariableStage.INITIALIZED.value) and (
                    scope == "local" or v["visibility"] == "private" or is_config_like
                ):
                    if name != "_" and not v.get("annotations"):
                        raw_findings.append(
                            _make_finding(
                                analyzer_id="dataflow_engine",
                                category="VARIABLE_LIFECYCLE",
                                rule_id="UNUSED_VARIABLE",
                                title=f"Variable {qname} declared/initialized with no functional reads",
                                description=(
                                    "Variable has zero discovered reads. Must be investigated before any modification; "
                                    "APIDACS never deletes declarations automatically."
                                ),
                                severity="MEDIUM" if is_config_like else "LOW",
                                is_blocking=False,
                                file_path=v["rel_path"],
                                line_number=v["line"],
                                symbol_name=qname,
                            )
                        )
                elif stage == VariableStage.ASSIGNED.value and not functional_reads and not propagations:
                    raw_findings.append(
                        _make_finding(
                            analyzer_id="dataflow_engine",
                            category="DATA_FLOW",
                            rule_id="DISCONNECTED_DATA_FLOW",
                            title=f"Variable {qname} assigned without downstream propagation or consumption",
                            description="Static AST found assignments to this variable, but value never reaches a consumer.",
                            severity="MEDIUM",
                            is_blocking=False,
                            file_path=v["rel_path"],
                            line_number=v["line"],
                            symbol_name=qname,
                        )
                    )

            destinations = sorted({c["callee"] for c in propagations} | {r["function"] for r in functional_reads})
            actual_consumer = destinations[0] if destinations else ""
            intended_consumer = "downstream_engine" if is_config_like else ""

            conn.execute(
                """
                INSERT OR IGNORE INTO variables (
                    variable_id, project_id, file_id, type_id, name, qualified_name, scope,
                    var_kind, declared_type, visibility, mutability, initial_value,
                    is_hardcoded_literal, line_number, annotations_json, assignments_json,
                    reads_json, logging_reads_json, call_sites_json, transformations_json,
                    destinations_json, intended_consumer, actual_consumer, lifecycle_stage, confidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    v["variable_id"], project_id, v["file_id"], v["owner_tid"], name, qname, scope,
                    v["kind"], v["declaredType"], v["visibility"], v["mutability"], v["initialValue"],
                    int(v["isHardcodedLiteral"]), v["line"], json.dumps(v.get("annotations", [])),
                    json.dumps(raw_writes[:20]), json.dumps(functional_reads[:20]),
                    json.dumps(logging_reads[:20]), json.dumps(propagations[:20]),
                    json.dumps(transformations[:20]), json.dumps(destinations[:20]),
                    intended_consumer, actual_consumer, stage, conf,
                ),
            )

            # Record explicit Data Flow entries for non-trivial variables & config properties
            if is_config_like or scope in ("member", "top_level") or stage in (VariableStage.DISCONNECTED.value, VariableStage.READ.value):
                steps = [f"DECLARE({qname}:{v['line']})"]
                if v["initialValue"]:
                    steps.append(f"INIT({v['initialValue']})")
                for w in raw_writes[:3]:
                    steps.append(f"ASSIGN({w['function']}:{w['line']} = {w['rhs'][:40]})")
                for c in propagations[:3]:
                    steps.append(f"PROPAGATE({c['caller']} -> {c['callee']}:{c['line']})")
                if actual_consumer and stage in (VariableStage.CONSUMED.value, VariableStage.VERIFIED.value):
                    steps.append(f"CONSUME({actual_consumer})")
                flow_status = (
                    "CONNECTED" if stage in (VariableStage.CONSUMED.value, VariableStage.VERIFIED.value)
                    else ("LOGGING_ONLY" if logging_reads and not functional_reads else "DISCONNECTED")
                )
                flow_id = stable_id("flow", project_id, qname, str(v["line"]))
                conn.execute(
                    """
                    INSERT OR IGNORE INTO data_flows (
                        flow_id, project_id, variable_id, source_symbol, steps_json,
                        destination_symbol, intended_consumer, actual_consumer, flow_status, confidence
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        flow_id, project_id, v["variable_id"], qname, json.dumps(steps),
                        actual_consumer, intended_consumer, actual_consumer, flow_status, conf,
                    ),
                )

                # Detect hardcoded downstream override: config variable exists & consumer writes hardcoded constant for similarly named symbol
                if is_config_like and not v["is_test_file"]:
                    for fn_r in func_rows.values():
                        if fn_r["is_test_file"] or fn_r["rel_path"] == v["rel_path"]:
                            continue
                        for ip in fn_r.get("ignoredParams", []):
                            if ip.lower() == name.lower():
                                # Look for a hardcoded constant assignment or literal inside that function
                                hard = [a for a in fn_r.get("assignments", []) if a.get("isHardcoded")]
                                if hard or any(
                                    any(a.get("isHardcoded") for a in c.get("args", []))
                                    for c in fn_r.get("calls", [])
                                ):
                                    raw_findings.append(
                                        _make_finding(
                                            analyzer_id="dataflow_engine",
                                            category="DATA_FLOW",
                                            rule_id="CONFIG_REPLACED_WITH_HARDCODED_VALUE",
                                            title=f"Config '{ip}' bypassed by hardcoded literal inside {fn_r['qualifiedName']}",
                                            description=(
                                                f"Function {fn_r['qualifiedName']} receives '{ip}' but ignores it "
                                                f"while using a hardcoded literal."
                                            ),
                                            severity="HIGH",
                                            is_blocking=True,
                                            file_path=fn_r["rel_path"],
                                            line_number=fn_r["lineStart"],
                                            symbol_name=f"{fn_r['qualifiedName']}#{ip}",
                                        )
                                    )

        # Pass 5: Persist UI Controls & Detect Disconnected UI Controls
        for ui in ui_rows:
            backend_calls = ui.get("backendCalls", [])
            resolved_targets: List[str] = []
            for bc in backend_calls:
                short = bc.split(".")[-1]
                for cid in funcs_by_short_name.get(short, []):
                    resolved_targets.append(func_rows[cid]["qualifiedName"])
            has_backend = len(backend_calls) > 0
            status = FeatureStatus.IMPLEMENTED_UNVERIFIED.value if has_backend else FeatureStatus.PARTIALLY_IMPLEMENTED.value
            conf = Confidence.STRONG.value if resolved_targets else (Confidence.INFERRED.value if has_backend else Confidence.CONFIRMED.value)
            if not has_backend:
                raw_findings.append(
                    _make_finding(
                        analyzer_id="dependency_graph",
                        category="UI_BACKEND_MAPPING",
                        rule_id="UI_CONTROL_NO_BACKEND",
                        title=f"UI control '{ui['label']}' ({ui['componentType']}) has no backend call",
                        description=(
                            f"Control in {ui['screenName']} at {ui['rel_path']}:{ui['line']} "
                            "renders an interactive element with an empty or unconnected handler."
                        ),
                        severity="HIGH",
                        is_blocking=False,
                        file_path=ui["rel_path"],
                        line_number=ui["line"],
                        symbol_name=f"{ui['screenName']}::{ui['label']}",
                    )
                )
            conn.execute(
                """
                INSERT OR IGNORE INTO ui_controls (
                    control_id, project_id, file_id, screen_name, component_type, control_label,
                    test_tag, handler_name, handler_target, backend_targets_json, line_number, status, confidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    ui["control_id"], project_id, ui["file_id"], ui["screenName"], ui["componentType"],
                    ui["label"], ui["testTag"], ui["handlerName"],
                    backend_calls[0] if backend_calls else "",
                    json.dumps(resolved_targets or backend_calls),
                    ui["line"], status, conf,
                ),
            )
            edges.append(
                {
                    "source_kind": "ui_control",
                    "source_id": ui["control_id"],
                    "source_label": f"{ui['screenName']}::{ui['label']}",
                    "target_kind": "function" if resolved_targets else "unresolved",
                    "target_id": resolved_targets[0] if resolved_targets else "unresolved",
                    "target_label": resolved_targets[0] if resolved_targets else "<no-backend>",
                    "edge_type": "UI_BINDS",
                    "confidence": conf,
                    "file_id": ui["file_id"],
                    "line_number": ui["line"],
                    "evidence": f"{ui['componentType']} -> {backend_calls}",
                }
            )

        # Pass 6: Feature Grouping & Status Classification
        feature_groups: Dict[str, Dict[str, Any]] = defaultdict(
            lambda: {"entries": [], "uis": [], "funcs": [], "tests": []}
        )
        for f_rec in func_rows.values():
            if f_rec["is_test_file"]:
                continue
            domain = _infer_feature_domain(f_rec["rel_path"], f_rec["qualifiedName"].rsplit(".", 1)[0])
            feature_groups[domain]["funcs"].append(f_rec["qualifiedName"])
            if f_rec["is_entry_point"] or f_rec["isComposable"]:
                feature_groups[domain]["entries"].append(f_rec["qualifiedName"])
        for ui in ui_rows:
            domain = _infer_feature_domain(ui["rel_path"], ui["package_name"])
            feature_groups[domain]["uis"].append(f"{ui['screenName']}::{ui['label']}")
        for tr in test_rows:
            feature_groups[tr["target_feature"]]["tests"].append(tr["name"])

        disconnected_ui_domains = {
            _infer_feature_domain(ui["rel_path"], ui["package_name"])
            for ui in ui_rows
            if not ui.get("backendCalls")
        }
        for fname, grp in sorted(feature_groups.items()):
            feat_id = stable_id("feat", project_id, fname)
            if fname in disconnected_ui_domains and not grp["funcs"]:
                f_status = FeatureStatus.STUBBED.value
            elif fname in disconnected_ui_domains:
                f_status = FeatureStatus.PARTIALLY_IMPLEMENTED.value
            elif grp["tests"]:
                f_status = FeatureStatus.IMPLEMENTED_TESTED.value
            elif grp["funcs"]:
                f_status = FeatureStatus.IMPLEMENTED_UNVERIFIED.value
            else:
                f_status = FeatureStatus.PLANNED.value

            conn.execute(
                """
                INSERT OR IGNORE INTO features (
                    feature_id, project_id, name, module_scope, entry_points_json,
                    ui_controls_json, backend_functions_json, test_ids_json, status, confidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    feat_id, project_id, fname, fname,
                    json.dumps(grp["entries"][:30]),
                    json.dumps(grp["uis"][:30]),
                    json.dumps(grp["funcs"][:50]),
                    json.dumps(grp["tests"][:30]),
                    f_status,
                    Confidence.STRONG.value if grp["funcs"] else Confidence.INFERRED.value,
                ),
            )

        # Pass 7: Tests Registry & Integrity Checks
        for tr in test_rows:
            conn.execute(
                """
                INSERT OR IGNORE INTO tests (
                    test_id, project_id, file_id, name, qualified_name, framework,
                    verification_category, target_feature, target_elements_json,
                    assertion_count, is_skipped, skip_reason, is_empty, uses_mocks,
                    uses_real_hardware, execution_command, line_number
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    tr["test_id"], project_id, tr["file_id"], tr["name"], tr["qualified_name"],
                    tr["framework"], tr["verification_category"], tr["target_feature"],
                    json.dumps(tr["target_elements"]), tr["assertion_count"], int(tr["is_skipped"]),
                    tr["skip_reason"], int(tr["is_empty"]), int(tr["uses_mocks"]),
                    int(tr["uses_real_hardware"]), tr["execution_command"], tr["line_number"],
                ),
            )
            if tr["is_skipped"]:
                raw_findings.append(
                    _make_finding(
                        analyzer_id="test_integrity",
                        category="TEST_INTEGRITY",
                        rule_id="TEST_DISABLED_OR_SKIPPED",
                        title=f"Test {tr['qualified_name']} is disabled ({tr['skip_reason']})",
                        description="Tests marked @Ignore/@Disabled reduce verification integrity and may conceal defects.",
                        severity="HIGH",
                        is_blocking=True,
                        file_path=tr["rel_path"],
                        line_number=tr["line_number"],
                        symbol_name=tr["qualified_name"],
                    )
                )
            elif tr["is_empty"] or tr["assertion_count"] == 0:
                raw_findings.append(
                    _make_finding(
                        analyzer_id="test_integrity",
                        category="TEST_INTEGRITY",
                        rule_id="TEST_NO_ASSERTIONS_OR_EMPTY",
                        title=f"Test {tr['qualified_name']} contains empty body or zero assertions",
                        description="Test method performs no detectable assertions and cannot establish behavior.",
                        severity="HIGH",
                        is_blocking=True,
                        file_path=tr["rel_path"],
                        line_number=tr["line_number"],
                        symbol_name=tr["qualified_name"],
                    )
                )

        # Pass 8: Persist Dependency Edges & Detect Cycles
        for idx, e in enumerate(edges):
            eid = stable_id(
                "edge", project_id, e["source_id"], e["target_id"], e["edge_type"], str(e["line_number"]), str(idx)
            )
            conn.execute(
                """
                INSERT OR IGNORE INTO dependency_edges (
                    edge_id, project_id, source_kind, source_id, source_label,
                    target_kind, target_id, target_label, edge_type, confidence,
                    file_id, line_number, evidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    eid, project_id, e["source_kind"], e["source_id"], e["source_label"],
                    e["target_kind"], e["target_id"], e["target_label"], e["edge_type"],
                    e["confidence"], e["file_id"], e["line_number"], e["evidence"],
                ),
            )

        cycles = detect_cycles(edges)
        for cyc in cycles:
            label = " -> ".join(cyc)
            owners = {node.rsplit(".", 1)[0] for node in cyc[:-1]}
            is_intra_class = len(owners) == 1 and "/" not in cyc[0]
            raw_findings.append(
                _make_finding(
                    analyzer_id="dependency_graph",
                    category="DEPENDENCY_CYCLE",
                    rule_id="RECURSIVE_CALL_CYCLE" if is_intra_class else "CIRCULAR_DEPENDENCY",
                    title=(
                        f"Intra-class mutual recursion: {label}"
                        if is_intra_class
                        else f"Circular dependency detected: {label}"
                    ),
                    description=f"Directed dependency cycle found: {label}",
                    severity="INFO" if is_intra_class else "HIGH",
                    is_blocking=not is_intra_class,
                    file_path=cyc[0] if "/" in cyc[0] else "",
                    line_number=0,
                    symbol_name=label,
                )
            )

        conn.execute(
            """
            UPDATE scans SET completed_at = ?, status = 'COMPLETED', files_scanned = ?, files_skipped = ?
            WHERE scan_id = ?
            """,
            (utc_now(), len(files_to_parse), max(0, len(discovered) - len(files_to_parse)), scan_id),
        )
        db.record_audit(
            conn,
            project_id,
            trigger,
            "RUN_SCAN",
            scan_id,
            {"mode": mode, "files": len(discovered), "edges": len(edges), "cycles": len(cycles)},
            "OK",
        )

    # Trigger Global Finding Lifecycle Reconciliation & Automatic Investigation Queueing
    lifecycle_stats: Dict[str, Any] = {}
    if lifecycle_callback is not None:
        lifecycle_stats = lifecycle_callback(
            db=db,
            project_id=project_id,
            scan_id=scan_id,
            observations=raw_findings,
            changed_paths=changed_paths | deleted_paths,
        )

    return {
        "project_id": project_id,
        "scan_id": scan_id,
        "mode": mode,
        "revision": rev,
        "files_total": len(discovered),
        "files_analyzed": len(files_to_parse),
        "types_count": len(type_rows),
        "functions_count": len(func_rows),
        "variables_count": len(var_rows),
        "ui_controls_count": len(ui_rows),
        "tests_count": len(test_rows),
        "edges_count": len(edges),
        "cycles": cycles,
        "raw_findings_count": len(raw_findings),
        "lifecycle": lifecycle_stats,
    }


def _make_finding(
    analyzer_id: str,
    category: str,
    rule_id: str,
    title: str,
    description: str,
    severity: str,
    is_blocking: bool,
    file_path: str,
    line_number: int,
    symbol_name: str,
) -> Dict[str, Any]:
    return {
        "analyzer_id": analyzer_id,
        "category": category,
        "rule_id": rule_id,
        "title": title,
        "description": description,
        "severity": severity,
        "is_blocking": is_blocking,
        "file_path": file_path,
        "line_number": line_number,
        "symbol_name": symbol_name,
    }


def _method_has_default_body(method: Dict[str, Any], ast: Dict[str, Any]) -> bool:
    # Interface methods in PSI have lineStart == lineEnd if abstract expressionless signature
    # or multi-line body if default method is provided inside interface
    return len(method.get("calls", [])) > 0 or len(method.get("assignments", [])) > 0


def _downstream_consumes(propagations: List[Dict[str, Any]], func_rows: Dict[str, Dict[str, Any]]) -> bool:
    """Verify whether any propagated callee functionally consumes the passed parameter."""
    by_name: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for fn in func_rows.values():
        by_name[fn["name"]].append(fn)

    for prop in propagations:
        callee = prop["callee"]
        # External SDK / Compose / Stdlib calls without internal definitions are considered functional calls
        # unless proven ignored in a matching internal function definition
        matches = by_name.get(callee, [])
        if not matches:
            return True
        for m in matches:
            ignored = set(m.get("ignoredParams", []))
            param_names = [p["name"] for p in m.get("params", [])]
            # If the function actually uses its parameters, it consumes it
            if param_names and len(ignored) < len(param_names):
                return True
    return False


def detect_cycles(edges: List[Dict[str, Any]]) -> List[List[str]]:
    """Detect directed cycles at source_file (IMPORTS) and function (CALLS) granularity."""
    cycles: List[List[str]] = []
    seen_sigs: Set[Tuple[str, ...]] = set()

    for edge_type in ("IMPORTS", "CALLS"):
        adj: Dict[str, Set[str]] = defaultdict(set)
        for e in edges:
            if e["edge_type"] != edge_type:
                continue
            src = e["source_label"]
            tgt = e["target_label"]
            if src != tgt and e["target_kind"] in ("source_file", "function", "type"):
                adj[src].add(tgt)

        visited: Set[str] = set()
        stack: List[str] = []
        on_stack: Set[str] = set()

        def dfs(node: str) -> None:
            visited.add(node)
            stack.append(node)
            on_stack.add(node)
            for nxt in sorted(adj.get(node, ())):
                if nxt not in visited:
                    dfs(nxt)
                elif nxt in on_stack:
                    idx = stack.index(nxt)
                    cyc = stack[idx:] + [nxt]
                    sig = tuple(sorted(cyc[:-1]))
                    if sig not in seen_sigs:
                        seen_sigs.add(sig)
                        cycles.append(cyc)
            stack.pop()
            on_stack.remove(node)

        for n in sorted(adj.keys()):
            if n not in visited:
                dfs(n)
    return cycles


def query_graph(db: RegistryDB, element: str, direction: str = "both", max_depth: int = 4) -> Dict[str, Any]:
    """Trace upstream, downstream, or bidirectional dependency graph paths from the SQLite registry."""
    conn = db.connect()
    try:
        rows = [dict(r) for r in conn.execute("SELECT * FROM dependency_edges").fetchall()]
    finally:
        conn.close()

    fwd: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    rev: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    seeds: Set[str] = set()

    el_lower = element.lower()
    for r in rows:
        fwd[r["source_label"]].append(r)
        rev[r["target_label"]].append(r)
        if el_lower in r["source_label"].lower():
            seeds.add(r["source_label"])
        if el_lower in r["target_label"].lower():
            seeds.add(r["target_label"])

    def bfs(adj: Dict[str, List[Dict[str, Any]]], key_field: str) -> List[Dict[str, Any]]:
        out: List[Dict[str, Any]] = []
        q = deque([(s, 0) for s in sorted(seeds)])
        visited = set(seeds)
        while q:
            cur, depth = q.popleft()
            if depth >= max_depth:
                continue
            for edge in adj.get(cur, []):
                out.append({**edge, "depth": depth + 1})
                nxt = edge[key_field]
                if nxt not in visited:
                    visited.add(nxt)
                    q.append((nxt, depth + 1))
        return out

    upstream = bfs(rev, "source_label") if direction in ("upstream", "both") else []
    downstream = bfs(fwd, "target_label") if direction in ("downstream", "both") else []
    return {
        "query": element,
        "matched_seeds": sorted(seeds),
        "upstream": upstream,
        "downstream": downstream,
    }
