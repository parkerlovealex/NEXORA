"""Wave 5 regression test for A27: the weekly refresh must not report
success when the Chrome version check fails, and a missing current
Chrome version must be filled from the fetched value instead of being
reported as "already current". Before the fix, a fetch exception only
printed a warning and an empty current value took the already-current
branch; both runs exited 0.
"""

import json

from tools import refresh_devices as rd


def run_main(tmp_path, monkeypatch, catalog_versions, fetch):
    web = tmp_path / "web_devices.json"
    bundled = tmp_path / "bundled_devices.json"
    catalog = {
        "meta": {"dataVersion": "2026.11.25.7", "updatedAt": "2026-11-25", "versions": catalog_versions},
        "android": [{"id": "a"}],
        "ios_emulation": [],
    }
    web.write_text(json.dumps(catalog), encoding="utf-8")
    bundled.write_text(json.dumps(catalog), encoding="utf-8")
    monkeypatch.setattr(rd, "WEB_DEVICES", str(web))
    monkeypatch.setattr(rd, "DEVICES", str(bundled))
    monkeypatch.setattr(rd, "SUMMARY", str(tmp_path / "summary.md"))
    monkeypatch.setattr(rd, "HISTORY_DIR", str(tmp_path / "history"))
    monkeypatch.setattr(rd, "HISTORY_INDEX", str(tmp_path / "history" / "index.json"))
    monkeypatch.setattr(rd, "fetch_json", fetch)
    monkeypatch.setattr(rd, "archive_history", lambda data: [])
    monkeypatch.setattr("sys.argv", ["refresh_devices.py"])
    result = rd.main()
    return result, json.loads(web.read_text(encoding="utf-8"))


def test_fetch_failure_fails_the_run(tmp_path, monkeypatch):
    def boom(url):
        raise RuntimeError("source unavailable")
    result, _ = run_main(tmp_path, monkeypatch, {"chrome": "140.0.0.0"}, boom)
    assert result == 1


def test_missing_current_version_is_filled(tmp_path, monkeypatch, capsys):
    result, saved = run_main(tmp_path, monkeypatch, {"chrome": None},
                             lambda url: {"versions": [{"version": "141.0.0.0"}]})
    assert result == 0
    assert saved["meta"]["versions"]["chrome"] == "141.0.0.0"
    assert "already current" not in capsys.readouterr().out


def test_newer_version_updates_and_current_version_passes(tmp_path, monkeypatch):
    result, saved = run_main(tmp_path, monkeypatch, {"chrome": "140.0.0.0"},
                             lambda url: {"versions": [{"version": "141.0.0.0"}]})
    assert result == 0
    assert saved["meta"]["versions"]["chrome"] == "141.0.0.0"
    result, saved = run_main(tmp_path, monkeypatch, {"chrome": "141.0.0.0"},
                             lambda url: {"versions": [{"version": "141.0.0.0"}]})
    assert result == 0
    assert saved["meta"]["versions"]["chrome"] == "141.0.0.0"
