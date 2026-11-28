import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import collect_specs
import validate_specs


class SpecsIdentityTests(unittest.TestCase):
    def setUp(self):
        self.row = {
            "id": "pixel-9-pro", "brand": "Google", "model": "Pixel 9 Pro",
            "uaModel": "Pixel 9 Pro",
            "display": {"w": 1280, "h": 2856, "ppi": None},
        }

    def test_phonedb_search_url_cannot_prove_identity(self):
        obs = collect_specs.pdb_obs_from(
            self.row,
            "https://phonedb.net/index.php?m=search&q=Pixel+9+Pro",
            {"model": "Pixel 9", "codename_field": "caiman", "ppi": 495,
             "res": (1080, 2424)},
        )
        self.assertFalse(obs["codename_obs"]["eligible"])
        self.assertFalse(obs["ppi_obs"]["eligible"])

    def test_phonedb_page_model_must_match_exact_device(self):
        obs = collect_specs.pdb_obs_from(
            self.row, "https://phonedb.net/model-page",
            {"model": "Pixel 9 Pro", "codename_field": "caiman",
             "ppi": 495, "res": (1280, 2856)},
        )
        self.assertTrue(obs["codename_obs"]["eligible"])
        self.assertTrue(obs["ppi_obs"]["eligible"])
        self.assertEqual(obs["codename_obs"]["identity"]["method"], "ua_model_in_page_model")

    def test_gsmarena_quote_cannot_bypass_model_and_resolution(self):
        obs = collect_specs.gsm_obs_from(
            self.row, "https://www.gsmarena.com/wrong-device.php",
            {"title": "Pixel 9", "ppi": 495, "res": (1080, 2424)},
            "Pixel 9 Pro 1280 x 2856, 495 ppi",
        )
        self.assertFalse(obs["eligible"])

    def test_secondary_only_is_candidate_not_publishable_value(self):
        result = collect_specs.merge_field([
            {"source": "devicespecifications", "source_class": "secondary",
             "value": 416, "eligible": True}
        ])
        self.assertEqual(result["status"], "single_source_candidate")
        self.assertIsNone(result["value"])
        self.assertEqual(result["candidate_value"], 416)

    def test_two_secondary_sources_are_still_not_primary(self):
        result = collect_specs.merge_field([
            {"source": "devicespecifications", "source_class": "secondary",
             "value": 416, "eligible": True},
            {"source": "gsmarena", "source_class": "secondary",
             "value": 416, "eligible": True},
        ])
        self.assertEqual(result["status"], "corroborated_secondary_candidate")
        self.assertIsNone(result["value"])

    def test_primary_source_overrides_secondary_candidate(self):
        result = collect_specs.merge_field([
            {"source": "devicespecifications", "source_class": "secondary",
             "value": 424, "eligible": True},
            {"source": "google_pixel_support", "source_class": "primary",
             "value": 428, "eligible": True},
        ])
        self.assertEqual(result["status"], "primary")
        self.assertEqual(result["value"], 428)
        self.assertEqual(result["secondary_dissent"], {"424": ["devicespecifications"]})

    def test_findings_mode_does_not_silently_go_live(self):
        class NeverFetch:
            def __getattr__(self, name):
                raise AssertionError("live fetch should not run")
        self.assertIsNone(collect_specs.pdb_observe(self.row, NeverFetch(), None, live=False))
        self.assertIsNone(collect_specs.gsm_observe(self.row, NeverFetch(), None, live=False))


