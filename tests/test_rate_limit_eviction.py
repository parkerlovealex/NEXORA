"""Wave 3 regression tests for A33: rate-limit storage must evict
inactive client keys. Before the fix, expired timestamps were filtered
only when the same client returned, so both dictionaries grew forever
with the number of distinct clients.
"""

import time

from fastapi.testclient import TestClient

import backend.app as api

client = TestClient(api.app)


def test_inactive_keys_are_evicted_and_active_keys_survive():
    now = time.time()
    api._api_windows.clear()
    api._login_attempts.clear()
    for i in range(1100):
        api._api_windows[f"203.0.113.{i}"] = [now - 3600]  # long expired
    api._api_windows["198.51.100.7"] = [now]  # still active
    for i in range(1100):
        api._login_attempts[f"203.0.113.{i}"] = [now - 3600]
    api._login_attempts["198.51.100.7"] = [now]

    response = client.get("/api/v1/health")
    assert response.status_code == 200, response.text

    assert "198.51.100.7" in api._api_windows
    assert "198.51.100.7" in api._login_attempts
    assert len(api._api_windows) < 100
    assert len(api._login_attempts) < 100
    api._api_windows.clear()
    api._login_attempts.clear()
