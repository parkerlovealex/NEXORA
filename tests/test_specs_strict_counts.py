"""Wave 5 regression test for C12: strict mode promotes warnings to
errors, but the same observations must not also be reported (and
counted) as warnings. Before the fix, a catalog with N warnings and no
errors reported "N errors, N warnings" in strict mode.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "validate_specs.py"


def _run(tmp_path, *extra):
    catalog = tmp_path / "catalog.json"
    evidence = tmp_path / "evidence.json"
    catalog.write_text(json.dumps({"android": [{"id": "dev-x", "display": {}}]}))
    evidence.write_text(json.dumps({"schemaVersion": 1, "devices": {}}))
    proc = subprocess.run(
        [sys.executable, str(TOOL), str(catalog), "--evidence", str(evidence), *extra],
        capture_output=True, text=True)
    summary = re.search(r"(\d+) errors, (\d+) warnings", proc.stdout)
    assert summary, proc.stdout
    return proc, int(summary.group(1)), int(summary.group(2))


def test_normal_mode_reports_warnings_once(tmp_path):
    proc, errors, warnings = _run(tmp_path)
    assert proc.returncode == 0
    assert errors == 0 and warnings >= 1
    assert proc.stdout.count("WARN:") == warnings


def test_strict_mode_does_not_double_count(tmp_path):
    _, _, normal_warnings = _run(tmp_path)
    proc, errors, warnings = _run(tmp_path, "--strict")
    assert proc.returncode == 1
    assert warnings == 0, proc.stdout
    assert errors == normal_warnings, proc.stdout
    assert proc.stdout.count("WARN:") == 0
    assert proc.stdout.count("ERROR: STRICT:") == normal_warnings