class SpecsEvidenceValidationTests(unittest.TestCase):
    def setUp(self):
        self.catalog = {
            "android": [{
                "id": "pixel-9-pro", "brand": "Google", "model": "Pixel 9 Pro",
                "uaModel": "Pixel 9 Pro", "codename": "caiman",
                "display": {"w": 1280, "h": 2856, "ppi": 495},
            }]
        }
        self.evidence = {
            "schemaVersion": 1,
            "devices": {
                "pixel-9-pro": {
                    "ppi": {
                        "observations": [{
                            "source": "google_pixel_support", "source_class": "primary",
                            "source_url": "https://support.google.com/pixelphone/answer/7158570?hl=en",
                            "quoted_text": "1280 x 2856 LTPO OLED at 495 PPI",
                            "fetched_at": "2026-11-24T23:25:00Z",
                            "value": 495, "eligible": True,
                            "identity": {"model_text": "Google Pixel 9 Pro",
                                         "resolution": [1280, 2856],
                                         "method": "official_product_specs_model_match"},
                        }],
                        "verdict": {"status": "primary", "value": 495, "sources": ["google_pixel_support"]},
                    },
                    "codename": {
                        "observations": [{
                            "source": "google_aosp_pixel_docs", "source_class": "primary",
                            "source_url": "https://source.android.com/docs/setup/build/building-pixel-kernels",
                            "quoted_text": "Pixel 9 Pro (caiman)",
                            "fetched_at": "2026-11-24T23:25:00Z",
                            "value": "caiman", "eligible": True,
                            "identity": {"model_text": "Google Pixel 9 Pro",
                                         "resolution": None,
                                         "method": "official_aosp_device_mapping"},
                        }],
                        "verdict": {"status": "primary", "value": "caiman", "sources": ["google_aosp_pixel_docs"]},
                    },
                }
            },
        }

    def test_official_primary_evidence_passes(self):
        errors, warnings = validate_specs.validate(self.catalog, self.evidence)
        self.assertEqual(errors, [])
        self.assertEqual(warnings, [])

    def test_catalog_mismatch_is_error(self):
        self.catalog["android"][0]["display"]["ppi"] = 421
        errors, _ = validate_specs.validate(self.catalog, self.evidence)
        self.assertTrue(any("does not match primary evidence" in e for e in errors))

    def test_secondary_only_value_is_warning_not_accepted(self):
        self.catalog["android"][0]["display"]["ppi"] = None
        self.evidence["devices"]["pixel-9-pro"]["ppi"]["observations"][0]["source_class"] = "secondary"
        self.evidence["devices"]["pixel-9-pro"]["ppi"]["observations"][0]["source"] = "devicespecifications"
        self.evidence["devices"]["pixel-9-pro"]["ppi"]["observations"][0]["source_url"] = "https://www.devicespecifications.com/en/model/example"
        self.evidence["devices"]["pixel-9-pro"]["ppi"]["verdict"] = {"status": "secondary_only_candidate", "value": None}
        errors, warnings = validate_specs.validate(self.catalog, self.evidence)
        self.assertEqual(errors, [])
        self.assertTrue(any("secondary-only candidates" in w for w in warnings))

    def test_primary_identity_mismatch_is_error(self):
        self.evidence["devices"]["pixel-9-pro"]["ppi"]["observations"][0]["identity"]["model_text"] = "Google Pixel 9"
        errors, _ = validate_specs.validate(self.catalog, self.evidence)
        self.assertTrue(any("identity model text" in e for e in errors))


class ShippedProposalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # C14: the proposal and evidence files are generated artifacts,
        # absent from a clean checkout. Generate them with the same tool
        # the specs-refresh workflow uses, from the committed findings
        # and evidence, so the suite is self-contained.
        out_dir = ROOT / "output"
        proposal = out_dir / "devices.proposed.json"
        evidence = out_dir / "specs-evidence.json"
        if proposal.exists() and evidence.exists():
            return
        out_dir.mkdir(exist_ok=True)
        subprocess.run(
            [sys.executable, str(ROOT / "tools" / "apply_official_findings.py"),
             "--input", str(ROOT / "web" / "devices.json"),
             "--findings", str(ROOT / "data" / "official-specs-findings.json"),
             "--existing-evidence", str(ROOT / "data" / "evidence" / "specs" / "latest.json"),
             "--out", str(proposal), "--evidence", str(evidence),
             "--report", str(out_dir / "REPORT-specs.md")],
            check=True, capture_output=True, text=True)

    def test_official_proposal_coverage_and_honor_resolution(self):
        catalog = json.loads((ROOT / "output/devices.proposed.json").read_text())
        evidence = json.loads((ROOT / "output/specs-evidence.json").read_text())
        rows = {r["id"]: r for r in catalog["android"]}
        official_ppi = [
            did for did, row in rows.items()
            if row.get("display", {}).get("ppi") is not None
            and evidence["devices"].get(did, {}).get("ppi", {}).get("verdict", {}).get("status") == "primary"
        ]
        official_codes = [
            did for did, row in rows.items()
            if row.get("codename")
            and evidence["devices"].get(did, {}).get("codename", {}).get("verdict", {}).get("status") == "primary"
        ]
        self.assertEqual(len(official_ppi), 10)
        self.assertEqual(len(official_codes), 8)
        honor = rows["honor-x9b"]
        self.assertEqual((honor["display"]["w"], honor["display"]["h"], honor["display"]["ppi"]), (1200, 2652, 429))


if __name__ == "__main__":
    unittest.main()
