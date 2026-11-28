"""Wave 5 regression test for C18: public stats must count an app
version as verified only when the stored entry retains the provenance
quote it was verified against. Before the fix, any truthy entry —
including legacy seeded strings with no evidence at all — counted as
verified.
"""

import os

import pytest
from fastapi.testclient import TestClient

from backend.app import app

client = TestClient(app)


@pytest.fixture
def official_source(monkeypatch):
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


def admin_headers():
    response = client.post("/api/v1/admin/login", json={"password": os.environ["NEXORA_ADMIN_PASSWORD"]})
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['accessToken']}"}


def test_seeded_apps_are_present_but_not_verified(official_source):
    with client:
        stats = client.get("/api/v1/stats").json()
        assert stats["applications"] > 0
        # Seeded catalog entries carry version/build pairs but no retained
        # provenance quote, so none of them may count as verified.
        assert stats["verifiedAppVersions"] == 0
        assert stats["missingAppVersions"] == stats["applications"]


def test_admin_saved_version_counts_as_verified(official_source):
    with client:
        headers = admin_headers()
        apps = client.get("/api/v1/data").json()["meta"]["apps"]
        app_id = next(iter(apps))
        saved = client.post("/api/v1/admin/app-versions", headers=headers, json={
            "app_id": app_id, "version": "test.1",
            "source_url": "https://www.samsung.com/global/galaxy/",
        })
        assert saved.status_code == 200, saved.text
        stats = client.get("/api/v1/stats").json()
        assert stats["verifiedAppVersions"] == 1
        assert stats["missingAppVersions"] == stats["applications"] - 1
