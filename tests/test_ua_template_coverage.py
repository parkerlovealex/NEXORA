"""Wave 3 regression tests for A29 + C09: the UA-template validator must
derive every coverage cell from the records and compare, instead of
accepting status words on their own; duplicate coverage rows must fail;
deviceCatalogVersion must match the devices catalog. Before the fix,
the published catalog said facebook/android was "pending" beside its
verified record and validation still returned 0/0.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import validate_ua_templates as validator


def devices():
    return {"meta": {"dataVersion": "2026.11.25.7", "apps": {"facebook": {}, "instagram": {}}}}


def catalog(coverage, records, version="2026.11.25.7"):
    return {
        "uaTemplatesSchema": 1,
        "dataVersion": "2026.11.25.6",
        "updatedAt": "2026-11-26",
        "deviceCatalogVersion": version,
        "records": records,
        "coverage": coverage,
    }


def coverage_errors(data):
    errors, _, _ = validator.validate(data, devices())
    return [e for e in errors if "coverage" in e or "deviceCatalogVersion" in e]


def test_stale_cell_beside_a_verified_record_fails():
    data = catalog(
        coverage=[{"app": "facebook", "platforms": {"android": "pending", "ios": "not-collected"}},
                  {"app": "instagram", "platforms": {"android": "not-collected", "ios": "not-collected"}}],
        records=[{"app": "facebook", "platform": "android", "status": "verified"}],
    )
    assert any("facebook/android" in e and "derive 'verified'" in e for e in coverage_errors(data))


def test_derived_cells_pass():
    data = catalog(
        coverage=[{"app": "facebook", "platforms": {"android": "verified", "ios": "candidate"}},
                  {"app": "instagram", "platforms": {"android": "not-collected", "ios": "not-collected"}}],
        records=[{"app": "facebook", "platform": "android", "status": "verified"},
                 {"app": "facebook", "platform": "android", "status": "candidate"},
                 {"app": "facebook", "platform": "ios", "status": "candidate"}],
    )
    assert coverage_errors(data) == []


def test_duplicate_coverage_row_fails():
    row = {"app": "facebook", "platforms": {"android": "not-collected", "ios": "not-collected"}}
    data = catalog(
        coverage=[row, dict(row),
                  {"app": "instagram", "platforms": {"android": "not-collected", "ios": "not-collected"}}],
        records=[],
    )
    assert any("duplicate" in e for e in coverage_errors(data))


def test_device_catalog_version_mismatch_fails():
    data = catalog(
        coverage=[{"app": "facebook", "platforms": {"android": "not-collected", "ios": "not-collected"}},
                  {"app": "instagram", "platforms": {"android": "not-collected", "ios": "not-collected"}}],
        records=[],
        version="2026.11.24.1",
    )
    assert any("deviceCatalogVersion" in e for e in coverage_errors(data))
