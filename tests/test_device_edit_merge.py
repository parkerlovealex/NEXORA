"""Wave 3 regression test for B02: an admin device edit must merge —
fields the edit does not mention keep their stored values. Before the
fix, update_device replaced the whole record, so any stored field the
edit body omitted (markets, evidence, extra data) was silently lost.
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


def test_edit_that_omits_fields_preserves_them(official_source):
    with client:
        headers = admin_headers()
        record = {
            "id": "b02-merge-device", "brand": "NEXORA Test", "model": "Test One",
            "uaModel": "NX-TEST-1", "soc": "Test SoC", "gpu": "Test GPU", "cores": 8,
            "display": {"w": 1080, "h": 2400, "dpr": 3.0}, "ramGb": [8], "storageGb": [128],
            "osVersions": ["15"], "builds": {}, "markets": ["US"],
            "source_url": "https://www.samsung.com/global/galaxy/",
        }
        created = client.post("/api/v1/admin/devices", headers=headers,
                              json={"platform": "android", "record": record})
        assert created.status_code == 201, created.text

        edit = {k: v for k, v in record.items() if k != "markets"}
        edit["model"] = "Test One Updated"
        updated = client.put("/api/v1/admin/devices/b02-merge-device", headers=headers,
                             json={"platform": "android", "record": edit})
        assert updated.status_code == 200, updated.text

        stored = next(x["record"] for x in client.get("/api/v1/devices").json()
                      if x["id"] == "b02-merge-device")
        assert stored["model"] == "Test One Updated"  # the edit applied
        assert stored["markets"] == ["US"]  # the unmentioned field survived
        assert stored.get("fieldEvidence")  # evidence survived
