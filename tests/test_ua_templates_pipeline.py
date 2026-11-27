import json
import sys
import unittest
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import collect_ua_templates as collector
import validate_ua_templates as validator
import fetch_useragent_in as source_fetcher
import normalize_ua_templates_ai as ai_normalizer


def devices_fixture():
    return {
        "meta": {
            "schemaVersion": 2,
            "dataVersion": "2026.11.25.6",
            "versions": {"chrome": "140.0.0.0"},
            "apps": {
                "facebook": {
                    "version": "1.2.0",
                    "build": "123",
                    "versions": [{"version": "1.2.0", "build": "123"}],
                },
                "instagram": {"version": "2.0", "build": "456", "versions": []},
            },
        },
        "android": [{
            "id": "pixel-test", "brand": "Google", "model": "Pixel Test",
            "uaModel": "PixelTest", "osVersions": ["15"], "builds": {"15": "BP1A"},
            "display": {"w": 1080, "h": 2400, "dpr": 3, "ppi": 420},
            "codename": "test", "soc": "Test SoC",
        }],
        "ios_emulation": [{
            "id": "iphone-test", "brand": "Apple", "model": "iPhone Test",
            "uaModel": "iPhone99,1", "osVersions": ["18.0"], "builds": {"18.0": "22A1"},
            "display": {"w": 1179, "h": 2556, "dpr": 3, "ppi": 460},
            "codename": "iphone-test", "soc": "Apple Test",
        }],
    }


def evidence(source_id, url, kind="observed-ua"):
    return {
        "source_id": source_id,
        "source_url": url,
        "observed": "Mozilla/5.0 (Linux; Android 15; PixelTest) App/1.2.0",
        "quoted_text": "Observed app UA sample for the stated app version/build.",
        "app_version": "1.2.0",
        "app_build": "123",
        "observed_at": "2026-11-25",
        "fetched_at": "2026-11-25",
        "kind": kind,
    }


