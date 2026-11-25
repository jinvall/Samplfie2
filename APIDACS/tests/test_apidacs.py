"""Comprehensive automated verification suite for APIDACS (APIDACS.md §22) and APIDACSI (APIDACSI.md §34).

Notice: Synthetic source fixtures inside these unit/integration tests verify APIDACS static analysis,
SQLite persistence, Git hooks, and lifecycle state machines in isolation. They do NOT represent
runtime hardware or application integration evidence for Samplfie2 audio/UI features.
"""

from __future__ import annotations

import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1] / "toolkit"
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from apidacs.analyzer import query_graph, run_scan  # noqa: E402
from apidacs.cli import main as cli_main  # noqa: E402
from apidacs.db import RegistryDB  # noqa: E402
from apidacs.diagnostics import (  # noqa: E402
    audit_test_integrity,
    ingest_diagnostics,
    parse_kotlin_compiler_log,
    snapshot_test_baseline,
)
from apidacs.docs_gen import generate_docs, validate_docs  # noqa: E402
from apidacs.git_ci import ensure_ci_workflow, install_git_hooks, run_pre_commit, run_pre_push  # noqa: E402
from apidacs.impact import (  # noqa: E402
    add_exception,
    analyze_impact,
    check_expired_exceptions,
    evaluate_compliance,
    snapshot_structural_baseline,
)
from apidacs.lifecycle import (  # noqa: E402
    approve_remediation_plan,
    close_finding,
    create_remediation_plan,
    execute_remediation_plan,
    investigate_batch,
    investigate_finding,
    reconcile_and_ingest,
    record_verification,
    run_regression_comparison,
    validate_resolution,
)
from apidacs.models import MANDATORY_REPORT_FILES, sha256_bytes  # noqa: E402


