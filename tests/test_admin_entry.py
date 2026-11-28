"""Wave 3 regression test for B04: the documented single-process
deployment must serve the admin entry URL. Before the fix, FastAPI
returned 200 for / but 404 for /admin and /admin/, and the frontend
shows its sign-in chip only on that path — a new administrator could
not reach sign-in by following the deployment instructions.
"""

from fastapi.testclient import TestClient

from backend.app import app

client = TestClient(app)


def test_admin_entry_urls_serve_the_app():
    for path in ("/admin", "/admin/"):
        response = client.get(path)
        assert response.status_code == 200, (path, response.status_code)
        assert "NEXORA Next" in response.text


def test_public_root_still_serves():
    response = client.get("/")
    assert response.status_code == 200
    assert "NEXORA Next" in response.text
