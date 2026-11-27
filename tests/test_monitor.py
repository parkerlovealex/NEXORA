from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from backend import app as api


def test_chrome_auto_publish_requires_current_connector_trust(tmp_path, monkeypatch):
    db_engine = create_engine(f"sqlite:///{tmp_path / 'monitor.db'}")
    api.Base.metadata.create_all(db_engine)
    sessions = sessionmaker(bind=db_engine)
    monkeypatch.setattr(api, "SessionLocal", sessions)
    url = "https://versionhistory.googleapis.com/v1/chrome/platforms/android/channels/stable/versions"
    response = {"versions": [{"version": "2.0.0.0"}]}
    monkeypatch.setattr(api, "http_json", lambda address: response if address == url else {})
    with sessions.begin() as db:
        db.add(api.MetaRecord(id=1, payload={"versions": {"chrome": "1.0.0.0"}, "dataVersion": "2026.11.25.1"}))
        db.add(api.Source(id="chrome-version-history", name="Chrome", category="browser", url=url, trusted=False, enabled=True))

    assert api.monitor_sources_job()["autoPublished"] == 0
    with sessions.begin() as db:
        assert db.get(api.MetaRecord, 1).payload == {"versions": {"chrome": "1.0.0.0"}, "dataVersion": "2026.11.25.1"}
        assert db.scalar(select(api.AuditLog.id)) is None
        source = db.get(api.Source, "chrome-version-history")
        assert source.details["latestVersion"] == "2.0.0.0"
        source.trusted = True

    assert api.monitor_sources_job()["autoPublished"] == 1
    assert api.monitor_sources_job()["autoPublished"] == 0
    with sessions() as db:
        meta = db.get(api.MetaRecord, 1).payload
        assert meta["versions"]["chrome"] == "2.0.0.0"
        assert meta["versionEvidence"]["chrome"]["quote"] == "2.0.0.0"
        assert db.scalars(select(api.AuditLog.action)).all() == ["trusted_auto_publish"]
