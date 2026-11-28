"""Wave 3 regression test for A23: the admin sources route uses PATCH,
so a cross-origin admin client's preflight must be allowed to use it.
Before the fix, CORSMiddleware's allow_methods omitted PATCH.
"""

from fastapi.testclient import TestClient

from backend.app import app

client = TestClient(app)


def test_cors_preflight_allows_patch():
    response = client.options(
        "/api/v1/admin/sources/example",
        headers={"Origin": "https://admin.example",
                 "Access-Control-Request-Method": "PATCH"},
    )
    assert response.status_code == 200, response.text
    allowed = response.headers.get("access-control-allow-methods", "")
    assert "PATCH" in allowed