class UaTemplateCollectorTests(unittest.TestCase):
    def test_candidate_collection_creates_all_app_coverage_without_promotion(self):
        devices = devices_fixture()
        findings = {"records": [{
            "id": "fb-android-script", "app": "facebook", "platform": "android",
            "status": "candidate", "source_format": "App/{fbVersion}", "template": None,
            "slots": {}, "evidence": [{
                "source_id": "uploaded-script", "source_ref": "User-Agent.zip!/fb version/Motorola.txt",
                "quoted_text": "create_user_agent(...)", "fetched_at": "2026-11-25", "observed_at": None, "kind": "candidate-script"
            }]
        }]}
        proposal, summary, _ = collector.build_proposal(devices, findings, today=date(2026, 11, 25))
        self.assertEqual(summary["records"], 1)
        self.assertEqual(summary["apps"], 2)
        self.assertEqual(proposal["coverage"][0]["platforms"]["android"], "candidate")
        self.assertEqual(proposal["coverage"][0]["platforms"]["ios"], "not-collected")
        self.assertEqual(proposal["records"][0]["status"], "candidate")

    def test_verified_record_is_not_downgraded_by_candidate_refresh(self):
        old = {"records": [{
            "id": "fb-android", "app": "facebook", "platform": "android", "status": "verified",
            "template": "App/{appVersion}", "slots": {"appVersion": "meta.apps.facebook.version"},
            "evidence": []
        }], "dataVersion": "2026.11.25.1"}
        findings = {"records": [{
            "id": "fb-android", "app": "facebook", "platform": "android", "status": "candidate",
            "source_format": "different candidate", "evidence": []
        }]}
        proposal, _, _ = collector.build_proposal(devices_fixture(), findings, existing=old, today=date(2026, 11, 25))
        self.assertEqual(proposal["records"][0]["status"], "verified")
        self.assertTrue(any("downgrade" in x for x in proposal["records"][0]["review_notes"]))

    def test_version_bump_increments_same_day_sequence(self):
        self.assertEqual(collector.bump_version("2026.11.25.3", date(2026, 11, 25)), "2026.11.25.4")

    def test_source_fetcher_discovers_filename_from_index_not_guessing(self):
        index = {"categories": [{"name": "In-App Browsers", "file": "ua-inapp-real.json", "count": 108}]}
        self.assertEqual(source_fetcher.discover_inapp_file(index), "ua-inapp-real.json")

    def test_source_fetcher_returns_none_when_filename_is_ambiguous(self):
        index = {"categories": [
            {"name": "In-App Browsers", "file": "one.json"},
            {"name": "In-App Browser", "file": "two.json"},
        ]}
        self.assertIsNone(source_fetcher.discover_inapp_file(index))

    def test_source_fetcher_uses_nested_category_key(self):
        index = {"categories": {"in_app_browsers": {"filename": "ua-inapp-v3.json", "count": 108}}}
        self.assertEqual(source_fetcher.discover_inapp_file(index), "ua-inapp-v3.json")

    def test_ai_normalizer_preserves_raw_source_and_never_verifies(self):
        devices = devices_fixture()
        source = {
            "source_id": "useragent-in-inapp-corpus",
            "source_url": "https://useragent.in/user-agents",
            "kind": "published-dataset-entry",
            "fetched_at": "2026-11-25",
            "records": [{
                "row_id": "fb-android-01", "app": "facebook", "platform": "android",
                "ua": "Mozilla/5.0 (Linux; Android 15; PixelTest) App/1.2.0",
                "app_version": "1.2.0", "app_build": "123"
            }]
        }
        ai_result = {"records": [{
            "row_id": "fb-android-01",
            "template": "Mozilla/5.0 (Linux; Android {osVersion}; {model}) App/{appVersion}",
            "slots": {"osVersion": "device.osVersions[]", "model": "device.uaModel", "appVersion": "meta.apps.facebook.version"},
            "source_format": None, "unresolved_slots": [], "review_notes": []
        }]}
        findings, summary = ai_normalizer.normalize_rows(source, devices, ai_result)
        self.assertEqual(findings["records"][0]["status"], "pending")
        self.assertEqual(findings["records"][0]["evidence"][0]["observed"], source["records"][0]["ua"])
        self.assertEqual(findings["records"][0]["evidence"][0]["kind"], "published-dataset-entry")
        self.assertIsNone(findings["records"][0]["evidence"][0]["observed_at"])
        self.assertEqual(findings["records"][0]["evidence"][0]["fetched_at"], "2026-11-25")
        self.assertEqual(summary["verified_promotions"], 0)

    def test_ai_normalizer_discards_unknown_source_row_id(self):
        source = {"source_url": "https://useragent.in/user-agents", "records": []}
        findings, summary = ai_normalizer.normalize_rows(source, devices_fixture(), {"records": [{"row_id": "invented"}]})
        self.assertEqual(findings["records"], [])
        self.assertTrue(any("unknown source row_id" in x for x in summary["gaps"]))


