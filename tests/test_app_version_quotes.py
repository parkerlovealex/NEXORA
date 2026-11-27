import json
import subprocess
import sys
from pathlib import Path

import pytest


TOOL = Path(__file__).resolve().parents[1] / "tools/collect_app_versions.py"


def collect(tmp_path, version, build, quote):
    catalog = tmp_path / "input.json"
    catalog.write_text(json.dumps({"meta": {"apps": {"facebook": {"version": "previous"}}}}))
    findings = tmp_path / "findings.json"
    entry = {"version": version, "source_url": "https://example.org/releases", "quoted_text": quote}
    if build is not None:
        entry["build"] = build
    findings.write_text(json.dumps({"apps": {"facebook": entry}}))
    proc = subprocess.run([sys.executable, str(TOOL), "--input", str(catalog), "--findings", str(findings),
                           "--out", str(tmp_path / "output.json"), "--evidence", str(tmp_path / "evidence.json"),
                           "--report", str(tmp_path / "report.md")], capture_output=True, text=True)
    assert proc.returncode in (0, 2), proc.stderr
    return (proc.returncode, json.loads((tmp_path / "output.json").read_text())["meta"]["apps"]["facebook"],
            json.loads((tmp_path / "evidence.json").read_text())["apps"])


@pytest.mark.parametrize("version,build,quote", [
    ("1.2.3", "420", "Release 1.2.30 (build 420)"),
    ("1.2.3", "42", "Release 1.2.3 (build 420)"),
    ("1.2.3", "42", "Version 1.2.3 (build 40)\nVersion 1.2.4 (build 42)"),
    ("1.2.3", "42", "Version 1.2.3 (build 40), version 1.2.4 (build 42)"),
    ("1.2.3", None, "The app listing shows a rating of 4.8"),
])
def test_unsupported_pair_keeps_the_existing_entry(tmp_path, version, build, quote):
    result, entry, evidence = collect(tmp_path, version, build, quote)
    assert (result, entry, evidence) == (2, {"version": "previous"}, {})


@pytest.mark.parametrize("build,quote", [
    ("420", "Version 1.2.3 (build 420)."), ("420", "1.2.3 | 420"),
    (None, "Version 1.2.3."), ("420", "Build 420, version 1.2.3."),
])
def test_matching_pair_retains_its_original_excerpt(tmp_path, build, quote):
    result, entry, evidence = collect(tmp_path, "1.2.3", build, quote)
    assert result == 0
    assert entry["version"] == "1.2.3"
    assert entry.get("build") == build
    assert evidence["facebook"]["quoted_text"] == quote
