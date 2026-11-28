"""Wave 5 regression test for A30: re-running the UA template
collector with identical inputs must not mint a new dataVersion or
stack duplicate review notes. Before the fix, every run bumped the
version and each repeated lower-status input appended another copy of
the downgrade note.
"""

import sys
import unittest
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import collect_ua_templates as collector

from test_ua_templates_pipeline import devices_fixture


def verified_existing():
    return {
        "dataVersion": "2026.11.25.1",
        "updatedAt": "2026-11-25",
        "deviceCatalogVersion": "2026.11.25.6",
        "coverage": [
            {"app": "facebook", "platforms": {"android": "verified", "ios": "not-collected"}},
            {"app": "instagram", "platforms": {"android": "not-collected", "ios": "not-collected"}},
        ],
        "sources": [],
        "records": [{
            "id": "fb-android", "app": "facebook", "platform": "android", "status": "verified",
            "schemaVersion": 1, "template": "App/{appVersion}",
            "slots": {"appVersion": "meta.apps.facebook.version"}, "evidence": [],
        }],
    }


def downgrade_findings():
    return {"records": [{
        "id": "fb-android", "app": "facebook", "platform": "android", "status": "candidate",
        "source_format": "different candidate", "evidence": [],
    }]}


class TemplateRevisionStabilityTests(unittest.TestCase):
    def test_identical_rerun_keeps_version_and_date(self):
        existing = verified_existing()
        first, _, _ = collector.build_proposal(devices_fixture(), {"records": []}, existing=existing, today=date(2026, 11, 25))
        self.assertEqual(first["dataVersion"], "2026.11.25.1")
        self.assertEqual(first["updatedAt"], "2026-11-25")
        second, _, _ = collector.build_proposal(devices_fixture(), {"records": []}, existing=first, today=date(2026, 11, 26))
        self.assertEqual(second["dataVersion"], "2026.11.25.1")
        self.assertEqual(second["updatedAt"], "2026-11-25")

    def test_real_change_still_bumps(self):
        existing = verified_existing()
        findings = {"records": [{
            "id": "ig-android", "app": "instagram", "platform": "android", "status": "candidate",
            "source_format": "IG/{version}", "evidence": [],
        }]}
        proposal, _, _ = collector.build_proposal(devices_fixture(), findings, existing=existing, today=date(2026, 11, 25))
        self.assertEqual(proposal["dataVersion"], "2026.11.25.2")

    def test_repeated_downgrade_adds_the_note_once(self):
        existing = verified_existing()
        first, _, _ = collector.build_proposal(devices_fixture(), downgrade_findings(), existing=existing, today=date(2026, 11, 25))
        notes_first = [n for n in first["records"][0].get("review_notes", []) if "downgrade" in n]
        self.assertEqual(len(notes_first), 1)
        second, _, _ = collector.build_proposal(devices_fixture(), downgrade_findings(), existing=first, today=date(2026, 11, 25))
        notes_second = [n for n in second["records"][0].get("review_notes", []) if "downgrade" in n]
        self.assertEqual(len(notes_second), 1)
        # The caller's existing catalog must not be mutated by a merge.
        self.assertNotIn("review_notes", existing["records"][0])


if __name__ == "__main__":
    unittest.main()