class UaTemplateValidatorTests(unittest.TestCase):
    def valid_verified_catalog(self):
        template = "Mozilla/5.0 (Linux; Android {osVersion}; {model}) App/{appVersion}"
        record = {
            "id": "facebook-android-v1", "app": "facebook", "platform": "android", "status": "verified",
            "template": template,
            "slots": {
                "osVersion": "device.osVersions[]",
                "model": "device.uaModel",
                "appVersion": "meta.apps.facebook.version",
            },
            "evidence": [
                evidence("ua-db", "https://useragent.in/user-agents", "observed-ua"),
                evidence("github-fixture", "https://github.com/example/fixture", "technical-reference"),
            ],
        }
        return {
            "uaTemplatesSchema": 1, "dataVersion": "2026.11.25.1", "updatedAt": "2026-11-25",
            "coverage": [
                {"app": "facebook", "platforms": {"android": "verified", "ios": "not-collected"}},
                {"app": "instagram", "platforms": {"android": "not-collected", "ios": "not-collected"}},
            ],
            "records": [record],
        }

    def test_verified_template_passes_two_source_gate(self):
        errors, _, counts = validator.validate(self.valid_verified_catalog(), devices_fixture())
        self.assertEqual(errors, [])
        self.assertEqual(counts, {"verified": 1})

    def test_unresolvable_slot_fails(self):
        data = self.valid_verified_catalog()
        data["records"][0]["slots"]["model"] = "device.fakeModel"
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertTrue(any("unresolvable path" in error for error in errors))

    def test_one_source_cannot_verify(self):
        data = self.valid_verified_catalog()
        data["records"][0]["evidence"] = data["records"][0]["evidence"][:1]
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertTrue(any("two independent source IDs" in error for error in errors))

    def test_only_sources_that_independently_match_count_as_corroboration(self):
        for change in [
            {"observed": None},
            {"observed": "Mozilla/5.0 (Linux; Android 15; PixelTest) Other/1.2.0"},
            {"observed": "Mozilla/5.0 (Linux; Android 15; PixelTest) App/9.9"},
            {"kind": "candidate-script"},
            {"source_id": "UA-DB"},
            {"source_url": "https://www.useragent.in:443/another-path"},
        ]:
            with self.subTest(change=change):
                data = self.valid_verified_catalog()
                data["records"][0]["evidence"][1].update(change)
                errors, _, _ = validator.validate(data, devices_fixture())
                self.assertTrue(any("two independent source IDs" in error for error in errors))

    def test_only_a_matching_source_can_establish_the_primary_observation(self):
        data = self.valid_verified_catalog()
        evidence_rows = data["records"][0]["evidence"]
        evidence_rows.append(evidence("third", "https://third.example/ua", "technical-reference"))
        evidence_rows[0]["observed"] = "unrelated"
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertTrue(any("requires observed-ua or official-doc evidence" in error for error in errors))

    def test_independence_uses_the_registrable_domain_of_matching_evidence(self):
        data = self.valid_verified_catalog()
        first, second = data["records"][0]["evidence"]
        first["source_url"] = "https://logs.first.co.uk/sample"
        second["source_url"] = "https://archive.first.co.uk/sample"
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertTrue(any("two independent source IDs" in error for error in errors))
        second["source_url"] = "https://archive.second.co.uk/sample"
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertEqual([], errors)

    def test_fetch_date_cannot_substitute_for_observation_date(self):
        data = self.valid_verified_catalog()
        for item in data["records"][0]["evidence"]:
            item["observed_at"] = None
            item["fetched_at"] = "2026-11-25"
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertTrue(any("actual observed_at date" in error for error in errors))

    def test_old_version_build_pair_cannot_verify(self):
        data = self.valid_verified_catalog()
        data["records"][0]["evidence"][0]["app_build"] = "wrong"
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertTrue(any("app version/build pair is absent" in error for error in errors))

    def test_published_dataset_entry_is_not_raw_observed_ua(self):
        data = self.valid_verified_catalog()
        for item in data["records"][0]["evidence"]:
            item["kind"] = "published-dataset-entry"
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertTrue(any("requires observed-ua or official-doc evidence" in error for error in errors))

    def test_candidate_source_format_needs_no_fake_observed_string(self):
        data = {
            "uaTemplatesSchema": 1, "dataVersion": "2026.11.25.1", "updatedAt": "2026-11-25",
            "coverage": [
                {"app": "facebook", "platforms": {"android": "candidate", "ios": "not-collected"}},
                {"app": "instagram", "platforms": {"android": "not-collected", "ios": "not-collected"}},
            ],
            "records": [{
                "id": "fb-script", "app": "facebook", "platform": "android", "status": "candidate",
                "template": None, "source_format": "FB_IAB/FB4A;FBAV/{version}", "slots": {},
                "evidence": [{
                    "source_id": "script", "source_ref": "User-Agent.zip!/fb version/Motorola.txt",
                    "quoted_text": "FB_IAB/FB4A;FBAV/{fb};", "fetched_at": "2026-11-25", "observed_at": None, "kind": "candidate-script"
                }]
            }],
        }
        errors, _, _ = validator.validate(data, devices_fixture())
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
