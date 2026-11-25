"""Multi-language passive source parsers with Kotlin PSI AST & SHA-256 caching."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import tomllib
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from .models import sha256_bytes

SUPPORTED_EXTENSIONS: Dict[str, str] = {
    ".kt": "kotlin",
    ".kts": "kotlin_script",
    ".xml": "xml",
    ".toml": "toml",
    ".sh": "shell",
    ".json": "json",
    ".properties": "properties",
}

IGNORED_DIRS: Set[str] = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".apidacs",
    "build",
    "captures",
    "__pycache__",
    ".cxx",
    "node_modules",
}


@dataclass
class DiscoveredFile:
    rel_path: str
    abs_path: Path
    language: str
    module_name: str
    sha256: str
    size_bytes: int
    line_count: int
    mtime_ns: int
    is_generated: bool
    is_third_party: bool
    is_test: bool


def _find_kotlin_classpath() -> Tuple[str, List[str]]:
    """Locate kotlin-compiler-embeddable + runtime dependencies cached offline."""
    search_roots = [
        Path("/opt/gradle/.gradle/caches/modules-2/files-2.1"),
        Path("/opt/gradle/gradle-9.3.1/lib"),
        Path.home() / ".gradle/caches/modules-2/files-2.1",
    ]

    def find_first(patterns: Sequence[str]) -> Optional[Path]:
        for root in search_roots:
            if not root.exists():
                continue
            for pat in patterns:
                matches = sorted(root.rglob(pat))
                if matches:
                    return matches[0]
        return None

    compiler = find_first(["kotlin-compiler-embeddable-2.4*.jar", "kotlin-compiler-embeddable-*.jar"])
    stdlib = find_first(["kotlin-stdlib-2.4*.jar", "kotlin-stdlib-2.*.jar"])
    script_rt = find_first(["kotlin-script-runtime-2.4*.jar", "kotlin-script-runtime-*.jar"])
    coro = find_first(["kotlinx-coroutines-core-jvm-1.8.0-intellij*.jar", "kotlinx-coroutines-core-jvm-*.jar"])

    jars = [str(p) for p in (compiler, stdlib, script_rt, coro) if p is not None]
    if not compiler:
        raise RuntimeError("Could not locate kotlin-compiler-embeddable JAR offline.")
    return str(compiler), jars


def ensure_psi_helper(repo_root: Path) -> Tuple[Path, List[str]]:
    """Compile PsiExtractor.java into .apidacs/psi_helper.jar if missing or outdated."""
    repo_root = Path(repo_root).resolve()
    toolkit_dir = Path(__file__).resolve().parent.parent
    java_src = toolkit_dir / "psi_extractor" / "PsiExtractor.java"
    runtime_dir = repo_root / ".apidacs"
    runtime_dir.mkdir(parents=True, exist_ok=True)
    jar_path = runtime_dir / "psi_helper.jar"
    classes_dir = runtime_dir / "psi_classes"

    compiler_jar, cp_jars = _find_kotlin_classpath()

    needs_build = (
        not jar_path.exists()
        or jar_path.stat().st_mtime < java_src.stat().st_mtime
    )
    if needs_build:
        classes_dir.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["javac", "-cp", compiler_jar, "-d", str(classes_dir), str(java_src)],
            check=True,
            capture_output=True,
            text=True,
        )
        subprocess.run(
            ["jar", "cf", str(jar_path), "-C", str(classes_dir), "."],
            check=True,
            capture_output=True,
            text=True,
        )
    return jar_path, cp_jars


def discover_source_files(repo_root: Path, include_apidacs: bool = False) -> List[DiscoveredFile]:
    """Walk repo safely respecting ignore directories and build outputs."""
    repo_root = Path(repo_root).resolve()
    results: List[DiscoveredFile] = []

    for dirpath, dirnames, filenames in os.walk(repo_root):
        rel_dir = Path(dirpath).relative_to(repo_root)
        # Filter ignored subdirectories in-place
        pruned = []
        for d in sorted(dirnames):
            if d in IGNORED_DIRS:
                continue
            if not include_apidacs and d == "APIDACS" and rel_dir == Path("."):
                continue
            pruned.append(d)
        dirnames[:] = pruned

        for fname in sorted(filenames):
            fp = Path(dirpath) / fname
            ext = fp.suffix.lower()
            if ext not in SUPPORTED_EXTENSIONS:
                continue
            rel_path = fp.relative_to(repo_root).as_posix()
            # Skip generated gradle reports or build intermediates even if nested
            if "/build/" in rel_path or rel_path.startswith("build/"):
                continue

            raw = fp.read_bytes()
            sha = sha256_bytes(raw)
            text = raw.decode("utf-8", errors="replace")
            line_count = text.count("\n") + (1 if text and not text.endswith("\n") else 0)
            st = fp.stat()

            parts = rel_path.split("/")
            module_name = parts[0] if len(parts) > 1 and parts[0] in ("app", "desktop", "scripts", "gradle") else "root"
            is_test = (
                "/src/test/" in rel_path
                or "/src/androidTest/" in rel_path
                or rel_path.endswith("Test.kt")
            )
            is_generated = "/generated/" in rel_path or rel_path.startswith("generated/")
            is_third_party = "/third_party/" in rel_path or "/vendor/" in rel_path

            results.append(
                DiscoveredFile(
                    rel_path=rel_path,
                    abs_path=fp,
                    language=SUPPORTED_EXTENSIONS[ext],
                    module_name=module_name,
                    sha256=sha,
                    size_bytes=len(raw),
                    line_count=line_count,
                    mtime_ns=st.st_mtime_ns,
                    is_generated=is_generated,
                    is_third_party=is_third_party,
                    is_test=is_test,
                )
            )
    return results


def parse_kotlin_batch(
    repo_root: Path,
    files: Sequence[DiscoveredFile],
    cache_dir: Optional[Path] = None,
) -> Dict[str, Dict[str, Any]]:
    """Parse Kotlin (.kt / .kts) files via real PSI AST with SHA-256 disk caching."""
    repo_root = Path(repo_root).resolve()
    env_cdir = os.environ.get("APIDACS_CACHE_DIR")
    cdir = cache_dir or (Path(env_cdir) if env_cdir else (repo_root / ".apidacs" / "cache"))
    cdir.mkdir(parents=True, exist_ok=True)

    parsed: Dict[str, Dict[str, Any]] = {}
    to_parse: List[DiscoveredFile] = []

    for df in files:
        if df.language not in ("kotlin", "kotlin_script"):
            continue
        cache_file = cdir / f"{df.sha256}.psi.json"
        if cache_file.exists():
            try:
                cached = json.loads(cache_file.read_text(encoding="utf-8"))
                cached["path"] = df.rel_path
                parsed[df.rel_path] = cached
                continue
            except Exception:
                pass
        to_parse.append(df)

    if to_parse:
        jar_path, cp_jars = ensure_psi_helper(repo_root)
        cp = ":".join([str(jar_path)] + cp_jars)

        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as mf:
            for df in to_parse:
                mf.write(str(df.abs_path) + "\n")
            manifest_path = mf.name

        try:
            proc = subprocess.run(
                ["java", "-Xmx768m", "-cp", cp, "apidacs.psi.PsiExtractor", "--manifest", manifest_path],
                capture_output=True,
                text=True,
                check=False,
            )
            if proc.returncode != 0:
                raise RuntimeError(f"PsiExtractor failed ({proc.returncode}): {proc.stderr[:800]}")
            records = json.loads(proc.stdout)
            abs_to_df = {str(df.abs_path): df for df in to_parse}
            for rec in records:
                abs_p = rec.get("path", "")
                df = abs_to_df.get(abs_p)
                if df is None:
                    continue
                rec["path"] = df.rel_path
                parsed[df.rel_path] = rec
                cache_file = cdir / f"{df.sha256}.psi.json"
                try:
                    cache_file.write_text(json.dumps(rec), encoding="utf-8")
                except OSError:
                    pass
        finally:
            try:
                os.unlink(manifest_path)
            except OSError:
                pass

    return parsed


def parse_xml_file(df: DiscoveredFile) -> Dict[str, Any]:
    """Parse AndroidManifest.xml, strings.xml, and other XML configs structurally."""
    out: Dict[str, Any] = {
        "path": df.rel_path,
        "language": "xml",
        "parseStatus": "OK",
        "parseError": "",
        "permissions": [],
        "components": [],
        "strings": [],
        "references": [],
    }
    try:
        tree = ET.parse(df.abs_path)
        root = tree.getroot()
        android_ns = "{http://schemas.android.com/apk/res/android}"
        if root.tag == "manifest":
            for perm in root.findall("uses-permission"):
                name = perm.attrib.get(android_ns + "name") or perm.attrib.get("name", "")
                if name:
                    out["permissions"].append(name)
            app_el = root.find("application")
            if app_el is not None:
                for tag in ("activity", "service", "receiver", "provider"):
                    for comp in app_el.findall(tag):
                        cname = comp.attrib.get(android_ns + "name") or comp.attrib.get("name", "")
                        if cname:
                            out["components"].append({"kind": tag, "name": cname})
                            out["references"].append(cname)
        elif root.tag == "resources":
            for s in root.findall("string"):
                sname = s.attrib.get("name", "")
                if sname:
                    out["strings"].append({"name": sname, "value": (s.text or "").strip()})
        else:
            # Capture class/drawable refs in adaptive icon or backup XMLs
            for el in root.iter():
                for _, v in el.attrib.items():
                    if v.startswith("@") or "." in v:
                        out["references"].append(v)
    except Exception as exc:
        out["parseStatus"] = "ERROR"
        out["parseError"] = str(exc)
    return out


def parse_toml_file(df: DiscoveredFile) -> Dict[str, Any]:
    """Parse Gradle version catalog TOML."""
    out: Dict[str, Any] = {
        "path": df.rel_path,
        "language": "toml",
        "parseStatus": "OK",
        "parseError": "",
        "versions": {},
        "libraries": [],
        "plugins": [],
    }
    try:
        with df.abs_path.open("rb") as fh:
            data = tomllib.load(fh)
        out["versions"] = {k: str(v) for k, v in data.get("versions", {}).items()}
        for k, v in data.get("libraries", {}).items():
            out["libraries"].append({"alias": k, "spec": str(v)})
        for k, v in data.get("plugins", {}).items():
            out["plugins"].append({"alias": k, "spec": str(v)})
    except Exception as exc:
        out["parseStatus"] = "ERROR"
        out["parseError"] = str(exc)
    return out


def parse_shell_file(df: DiscoveredFile) -> Dict[str, Any]:
    """Validate shell script syntax passively via `bash -n` without executing."""
    out: Dict[str, Any] = {
        "path": df.rel_path,
        "language": "shell",
        "parseStatus": "OK",
        "parseError": "",
        "commands": [],
    }
    proc = subprocess.run(["bash", "-n", str(df.abs_path)], capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        out["parseStatus"] = "ERROR"
        out["parseError"] = proc.stderr.strip()
    text = df.abs_path.read_text(encoding="utf-8", errors="replace")
    for idx, line in enumerate(text.splitlines(), start=1):
        s = line.strip()
        if s.startswith("gradle ") or s.startswith("java ") or s.startswith("python3 "):
            out["commands"].append({"line": idx, "command": s[:120]})
    return out


def parse_json_file(df: DiscoveredFile) -> Dict[str, Any]:
    out: Dict[str, Any] = {
        "path": df.rel_path,
        "language": "json",
        "parseStatus": "OK",
        "parseError": "",
        "top_keys": [],
    }
    try:
        data = json.loads(df.abs_path.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            out["top_keys"] = sorted(data.keys())
    except Exception as exc:
        out["parseStatus"] = "ERROR"
        out["parseError"] = str(exc)
    return out
