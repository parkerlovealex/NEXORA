"""Wave 5 regression test for A44: the specs evidence validator must
enforce the same HTTPS requirement as the official merger. Before the
fix, host_allowed() checked only the hostname, so an http:// evidence
URL on an approved host passed standalone validation.
"""

from tools.validate_specs import host_allowed, validate


def catalog_and_evidence(url):
    catalog = {"android": [{"id": "pixel-test", "display": {}}]}
    evidence = {
        "schemaVersion": 1,
        "devices": {
            "pixel-test": {
                "ppi": {
                    "observations": [{
                        "source": "google_pixel_support",
                        "source_class": "primary",
                        "source_url": url,
                        "quoted_text": "Pixel Test display 420 ppi",
                        "fetched_at": "2026-11-25T00:00:00+00:00",
                        "eligible": False,
                    }],
                },
            },
        },
    }
    return catalog, evidence


def url_errors(url):
    catalog, evidence = catalog_and_evidence(url)
    errors, _warnings = validate(catalog, evidence)
    return [e for e in errors if "source URL/domain" in e]


def test_host_allowed_requires_https():
    assert host_allowed("google_pixel_support", "https://support.google.com/pixel/answer/1", "primary") is True
    assert host_allowed("google_pixel_support", "http://support.google.com/pixel/answer/1", "primary") is False
    assert host_allowed("google_pixel_support", "ftp://support.google.com/file", "primary") is False
    assert host_allowed("google_pixel_support", "//support.google.com/no-scheme", "primary") is False
    assert host_allowed("google_pixel_support", "https://evil.example.com/phish", "primary") is False


def test_validate_rejects_http_evidence_url():
    assert url_errors("http://support.google.com/pixel/answer/1"), "http evidence URL passed validation"
    assert url_errors("https://support.google.com/pixel/answer/1") == [], "https evidence URL rejected"
