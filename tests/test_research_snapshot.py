"""Wave 5 regression test for C10: the Workstream B research snapshot
referenced a versioned catalog file (data/devices-2026.11.25.7.json)
that was absent from the checkout, breaking traceability. Every
catalog snapshot referenced by a research file must exist, and the
history index must carry the current catalog version with its
snapshot file present.
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_referenced_catalog_snapshots_exist():
    research_files = sorted((ROOT / "data").glob("ua_templates.research-*.json"))
    assert research_files, "no research snapshot files found"
    for research_file in research_files:
        research = json.loads(research_file.read_text(encoding="utf-8"))
        snapshot = research.get("catalogSnapshot") or {}
        snapshot_path = snapshot.get("file") if isinstance(snapshot, dict) else snapshot
        assert snapshot_path, f"{research_file.name} names no catalog snapshot"
        assert (ROOT / snapshot_path).exists(), f"{research_file.name} references missing {snapshot_path}"
        archived = json.loads((ROOT / snapshot_path).read_text(encoding="utf-8"))
        assert archived["meta"]["dataVersion"] == snapshot.get("dataVersion"), "archived snapshot version differs from the research reference"


def test_history_index_matches_snapshot_files():
    index = json.loads((ROOT / "data" / "history" / "index.json").read_text(encoding="utf-8"))
    versions = [entry["dataVersion"] for entry in index]
    for version in versions:
        assert (ROOT / "data" / "history" / f"devices-{version}.json").exists(), f"index lists {version} but its snapshot file is missing"
    current = json.loads((ROOT / "web" / "devices.json").read_text(encoding="utf-8"))
    assert current["meta"]["dataVersion"] in versions, "current catalog version is not archived in the history index"
    mirror = json.loads((ROOT / "web" / "history.json").read_text(encoding="utf-8"))
    assert mirror == index, "web/history.json mirror differs from data/history/index.json"
