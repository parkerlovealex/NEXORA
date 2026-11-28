"""Wave 5 regression tests for C03: the weekly refresh must treat the
website catalog as canonical. Before the fix it loaded the bundled
Android asset and wrote that object over BOTH files, so a refresh could
erase website-only data. It must also refuse to write when the result
would shrink the catalog below the bundled copy (diverged copies).
"""

import json
import sys

from tools import refresh_devices as refresh


def _catalog(ids, chrome="1.0.0.0"):
    return {"meta": {"dataVersion": "2026.11.25.1", "versions": {"chrome": chrome}},
            "android": [{"id": i} for i in ids], "ios_emulation": []}


def _setup(tmp_path, monkeypatch, web_ids, bundled_ids):
    monkeypatch.chdir(tmp_path)
    web = tmp_path / "web" / "devices.json"
    web.parent.mkdir(parents=True)
    web.write_text(json.dumps(_catalog(web_ids)))
    bundled = tmp_path / "app" / "src" / "main" / "assets" / "devices.json"
    bundled.parent.mkdir(parents=True)
    bundled.write_text(json.dumps(_catalog(bundled_ids)))
    monkeypatch.setattr(refresh, "WEB_DEVICES", str(web))
    monkeypatch.setattr(refresh, "DEVICES", str(bundled))
    monkeypatch.setattr(refresh, "fetch_json", lambda _: {"versions": [{"version": "2.0.0.0"}]})
    monkeypatch.setattr(refresh, "archive_history", lambda data: [])
    monkeypatch.setattr(sys, "argv", ["refresh_devices.py"])
    return web, bundled


def test_refresh_preserves_the_richer_web_catalog(tmp_path, monkeypatch):
    web, bundled = _setup(tmp_path, monkeypatch, ["web-a", "web-b"], ["asset-a"])
    assert refresh.main() == 0
    for path in (web, bundled):
        written = json.loads(path.read_text())
        assert [d["id"] for d in written["android"]] == ["web-a", "web-b"]
        assert written["meta"]["versions"]["chrome"] == "2.0.0.0"
        assert written["meta"]["dataVersion"] != "2026.11.25.1"


def test_refresh_refuses_to_shrink_below_the_bundled_copy(tmp_path, monkeypatch):
    web, bundled = _setup(tmp_path, monkeypatch, ["web-a"], ["asset-a", "asset-b"])
    before_web, before_bundled = web.read_text(), bundled.read_text()
    assert refresh.main() == 1
    assert web.read_text() == before_web
    assert bundled.read_text() == before_bundled
