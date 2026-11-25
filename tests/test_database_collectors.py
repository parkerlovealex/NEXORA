import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import collect_ipsw_builds as collector
import validate_devices as validator


def base_catalog():
    return {
        "meta": {
            "schemaVersion": 2,
            "dataVersion": "2026.11.23.2",
            "updatedAt": "2026-11-23",
            "versions": {"chrome": "159.0.8154.18"},
        },
        "android": [{
            "id": "pixel-test",
            "brand": "Google",
            "model": "Pixel Test",
            "uaModel": "PixelTest",
            "gpu": "Test GPU",
            "soc": "Test SoC",
            "cores": 8,
            "ramGb": [8],
            "storageGb": [128],
            "display": {"w": 1080, "h": 2400, "dpr": 3.0},
            "osVersions": ["15"],
            "builds": {},
            "markets": ["US"],
        }],
        "ios_emulation": [{
            "id": "iphone-test",
            "brand": "Apple",
            "model": "iPhone Test",
            "uaModel": "iPhone99,1",
            "gpu": "Apple GPU",
            "soc": "Apple A99",
            "cores": 6,
            "ramGb": [8],
            "storageGb": [128],
            "display": {"w": 1179, "h": 2556, "dpr": 3.0},
            "osVersions": ["18.0"],
            "builds": {"18.0": "22A100"},
            "markets": ["US"],
        }],
    }


def fake_payload(identifier, firmwares):
    return {"identifier": identifier, "firmwares": firmwares}


class CollectorTests(unittest.TestCase):
    def test_collect_creates_per_pair_evidence_and_bumps_metadata(self):
        data = base_catalog()
        payload = fake_payload("iPhone99,1", [
            {
                "identifier": "iPhone99,1", "version": "18.0",
                "buildid": "22A100", "signed": True, "releasedate": "2024-09-16",
            },
            {
                "identifier": "iPhone99,1", "version": "18.1",
                "buildid": "22B200", "signed": False, "releasedate": "2024-10-28",
            },
        ])
        proposal, evidence, report, code = collector.collect(
            data, fetcher=lambda ident: payload,
            fetched_at="2026-11-24T12:00:00+00:00",
        )
        self.assertEqual(code, 0)
        row = proposal["ios_emulation"][0]
        self.assertEqual(row["osVersions"], ["18.0", "18.1"])
        self.assertEqual(row["builds"], {"18.0": "22A100", "18.1": "22B200"})
        self.assertEqual(proposal["meta"]["updatedAt"], "2026-11-24")
        self.assertEqual(proposal["meta"]["dataVersion"], "2026.11.24.1")
        ev = evidence["devices"]["iphone-test"]["firmwares"]
        self.assertEqual(len(ev), 2)
        self.assertIn('"version":"18.0"', ev[0]["observations"][0]["quoted_text"])
        self.assertEqual(report["status"], "proposal_ready")

        errors, warnings, count = validator.validate_data(
            proposal, evidence=evidence, evidence_supplied=True
        )
        self.assertEqual(errors, [])
        self.assertEqual(count, 2)
        self.assertEqual(len(warnings), 1)  # Android firmware collector is a later step.

    def test_conflicting_builds_are_not_silently_selected(self):
        data = base_catalog()
        payload = fake_payload("iPhone99,1", [
            {"identifier": "iPhone99,1", "version": "18.2", "buildid": "22C100", "signed": False},
            {"identifier": "iPhone99,1", "version": "18.2", "buildid": "22C101", "signed": True},
        ])
        proposal, evidence, report, code = collector.collect(
            data, fetcher=lambda ident: payload,
            fetched_at="2026-11-24T12:00:00+00:00",
        )
        self.assertEqual(code, 3)
        self.assertIn("18.2", report["text"])
        self.assertIn("no source winner selected", report["text"])
        self.assertNotIn("18.2", proposal["ios_emulation"][0]["builds"])
        self.assertEqual(len(evidence["devices"]["iphone-test"]["firmwares"]), 2)
        errors, _, _ = validator.validate_data(
            proposal, evidence=evidence, evidence_supplied=True
        )
        self.assertTrue(any("missing for OS versions: 18.2" in e for e in errors))
        self.assertTrue(any("conflicting evidence for 18.2" in e for e in errors))

    def test_identifier_mismatch_is_blocked(self):
        data = base_catalog()
        payload = fake_payload("iPhoneWrong,1", [
            {"version": "18.0", "buildid": "22A100"},
        ])
        _, _, report, code = collector.collect(
            data, fetcher=lambda ident: payload,
            fetched_at="2026-11-24T12:00:00+00:00",
        )
        self.assertEqual(code, 2)
        self.assertEqual(report["stats"]["blocked"], 1)
        self.assertEqual(report["status"], "failed_zero_data")

    def test_partial_failure_is_nonzero_exit(self):
        data = base_catalog()
        data["ios_emulation"].append({
            **deepcopy(data["ios_emulation"][0]),
            "id": "iphone-test-2",
            "uaModel": "iPhone99,2",
        })
        def fetcher(ident):
            if ident == "iPhone99,2":
                raise RuntimeError("simulated source timeout")
            return fake_payload("iPhone99,1", [
                {"identifier": "iPhone99,1", "version": "18.0", "buildid": "22A100"}
            ])
        _, _, report, code = collector.collect(
            data, fetcher=fetcher, fetched_at="2026-11-24T12:00:00+00:00"
        )
        self.assertEqual(code, 3)
        self.assertEqual(report["stats"]["blocked"], 1)
        self.assertEqual(report["stats"]["pairs"], 1)


    def test_same_day_data_version_sequence_increments(self):
        self.assertEqual(
            collector.bump_data_version("2026.11.24.3", collector.date(2026, 11, 24)),
            "2026.11.24.4",
        )

    def test_empty_device_firmware_list_is_reported_as_partial_failure(self):
        data = base_catalog()
        payload = fake_payload("iPhone99,1", [])
        _, _, report, code = collector.collect(
            data, fetcher=lambda ident: payload,
            fetched_at="2026-11-24T12:00:00+00:00",
        )
        self.assertEqual(code, 2)
        self.assertEqual(report["stats"]["zero_firmware_devices"], 1)
        self.assertIn("ZERO usable firmware pairs", report["text"])

    def test_invalid_version_is_rejected(self):
        data = base_catalog()
        payload = fake_payload("iPhone99,1", [
            {"identifier": "iPhone99,1", "version": "18.0 beta 2", "buildid": "22A100"},
        ])
        _, _, report, code = collector.collect(
            data, fetcher=lambda ident: payload,
            fetched_at="2026-11-24T12:00:00+00:00",
        )
        self.assertEqual(code, 2)
        self.assertEqual(report["stats"]["invalid_records"], 1)
        self.assertIn("invalid version", report["text"])


