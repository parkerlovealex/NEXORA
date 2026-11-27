import copy
import hashlib
import io
import json
import sys
from unittest.mock import Mock

import pytest

from tools import refresh_devices as refresh


URL = "https://www.samsung.com/test-capture"
ROW = {
    "id": "fixture-handset", "brand": "Fixture", "model": "Fixture Handset", "uaModel": "NX01",
    "soc": "Test Chip", "gpu": "Test GPU", "cores": 4, "display": {"w": 720, "h": 1280, "dpr": 2},
    "ramGb": [4], "storageGb": [32], "osVersions": ["12"], "builds": {"12": "FIX.1"},
}
QUOTES = {
    "id": "Model: Fixture Handset", "model": "Model: Fixture Handset", "brand": "Brand: Fixture",
    "uaModel": "Model code: NX01", "soc": "SoC: Test Chip", "gpu": "GPU: Test GPU",
    "cores": "CPU cores: 4", "display.w": "Resolution: 720×1280", "display.h": "Resolution: 720×1280",
    "display.dpr": "Pixel ratio: 2", "ramGb": "RAM: 4 GB", "storageGb": "Storage: 32 GB",
    "osVersions": "Ships with Android 12", "builds.12": "Android 12 firmware FIX.1",
}
TEXT = "\n".join(QUOTES.values())


def local_provider(monkeypatch, row, evidence):
    response = {"choices": [{"message": {"content": json.dumps({"rows": [{"record": row, "evidence": evidence}]})}}]}
    transport = Mock(return_value=io.BytesIO(json.dumps(response).encode()))
    monkeypatch.setenv("CEREBRAS_API_KEY", "offline-test-only")
    monkeypatch.setattr(refresh.urllib.request, "urlopen", transport)
    return transport


def test_normal_refresh_keeps_quotes_with_the_proposed_row(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    source = tmp_path / "devices.json"
    source.write_text(json.dumps({"meta": {"dataVersion": "2026.11.25.1", "versions": {"chrome": "1.0.0.0"}},
                                  "android": [], "ios_emulation": []}))
    (tmp_path / "web").mkdir()
    capture = tmp_path / "capture.json"
    capture.write_text(json.dumps({"source_url": URL, "text": TEXT}))
    monkeypatch.setattr(refresh, "DEVICES", str(source))
    monkeypatch.setattr(refresh, "fetch_json", lambda _: {"versions": [{"version": "1.0.0.0"}]})
    monkeypatch.setattr(sys, "argv", ["refresh_devices.py", "--ai-input", str(capture)])
    transport = local_provider(monkeypatch, ROW, QUOTES)
    assert refresh.main() == 0
    proposed = json.loads(source.read_text())["android"][0]
    assert proposed["fieldEvidence"] == QUOTES
    assert proposed["source_url"] == URL
    assert proposed["sourceCaptureSha256"] == hashlib.sha256(TEXT.encode()).hexdigest()
    assert proposed["builds"] == {"12": "FIX.1"}
    transport.assert_called_once()


@pytest.mark.parametrize("problem", ["missing", "invented", "wrong_field", "wrong_dimensions", "wrong_build", "extra_fact"])
def test_missing_or_unsupporting_field_evidence_rejects_the_whole_row(problem, monkeypatch):
    row, quotes = copy.deepcopy(ROW), dict(QUOTES)
    if problem == "missing":
        del quotes["display.dpr"]
    elif problem == "invented":
        quotes["soc"] = "Invented note: Test Chip"
    elif problem == "wrong_field":
        quotes["cores"] = quotes["ramGb"]
    elif problem == "wrong_dimensions":
        row["display"]["w"], row["display"]["h"] = 1280, 720
    elif problem == "wrong_build":
        row["builds"]["12"] = "FIX.2"
    else:
        row["markets"] = ["US"]
    local_provider(monkeypatch, row, quotes)
    assert refresh.ai_normalize(TEXT, URL) == []


@pytest.mark.parametrize("capture", ["plain text", {"source_url": "https://example.org/specs", "text": TEXT}])
def test_unattributed_or_unapproved_input_stops_before_provider_access(capture, monkeypatch):
    transport = local_provider(monkeypatch, ROW, QUOTES)
    with pytest.raises(ValueError):
        refresh.ai_normalize(capture if isinstance(capture, str) else json.dumps(capture))
    transport.assert_not_called()
