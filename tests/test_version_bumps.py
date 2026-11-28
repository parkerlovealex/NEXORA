"""Wave 3 regression tests for A40 + A43: every successful catalog
mutation must advance meta.dataVersion, because clients compare it to
decide whether to install the catalog.

A40: the app-version collector updated only updatedAt on a merge.
A43: approving a device proposal added the device without the bump that
direct creation performs.
"""

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import backend.app as api
from backend.app import app


@pytest.fixture
def official_source(monkeypatch):
    # Same official-source text the API tests use: every field of the
    # proposal record below is quoted in these lines.
    lines = [
        "Brand: NEXORA Test", "Model: Test One", "Model: Test One Updated",
        "Model code: NX-TEST-1", "Brand: Proposal", "Model: Review Device",
        "Model code: PR-1", "SoC: Test SoC", "GPU: Test GPU", "SoC: Example SoC",
        "GPU: Example GPU", "CPU cores: 8", "Resolution: 1080 x 2400",
        "Device pixel ratio: 3.0", "Device pixel ratio: 3", "RAM: 8 GB",
        "Storage: 128 GB", "Android 15 is supported", "Android 14 is supported",
        "Markets: US", "Application version: test.1",
    ]
    monkeypatch.setattr("backend.app.fetch_source_text", lambda url: "\n".join(lines))
    return lines

client = TestClient(app)
TOOL = Path(__file__).resolve().parents[1] / "tools" / "collect_app_versions.py"


def admin_headers():
    response = client.post("/api/v1/admin/login", json={"password": os.environ["NEXORA_ADMIN_PASSWORD"]})
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['accessToken']}"}


def stored_data_version():
    with api.SessionLocal() as db:
        row = db.get(api.MetaRecord, 1)
        return (row.payload or {}).get("dataVersion") if row else None


def test_device_proposal_approval_bumps_data_version(official_source):
    with client:
        headers = admin_headers()
        before = stored_data_version()
        record = {
            "id": "a43-proposal-device", "brand": "Proposal", "model": "Review Device",
            "uaModel": "PR-1", "soc": "Example SoC", "gpu": "Example GPU", "cores": 8,
            "display": {"w": 1080, "h": 2400, "dpr": 3}, "ramGb": [8], "storageGb": [128],
            "osVersions": ["14"], "builds": {}, "source_url": "https://www.samsung.com/global/galaxy/",
        }
        created = client.post("/api/v1/admin/proposals", headers=headers, json={
            "kind": "device", "title": "Review Device",
            "payload": {"platform": "android", "record": record},
            "evidence": [{"source": "test", "url": "https://example.com"}], "confidence": 0.9})
        assert created.status_code == 201, created.text
        decision = client.post(
            f"/api/v1/admin/proposals/{created.json()['id']}/decision?decision=approve",
            headers=headers)
        assert decision.status_code == 200, decision.text
        after = stored_data_version()
        assert after is not None and after != before


def run_collector(tmp_path, findings_apps):
    catalog = tmp_path / "input.json"
    catalog.write_text(json.dumps({"meta": {"dataVersion": "2020.01.01.3",
                                            "apps": {"facebook": {"version": "previous"}}}}))
    findings = tmp_path / "findings.json"
    findings.write_text(json.dumps({"apps": findings_apps}))
    proc = subprocess.run(
        [sys.executable, str(TOOL), "--input", str(catalog), "--findings", str(findings),
         "--out", str(tmp_path / "output.json"), "--evidence", str(tmp_path / "evidence.json"),
         "--report", str(tmp_path / "report.md")], capture_output=True, text=True)
    return proc, json.loads((tmp_path / "output.json").read_text())["meta"]


def test_collector_merge_bumps_data_version(tmp_path):
    proc, meta = run_collector(tmp_path, {"facebook": {
        "version": "1.2.3", "build": "420",
        "source_url": "https://example.org/releases",
        "quoted_text": "Version 1.2.3 (build 420)."}})
    assert proc.returncode == 0, proc.stderr
    today = datetime.now(timezone.utc).date()
    assert meta["dataVersion"] == f"{today.strftime('%Y.%m.%d')}.1"
    assert meta["updatedAt"] == today.isoformat()


def test_collector_without_a_merge_keeps_data_version(tmp_path):
    proc, meta = run_collector(tmp_path, {"facebook": {
        "version": "1.2.3", "build": "42",
        "source_url": "https://example.org/releases",
        "quoted_text": "The app listing shows a rating of 4.8"}})
    assert proc.returncode == 2
    assert meta["dataVersion"] == "2020.01.01.3"