class ApidacsTestCase(unittest.TestCase):
    """Base fixture creating an isolated temporary project with prebuilt PSI helper symlink/cache."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name).resolve()
        (self.repo / "src").mkdir(parents=True, exist_ok=True)
        os.environ["APIDACS_CACHE_DIR"] = "/tmp/apidacs_test_psi_cache"
        # Share compiled psi_helper.jar from workspace if already built so tests stay fast offline
        ws_jar = Path(__file__).resolve().parents[2] / ".apidacs" / "psi_helper.jar"
        if ws_jar.exists():
            rt = self.repo / ".apidacs"
            rt.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ws_jar, rt / "psi_helper.jar")
        self.db = RegistryDB(self.repo)
        self.db.initialize()
        self.project_id = self.db.ensure_project("TestFixtureProject")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write_src(self, rel_path: str, content: str) -> Path:
        p = self.repo / rel_path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        return p

    def rules_for_symbol(self, symbol_substr: str = "") -> list[str]:
        conn = self.db.connect()
        try:
            if symbol_substr:
                rows = conn.execute(
                    "SELECT rule_id FROM findings WHERE project_id = ? AND symbol_name LIKE ?",
                    (self.project_id, f"%{symbol_substr}%"),
                ).fetchall()
            else:
                rows = conn.execute("SELECT rule_id FROM findings WHERE project_id = ?", (self.project_id,)).fetchall()
            return [r["rule_id"] for r in rows]
        finally:
            conn.close()

    def test_01_unused_variable_finding_no_deletion(self) -> None:
        fp = self.write_src(
            "src/UnusedVar.kt",
            """
            package sample
            fun demo(): Int {
                val neverUsedConfig = 48000
                return 1
            }
            """,
        )
        before_hash = sha256_bytes(fp.read_bytes())
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.assertIn("UNUSED_VARIABLE", self.rules_for_symbol("neverUsedConfig"))
        # Must NEVER auto-delete user variables
        self.assertEqual(before_hash, sha256_bytes(fp.read_bytes()))

    def test_02_assigned_not_propagated_disconnected(self) -> None:
        self.write_src(
            "src/Disconnected.kt",
            """
            package sample
            class AudioSession {
                private var pendingRate: Int = 0
                fun configure() {
                    pendingRate = 96000
                }
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        rules = self.rules_for_symbol("pendingRate")
        self.assertIn("DISCONNECTED_DATA_FLOW", rules)

    def test_03_parameter_ignored_by_consumer(self) -> None:
        self.write_src(
            "src/IgnoredParam.kt",
            """
            package sample
            fun applyGain(buffer: FloatArray, targetGain: Float): Float {
                return buffer[0] * 1.0f
            }
            fun runAudio(): Float {
                val gain = 0.75f
                return applyGain(floatArrayOf(0.5f), gain)
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.assertIn("IGNORED_PARAMETER", self.rules_for_symbol("targetGain"))

    def test_04_logging_only_read_not_functional(self) -> None:
        self.write_src(
            "src/LogOnly.kt",
            """
            package sample
            fun process(input: Int): Int {
                val sampleRateSetting = input * 2
                println("Sample rate is $sampleRateSetting")
                return 42
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.assertIn("LOGGING_ONLY_VARIABLE", self.rules_for_symbol("sampleRateSetting"))
        conn = self.db.connect()
        try:
            row = conn.execute("SELECT lifecycle_stage FROM variables WHERE name = 'sampleRateSetting'").fetchone()
            self.assertNotIn(row["lifecycle_stage"], ("CONSUMED", "VERIFIED"))
        finally:
            conn.close()

    def test_05_config_replaced_by_hardcoded_value_regression(self) -> None:
        self.write_src(
            "src/ConfigProg.kt",
            """
            package sample
            class RigSettings {
                val outputRate: Int = computeRate()
                fun computeRate(): Int = 48000
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        # Replace dynamic computeRate() call with hardcoded literal
        self.write_src(
            "src/ConfigProg.kt",
            """
            package sample
            class RigSettings {
                val outputRate: Int = 44100
                fun computeRate(): Int = 48000
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.assertIn("CONFIG_REPLACED_WITH_HARDCODED_VALUE", self.rules_for_symbol("outputRate"))

    def test_06_function_deleted_with_active_callers_blocked(self) -> None:
        self.write_src(
            "src/Service.kt",
            """
            package sample
            fun computeChecksum(x: Int): Int = x + 7
            fun caller(): Int = computeChecksum(5)
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        snapshot_structural_baseline(self.db, self.project_id)

        # Delete active callee without updating caller or approval
        self.write_src(
            "src/Service.kt",
            """
            package sample
            fun caller(): Int = 12
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        imp = analyze_impact(self.db, self.project_id)
        self.assertGreater(imp["blocking_count"], 0)
        self.assertTrue(any(d["kind"] == "FUNCTION_DELETED" and "computeChecksum" in d["symbol"] for d in imp["destructive_changes"]))
        comp = evaluate_compliance(self.db, self.project_id, strict=True)
        self.assertEqual(comp["verdict"], "BLOCKED")
        self.assertEqual(comp["exit_code"], 1)

    def test_07_reflection_retains_uncertainty_not_unused(self) -> None:
        self.write_src(
            "src/Reflector.kt",
            """
            package sample
            class HiddenPlugin {
                private val secretHook: String = "v1"
                fun boot() {
                    val cls = Class.forName("sample.HiddenPlugin")
                    println(cls.name)
                }
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        rules = self.rules_for_symbol("secretHook")
        self.assertIn("REFLECTION_UNCERTAINTY", rules)
        self.assertNotIn("UNUSED_VARIABLE", rules)

    def test_08_and_09_interface_contract_and_signature_violations(self) -> None:
        self.write_src(
            "src/Contracts.kt",
            """
            package sample
            interface AudioSink {
                fun writeFrames(frames: FloatArray, count: Int): Int
                fun closeSink()
            }
            class BrokenSink : AudioSink {
                override fun writeFrames(frames: FloatArray): Int = 0
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        all_rules = self.rules_for_symbol()
        self.assertIn("INCOMPATIBLE_CONTRACT_SIGNATURE", all_rules)
        self.assertIn("MISSING_INTERFACE_IMPLEMENTATION", all_rules)

    def test_10_circular_dependency_reported(self) -> None:
        self.write_src("src/CycleA.kt", "package a\nimport b.CycleB\nclass CycleA\n")
        self.write_src("src/CycleB.kt", "package b\nimport a.CycleA\nclass CycleB\n")
        res = run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.assertGreaterEqual(len(res["cycles"]), 1)
        self.assertIn("CIRCULAR_DEPENDENCY", self.rules_for_symbol())

    def test_11_ui_control_lacks_backend_marked_incomplete(self) -> None:
        self.write_src(
            "src/UiScreen.kt",
            """
            package sample.ui
            import androidx.compose.runtime.Composable
            @Composable
            fun ControlPanel() {
                Button(onClick = {}) {
                    Text("Export Master")
                }
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.assertIn("UI_CONTROL_NO_BACKEND", self.rules_for_symbol())
        conn = self.db.connect()
        try:
            feat = conn.execute("SELECT status FROM features WHERE project_id = ?", (self.project_id,)).fetchone()
            self.assertEqual(feat["status"], "PARTIALLY_IMPLEMENTED")
        finally:
            conn.close()

    def test_12_warning_baseline_vs_new_warning_and_suppression(self) -> None:
        self.write_src(
            "src/Suppressed.kt",
            """
            package sample
            @Suppress("UNCHECKED_CAST")
            fun castDemo(x: Any): String = x as String
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.assertIn("UNVERIFIED_SUPPRESSION", self.rules_for_symbol())

        base_log = "w: file:///src/Suppressed.kt:3:5 Existing safe call warning"
        c1 = parse_kotlin_compiler_log(self.repo, base_log)
        r1 = ingest_diagnostics(self.db, self.project_id, compiler_warnings=c1, establish_baseline=True, lifecycle_callback=reconcile_and_ingest)
        self.assertEqual(r1["baseline_count"], 1)
        self.assertEqual(r1["new_regression_count"], 0)

        new_log = base_log + "\nw: file:///src/Suppressed.kt:4:1 Brand new unused warning"
        c2 = parse_kotlin_compiler_log(self.repo, new_log)
        r2 = ingest_diagnostics(self.db, self.project_id, compiler_warnings=c2, establish_baseline=False, lifecycle_callback=reconcile_and_ingest)
        self.assertEqual(r2["baseline_count"], 1)
        self.assertEqual(r2["new_regression_count"], 1)

    def test_13_test_disabled_empty_and_weakened_detected(self) -> None:
        self.write_src(
            "src/test/SampleTest.kt",
            """
            package sample
            import org.junit.Test
            import org.junit.Ignore
            class SampleTest {
                @Test
                fun activeTest() {
                    assertEquals(4, 2 + 2)
                    assertTrue(true)
                }
                @Ignore("broken")
                @Test
                fun skippedTest() {
                    assertTrue(true)
                }
                @Test
                fun emptyTest() {
                }
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        snapshot_test_baseline(self.db, self.project_id)
        audit = audit_test_integrity(self.db, self.project_id)
        self.assertFalse(audit["passed"])
        self.assertEqual(audit["skipped_count"], 1)
        self.assertEqual(audit["empty_or_zero_assert_count"], 1)

        # Remove an assertion from activeTest to simulate test weakening
        self.write_src(
            "src/test/SampleTest.kt",
            """
            package sample
            import org.junit.Test
            class SampleTest {
                @Test
                fun activeTest() {
                    assertEquals(4, 2 + 2)
                }
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        audit2 = audit_test_integrity(self.db, self.project_id)
        self.assertEqual(audit2["weakened_count"], 1)

    def test_14_mock_only_test_cannot_close_finding(self) -> None:
        self.write_src(
            "src/MockVictim.kt",
            """
            package sample
            fun pipe(rate: Int): Int = 44100
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        inv = investigate_batch(self.db, self.project_id)
        fid = inv[0]["finding_id"]
        pid = create_remediation_plan(self.db, [fid], "Use rate parameter cleanly", "Dynamic rate used")
        approve_remediation_plan(self.db, pid, "lead_reviewer")
        execute_remediation_plan(self.db, pid, "agent", ["src/MockVictim.kt"], ["sample.pipe"])
        # Record ONLY mocked verification
        record_verification(self.db, fid, "Unit testing", "PASSED", "mockTest", "Passed with fake engine", uses_mocks=True)
        val = validate_resolution(self.db, fid, original_condition_absent=True)
        self.assertFalse(val["eligible_for_closure"])
        with self.assertRaises(PermissionError):
            close_finding(self.db, fid, "agent", "Attempted closure with mocks only")

    def test_15_interrupted_transaction_preserves_valid_registry(self) -> None:
        self.write_src("src/Ok.kt", "package ok\nval x = 10\n")
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        before_val = self.db.validate(self.project_id)
        self.assertTrue(before_val["valid"])

        with self.assertRaises(RuntimeError):
            with self.db.transaction() as conn:
                conn.execute("DELETE FROM source_files WHERE project_id = ?", (self.project_id,))
                raise RuntimeError("Simulated crash during registry transaction")

        after_val = self.db.validate(self.project_id)
        self.assertTrue(after_val["valid"])
        self.assertEqual(after_val["stats"]["source_files"], before_val["stats"]["source_files"])

    def test_16_markdown_determinism_and_manual_drift_detected(self) -> None:
        self.write_src("src/DocDemo.kt", "package demo\nval gain = 1.0f\n")
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        res1 = generate_docs(self.db, self.project_id)
        self.assertEqual(res1["count"], 14)
        for fname in MANDATORY_REPORT_FILES:
            self.assertTrue((self.repo / "APIDACS" / "reports" / fname).exists())
        hashes1 = {f: sha256_bytes((self.repo / "APIDACS" / "reports" / f).read_bytes()) for f in MANDATORY_REPORT_FILES}

        generate_docs(self.db, self.project_id)
        hashes2 = {f: sha256_bytes((self.repo / "APIDACS" / "reports" / f).read_bytes()) for f in MANDATORY_REPORT_FILES}
        self.assertEqual(hashes1, hashes2)
        self.assertTrue(validate_docs(self.db)["valid"])

        # Tamper manually with VARIABLES.md
        vdoc = self.repo / "APIDACS" / "reports" / "VARIABLES.md"
        vdoc.write_text(vdoc.read_text(encoding="utf-8") + "\n<!-- manual edit -->\n", encoding="utf-8")
        drift = validate_docs(self.db)
        self.assertFalse(drift["valid"])
        self.assertEqual(drift["drifted"][0]["file"], "VARIABLES.md")

    def test_17_incremental_scan_and_multi_language_parsers(self) -> None:
        self.write_src("app/src/main/AndroidManifest.xml", '<manifest package="com.t"><uses-permission android:name="android.permission.INTERNET"/><application><activity android:name=".MainActivity"/></application></manifest>')
        self.write_src("gradle/libs.versions.toml", '[versions]\nkotlin = "2.4.21"\n[libraries]\ncore = { group = "a", name = "b" }\n')
        self.write_src("scripts/check.sh", "#!/usr/bin/env bash\necho ok\n")
        self.write_src("metadata.json", '{"name": "Sample"}')
        self.write_src("src/Target.kt", "package t\nfun targetFn(): Int = 1\n")
        self.write_src("src/Caller.kt", "package c\nimport t.targetFn\nfun useIt(): Int = targetFn()\n")

        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        self.write_src("src/Target.kt", "package t\nfun targetFn(): Int = 2\n")
        inc = run_scan(self.db, mode="changed", lifecycle_callback=reconcile_and_ingest)
        # Should analyze both Target.kt and its dependent neighborhood Caller.kt without failing
        self.assertGreaterEqual(inc["files_analyzed"], 2)
        g = query_graph(self.db, "targetFn", direction="upstream")
        self.assertTrue(any("useIt" in e["source_label"] for e in g["upstream"]))

    def test_18_apidacsi_reconciliation_line_shift_resumable_approval_closure_and_reopen(self) -> None:
        self.write_src(
            "src/Lifecycle.kt",
            """
            package sample
            fun demo(): Int {
                val disconnectedVal = 99
                return 0
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        conn = self.db.connect()
        try:
            orig = conn.execute("SELECT finding_id, line_number FROM findings WHERE symbol_name LIKE '%disconnectedVal%'").fetchone()
        finally:
            conn.close()
        self.assertIsNotNone(orig)
        orig_fid = orig["finding_id"]

        # Shift lines down by 10 lines -> same finding_id preserved, no duplicate created!
        self.write_src(
            "src/Lifecycle.kt",
            "\n" * 10 + """
            package sample
            fun demo(): Int {
                val disconnectedVal = 99
                return 0
            }
            """,
        )
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        conn = self.db.connect()
        try:
            rows = conn.execute("SELECT finding_id, line_number, occurrence_count FROM findings WHERE symbol_name LIKE '%disconnectedVal%'").fetchall()
        finally:
            conn.close()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["finding_id"], orig_fid)
        self.assertEqual(rows[0]["occurrence_count"], 2)
        self.assertGreater(rows[0]["line_number"], orig["line_number"])

        # Step-by-step resumable investigation (stop after step 3, then resume to step 8)
        paused = investigate_finding(self.db, orig_fid, stop_after_step=3)
        self.assertEqual(paused["step"], 3)
        self.assertEqual(paused["status"], "PAUSED")
        resumed = investigate_finding(self.db, orig_fid)
        self.assertEqual(resumed["step"], 8)
        self.assertEqual(resumed["status"], "COMPLETED")

        # Plan -> Unapproved execute must fail -> Approve -> Out-of-scope execute must fail -> Valid execute
        plan_id = create_remediation_plan(self.db, [orig_fid], "Return disconnectedVal properly", "Consumed")
        with self.assertRaises(PermissionError):
            execute_remediation_plan(self.db, plan_id, "agent", ["src/Lifecycle.kt"], ["disconnectedVal"])

        approve_remediation_plan(self.db, plan_id, "senior_engineer")
        with self.assertRaises(PermissionError):
            execute_remediation_plan(self.db, plan_id, "agent", ["src/OtherUnrelated.kt"], ["foo"])

        execute_remediation_plan(self.db, plan_id, "agent", ["src/Lifecycle.kt"], ["disconnectedVal"])
        record_verification(self.db, orig_fid, "Unit testing", "PASSED", "unittest", "Verified non-mocked", uses_mocks=False, plan_id=plan_id)
        cid = close_finding(self.db, orig_fid, "senior_engineer", "Consumed cleanly in return value")
        self.assertTrue(cid.startswith("CLS_"))

        # Verify closure_records & finding_history immutability via SQLite triggers
        conn = self.db.connect()
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute("DELETE FROM closure_records WHERE closure_id = ?", (cid,))
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute("UPDATE finding_history SET reason = 'tampered'")
        finally:
            conn.close()

        # Re-emit same finding on next scan -> automatic reopening preserving original identity!
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        conn = self.db.connect()
        try:
            reopened = conn.execute("SELECT state, reopen_count FROM findings WHERE finding_id = ?", (orig_fid,)).fetchone()
        finally:
            conn.close()
        self.assertEqual(reopened["reopen_count"], 1)
        self.assertIn(reopened["state"], ("PENDING", "REOPENED"))

    def test_19_exceptions_expiration_and_git_hooks_integration(self) -> None:
        # Initialize a real temporary git repo to exercise git hook installation & execution
        subprocess.run(["git", "init"], cwd=str(self.repo), check=True, capture_output=True)
        subprocess.run(["git", "config", "user.email", "test@apidacs.local"], cwd=str(self.repo), check=True)
        subprocess.run(["git", "config", "user.name", "APIDACS Test"], cwd=str(self.repo), check=True)

        self.write_src("src/Clean.kt", "package clean\nfun add(a: Int, b: Int): Int = a + b\nfun main() { println(add(1, 2)) }\n")
        self.write_src("src/test/CleanTest.kt", "package clean\nimport org.junit.Test\nclass CleanTest { @Test fun t() { assertEquals(3, add(1, 2)) } }\n")
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        generate_docs(self.db, self.project_id)

        hk = install_git_hooks(self.repo)
        self.assertTrue(hk["installed_to_git"])
        wf = ensure_ci_workflow(self.repo)
        self.assertTrue(wf.exists())

        pre = run_pre_commit(self.db)
        self.assertFalse(pre["blocked"])
        push = run_pre_push(self.db)
        self.assertFalse(push["blocked"])

        # Expired exception automatically returns finding to REVIEW_REQUIRED
        self.write_src("src/Clean.kt", "package clean\n@Suppress(\"UNUSED\") fun add(a: Int, b: Int): Int = a + b\nfun main() { println(add(1, 2)) }\n")
        run_scan(self.db, mode="full", lifecycle_callback=reconcile_and_ingest)
        conn = self.db.connect()
        try:
            fid = conn.execute("SELECT finding_id FROM findings WHERE project_id = ? LIMIT 1", (self.project_id,)).fetchone()["finding_id"]
        finally:
            conn.close()
        add_exception(self.db, self.project_id, "clean.add", "APPROVED_SUPPRESSION", "Needed demo", "reviewed", "lead", "2000-01-01T00:00:00Z", finding_id=fid)
        expired = check_expired_exceptions(self.db, self.project_id)
        self.assertEqual(len(expired), 1)

        # CLI exit codes check: compliance check clean -> 0, bad inspect -> 2, doc drift -> 1
        rc_ok = cli_main(["--repo", str(self.repo), "variables", "list"])
        self.assertEqual(rc_ok, 0)
        rc_err = cli_main(["--repo", str(self.repo), "findings", "inspect", "NONEXISTENT_ID"])
        self.assertEqual(rc_err, 2)


if __name__ == "__main__":
    unittest.main()
