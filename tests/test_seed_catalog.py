"""Wave 3 regression tests: fresh-install seed source (F01/F08) and the
A34 redeploy refresh.

Before the fix, a fresh install seeded the Android app's bundled asset
catalog (26 devices, dataVersion 2026.11.24.1, no browser-version
defaults) and the generator silently produced zero rows; a redeploy
never updated an already-seeded catalog.
"""

import json
from pathlib import Path

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

import backend.app as api
from backend.app import Base, Device, MetaRecord, read_seed, seed_database

ROOT = Path(__file__).resolve().parents[1]


def make_session(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path}/seed-test.db")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def catalog(version, android_rows, ios_rows=None):
    return {
        "meta": {"dataVersion": version,
                 "browserVersions": {"chromeAndroid": ["100.0.0.0"]}},
        "android": android_rows,
        "ios_emulation": ios_rows or [],
    }


def row(device_id, model):
    return {"id": device_id, "brand": "Test", "model": model}


def test_read_seed_rejects_a_catalog_without_browser_defaults(tmp_path, monkeypatch):
    bad = tmp_path / "devices.json"
    bad.write_text(json.dumps({"meta": {"dataVersion": "2026.11.24.1"},
                               "android": [], "ios_emulation": []}))
    monkeypatch.setenv("NEXORA_DATA_FILE", str(bad))
    with pytest.raises(RuntimeError, match="no browser-version defaults"):
        read_seed()


def test_fresh_seed_serves_the_published_catalog(tmp_path, monkeypatch):
    monkeypatch.delenv("NEXORA_DATA_FILE", raising=False)
    published = json.loads((ROOT / "web" / "devices.json").read_text(encoding="utf-8"))
    db = make_session(tmp_path)
    seed_database(db)
    expected = len(published["android"]) + len(published["ios_emulation"])
    assert db.scalar(select(func.count()).select_from(Device)) == expected
    meta = db.get(MetaRecord, 1)
    assert meta is not None
    assert meta.payload["dataVersion"] == published["meta"]["dataVersion"]
    assert meta.payload["browserVersions"] == published["meta"]["browserVersions"]


def test_redeploy_refreshes_seeded_rows_and_keeps_admin_rows(tmp_path, monkeypatch):
    v1 = catalog("2026.11.25.1",
                 [row("dev-a", "A One"), row("dev-b", "B One"), row("dev-d", "D One")])
    v2 = catalog("2026.11.25.2",
                 [row("dev-a", "A Two"), row("dev-b", "B Two"), row("dev-c", "C One")])
    db = make_session(tmp_path)

    monkeypatch.setattr(api, "read_seed", lambda: v1)
    seed_database(db)
    assert db.scalar(select(func.count()).select_from(Device)) == 3

    # The admin claims dev-b and edits its record after the first seed.
    claimed = db.get(Device, "dev-b")
    claimed.source_status = "admin_verified"
    claimed.record = row("dev-b", "B Admin")
    db.commit()

    monkeypatch.setattr(api, "read_seed", lambda: v2)
    seed_database(db)

    devices = {d.id: d for d in db.scalars(select(Device)).all()}
    assert set(devices) == {"dev-a", "dev-b", "dev-c"}  # dev-d dropped, dev-c added
    assert devices["dev-a"].record["model"] == "A Two"  # seed-owned row refreshed
    assert devices["dev-b"].record["model"] == "B Admin"  # admin row untouched
    assert devices["dev-b"].source_status == "admin_verified"
    assert db.get(MetaRecord, 1).payload["dataVersion"] == "2026.11.25.2"

    # Seeding the same version again is a no-op.
    seed_database(db)
    assert db.scalar(select(func.count()).select_from(Device)) == 3
    assert db.get(Device, "dev-a").record["model"] == "A Two"