class ValidatorTests(unittest.TestCase):
    def test_ios_build_coverage_is_required(self):
        data = base_catalog()
        data["ios_emulation"][0]["builds"] = {}
        errors, warnings, _ = validator.validate_data(data)
        self.assertTrue(any("iOS build IDs missing" in e for e in errors))
        self.assertEqual(len(warnings), 1)


    def test_evidence_quote_must_prove_exact_pair(self):
        data = base_catalog()
        payload = fake_payload("iPhone99,1", [
            {"identifier": "iPhone99,1", "version": "18.0", "buildid": "22A100"}
        ])
        proposal, evidence, _, _ = collector.collect(
            data, fetcher=lambda ident: payload,
            fetched_at="2026-11-24T12:00:00+00:00",
        )
        evidence["devices"]["iphone-test"]["firmwares"][0]["observations"][0]["quoted_text"] = (
            '{"identifier":"iPhone99,1","version":"18.0","buildid":"WRONG"}'
        )
        errors, _, _ = validator.validate_data(
            proposal, evidence=evidence, evidence_supplied=True
        )
        self.assertTrue(any("quote does not prove" in error for error in errors))

    def test_legacy_aggregate_evidence_is_rejected(self):
        data = base_catalog()
        errors, _, _ = validator.validate_data(
            data, evidence={"source": "old", "devices": {}}, evidence_supplied=True
        )
        self.assertTrue(any("legacy aggregate evidence is not accepted" in e for e in errors))

    def test_malformed_os_version_type_does_not_crash(self):
        data = base_catalog()
        data["android"][0]["osVersions"] = [{"bad": "shape"}]
        errors, _, _ = validator.validate_data(data)
        self.assertTrue(any("invalid OS version" in e for e in errors))

    def test_boolean_core_count_is_rejected(self):
        data = base_catalog()
        data["android"][0]["cores"] = True
        errors, _, _ = validator.validate_data(data)
        self.assertTrue(any("cores must be a positive integer" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
