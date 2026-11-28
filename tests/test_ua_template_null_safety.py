"""Wave 3 regression tests for the validate_ua_templates.py crash: the
old bundled Android catalog stores null for apps that have no version
data, and resolve_slot_path called .get on that null — an AttributeError
crash instead of a validation result. A data problem must be a
validation result, never a crash.
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import validate_ua_templates as validator


def test_slot_paths_survive_a_null_app_entry():
    devices = {"meta": {"apps": {"facebook": None}, "versions": None}}
    for path in ("meta.apps.facebook.version", "meta.apps.facebook.build",
                 "meta.apps.facebook.versions[].version",
                 "meta.apps.facebook.versions[].build", "meta.versions.chrome"):
        assert validator.resolve_slot_path(path, "facebook", devices) is False


def test_validate_returns_errors_instead_of_crashing_on_null_app_entries():
    catalog = json.loads((ROOT / "web" / "ua_templates.json").read_text(encoding="utf-8"))
    devices = json.loads((ROOT / "web" / "devices.json").read_text(encoding="utf-8"))
    devices["meta"]["apps"] = {name: None for name in devices["meta"]["apps"]}
    errors, warnings, counts = validator.validate(catalog, devices)
    assert isinstance(errors, list)  # a result, not an exception
