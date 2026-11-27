import os
import tempfile
import pytest

# The test command sets DATABASE_URL, NEXORA_ADMIN_PASSWORD and NEXORA_SECRET_KEY
# before importing the application module.
from fastapi.testclient import TestClient
from backend.app import app

client = TestClient(app)


def admin_headers():
    response = client.post("/api/v1/admin/login", json={"password": os.environ["NEXORA_ADMIN_PASSWORD"]})
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['accessToken']}"}


@pytest.mark.parametrize("short_key", [None, "x" * 31])
def test_admin_sessions_require_a_configured_signing_key(monkeypatch, short_key):
    with client:
        headers = admin_headers()
        assert client.get("/api/v1/admin/overview", headers=headers).status_code == 200
        if short_key is None:
            monkeypatch.delenv("NEXORA_SECRET_KEY")
        else:
            monkeypatch.setenv("NEXORA_SECRET_KEY", short_key)
        unavailable = client.get("/api/v1/admin/overview", headers=headers)
        assert unavailable.status_code == 503
        assert "NEXORA_SECRET_KEY" in unavailable.json()["detail"]


def test_public_data_and_stats():
    with client:
        page = client.get("/")
        assert page.status_code == 200
        assert "NEXORA Next" in page.text
        response = client.get("/api/v1/data")
        assert response.status_code == 200
        payload = response.json()
        assert payload["meta"]["schemaVersion"] == 2
        assert len(payload["android"]) > 0
        assert len(payload["ios_emulation"]) > 0
        stats = client.get("/api/v1/stats").json()
        assert stats["devices"] == len(payload["android"]) + len(payload["ios_emulation"])


def test_admin_auth_and_device_crud():
    with client:
        assert client.get("/api/v1/admin/overview").status_code == 401
        headers = admin_headers()
        record = {
            "id": "test-nexora-device", "brand": "NEXORA Test", "model": "Test One",
            "uaModel": "NX-TEST-1", "soc": "Test SoC", "gpu": "Test GPU", "cores": 8,
            "display": {"w": 1080, "h": 2400, "dpr": 3.0}, "ramGb": [8], "storageGb": [128],
            "osVersions": ["15"], "builds": {}, "markets": ["US"], "source_url": "https://www.samsung.com/global/galaxy/"
        }
        created = client.post("/api/v1/admin/devices", headers=headers, json={"platform": "android", "record": record})
        assert created.status_code == 201, created.text
        assert any(x["id"] == "test-nexora-device" for x in client.get("/api/v1/devices").json())
        changed = dict(record); changed["model"] = "Test One Updated"
        updated = client.put("/api/v1/admin/devices/test-nexora-device", headers=headers, json={"platform": "android", "record": changed})
        assert updated.status_code == 200, updated.text
        deleted = client.delete("/api/v1/admin/devices/test-nexora-device", headers=headers)
        assert deleted.status_code == 200, deleted.text


def test_validation_rejects_incomplete_device():
    with client:
        headers = admin_headers()
        response = client.post("/api/v1/admin/devices", headers=headers, json={"platform": "android", "record": {"id": "bad"}})
        assert response.status_code == 422


def test_proposal_requires_admin_and_approval_publishes():
    with client:
        headers = admin_headers()
        record = {
            "id": "proposal-device", "brand": "Proposal", "model": "Review Device",
            "uaModel": "PR-1", "soc": "Example SoC", "gpu": "Example GPU", "cores": 8,
            "display": {"w": 1080, "h": 2400, "dpr": 3}, "ramGb": [8], "storageGb": [128],
            "osVersions": ["14"], "builds": {}, "source_url": "https://www.samsung.com/global/galaxy/"
        }
        created = client.post("/api/v1/admin/proposals", headers=headers, json={"kind": "device", "title": "Review Device", "payload": {"platform": "android", "record": record}, "evidence": [{"source": "test", "url": "https://example.com"}], "confidence": 0.9})
        assert created.status_code == 201, created.text
        pid = created.json()["id"]
        decision = client.post(f"/api/v1/admin/proposals/{pid}/decision?decision=approve", headers=headers)
        assert decision.status_code == 200, decision.text
        assert any(x["id"] == "proposal-device" for x in client.get("/api/v1/devices").json())


def test_application_version_update_requires_official_source():
    with client:
        headers = admin_headers()
        bad = client.post("/api/v1/admin/app-versions", headers=headers, json={"app_id": "instagram", "version": "test.1", "source_url": "https://example.com/version"})
        assert bad.status_code == 422
        good = client.post("/api/v1/admin/app-versions", headers=headers, json={"app_id": "instagram", "version": "test.1", "source_url": "https://play.google.com/store/apps/details?id=com.instagram.android"})
        assert good.status_code == 200, good.text
        data = client.get("/api/v1/data").json()
        assert data["meta"]["apps"]["instagram"]["version"] == "test.1"


def test_official_source_allowlist_and_evidence_quotes():
    from backend.app import quote_is_supported, validate_official_source_url
    validate_official_source_url("https://play.google.com/store/apps/details?id=com.instagram.android")
    try:
        validate_official_source_url("https://example.com/device")
    except ValueError:
        pass
    else:
        raise AssertionError("Non-official source domain should be rejected")
    source = "The Samsung Galaxy Zeta has a 1080 x 2400 display and 8 GB RAM."
    assert quote_is_supported("Samsung Galaxy Zeta has a 1080 x 2400 display and 8 GB RAM", source, {"w": 1080, "h": 2400})
    assert not quote_is_supported("Samsung Galaxy Zeta has a 1440 x 3200 display", source, {"w": 1440, "h": 3200})


def test_admin_can_pause_and_enable_source_connector():
    with client:
        headers = admin_headers()
        sources = client.get("/api/v1/admin/sources", headers=headers).json()
        assert sources
        source_id = sources[0]["id"]
        paused = client.patch(f"/api/v1/admin/sources/{source_id}", headers=headers, json={"enabled": False})
        assert paused.status_code == 200 and paused.json()["enabled"] is False
        enabled = client.patch(f"/api/v1/admin/sources/{source_id}", headers=headers, json={"enabled": True})
        assert enabled.status_code == 200 and enabled.json()["enabled"] is True


def test_ai_assistant_fails_clearly_without_provider_key(monkeypatch):
    monkeypatch.delenv("AI_API_KEY", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    with client:
        headers = admin_headers()
        response = client.post("/api/v1/admin/ai/assist", headers=headers, json={"prompt": "Summarize catalog quality"})
        assert response.status_code == 503
        assert "AI_API_KEY" in response.json()["detail"]
