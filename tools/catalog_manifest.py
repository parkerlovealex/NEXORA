#!/usr/bin/env python3
"""NEXORA catalog release manifest: generator and validator (Phase 4.1).

The catalog is three artifacts published from web/:

    devices.json          meta.dataVersion / meta.schemaVersion
    ua_templates.json     dataVersion / uaTemplatesSchema
    desktop-catalog.json  dataVersion / schemaVersion

Every artifact also carries the shared `catalogReleaseId` of the release it
belongs to. This tool builds web/catalog-manifest.json from the artifacts,
or validates the committed manifest against them.

The manifest is deterministic: SHA-256 hashes and byte sizes are pure
functions of the artifact contents, keys are sorted, and no wall-clock
timestamps are included, so the same input always produces the same
manifest bytes. Signing (Phase 4.4) will add the signature block; until
then the manifest is a release contract, not a trust anchor.

Usage:
    python3 tools/catalog_manifest.py generate   # write web/catalog-manifest.json
    python3 tools/catalog_manifest.py check      # validate only (default)

Exit code 0 means every check passed.
"""

import hashlib
import json
import re
import sys
from pathlib import Path

VERSION_RE = re.compile(r"^\d{4}\.\d{2}\.\d{2}\.\d+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MANIFEST_NAME = "catalog-manifest.json"

# artifact key -> (file name, dataVersion getter, schemaVersion getter, release-id getter)
def _devices(doc):
    return doc["meta"]["dataVersion"], doc["meta"]["schemaVersion"], doc["meta"]["catalogReleaseId"]


def _ua_templates(doc):
    return doc["dataVersion"], doc["uaTemplatesSchema"], doc["catalogReleaseId"]


def _desktop(doc):
    return doc["dataVersion"], doc["schemaVersion"], doc["catalogReleaseId"]


ARTIFACTS = {
    "devices": ("devices.json", _devices),
    "uaTemplates": ("ua_templates.json", _ua_templates),
    "desktop": ("desktop-catalog.json", _desktop),
}


def load_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def build_manifest(web_dir):
    """Build the manifest dict from the artifacts in web_dir. Raises on bad input."""
    artifacts = {}
    release_ids = set()
    for key, (fname, extract) in ARTIFACTS.items():
        raw = (web_dir / fname).read_bytes()
        doc = json.loads(raw.decode("utf-8"))
        data_version, schema_version, release_id = extract(doc)
        if not VERSION_RE.match(str(data_version)):
            raise ValueError(f"{fname}: dataVersion {data_version!r} fails the version pattern")
        release_ids.add(release_id)
        artifacts[key] = {
            "dataVersion": data_version,
            "schemaVersion": schema_version,
            "file": fname,
            "sha256": hashlib.sha256(raw).hexdigest(),
            "sizeBytes": len(raw),
        }
    if len(release_ids) != 1:
        raise ValueError(f"catalogReleaseId differs across artifacts: {sorted(release_ids)}")
    release_id = release_ids.pop()
    if not VERSION_RE.match(str(release_id)):
        raise ValueError(f"catalogReleaseId {release_id!r} fails the version pattern")

    devices = load_json(web_dir / "devices.json")
    templates = load_json(web_dir / "ua_templates.json")
    if templates["deviceCatalogVersion"] != devices["meta"]["dataVersion"]:
        raise ValueError(
            "cross-reference broken: ua_templates.deviceCatalogVersion "
            f"{templates['deviceCatalogVersion']} != devices dataVersion "
            f"{devices['meta']['dataVersion']}"
        )

    return {
        "schemaVersion": 1,
        "catalogReleaseId": release_id,
        "releaseSequence": 1,
        "artifacts": artifacts,
        "signing": {
            "status": "unsigned",
            "algorithm": None,
            "keyId": None,
            "signature": None,
            "note": (
                "Unsigned release contract. Phase 4.4 signs this manifest with the "
                "dedicated NEXORA catalog key; do not treat it as a trust anchor "
                "until signing.status is 'signed' and the signature verifies."
            ),
        },
    }


def serialize(manifest):
    return json.dumps(manifest, indent=2, sort_keys=True) + "\n"


def validate_manifest(manifest, web_dir):
    """Return a list of problems; empty means valid against schema rules and files."""
    problems = []
    for field in ("schemaVersion", "catalogReleaseId", "releaseSequence", "artifacts", "signing"):
        if field not in manifest:
            problems.append(f"manifest missing required field: {field}")
    if problems:
        return problems
    if manifest["schemaVersion"] != 1:
        problems.append("manifest schemaVersion must be 1")
    if not isinstance(manifest["releaseSequence"], int) or manifest["releaseSequence"] < 1:
        problems.append("releaseSequence must be a positive integer")
    if not VERSION_RE.match(str(manifest["catalogReleaseId"])):
        problems.append("catalogReleaseId fails the version pattern")
    signing = manifest["signing"]
    if signing.get("status") not in ("unsigned", "signed"):
        problems.append("signing.status must be 'unsigned' or 'signed'")
    if signing.get("status") == "unsigned" and signing.get("signature") is not None:
        problems.append("unsigned manifest must not carry a signature")
    for key, artifact in manifest["artifacts"].items():
        for field in ("dataVersion", "schemaVersion", "file", "sha256", "sizeBytes"):
            if field not in artifact:
                problems.append(f"artifact {key}: missing field {field}")
        if not SHA256_RE.match(str(artifact.get("sha256", ""))):
            problems.append(f"artifact {key}: sha256 malformed")
        if not VERSION_RE.match(str(artifact.get("dataVersion", ""))):
            problems.append(f"artifact {key}: dataVersion fails the version pattern")

    try:
        rebuilt = build_manifest(web_dir)
    except Exception as exc:  # artifacts unreadable or inconsistent
        problems.append(f"artifact rebuild failed: {exc}")
        return problems
    if rebuilt["catalogReleaseId"] != manifest["catalogReleaseId"]:
        problems.append("manifest catalogReleaseId does not match the artifacts")
    for key, artifact in rebuilt["artifacts"].items():
        current = manifest["artifacts"].get(key)
        if current is None:
            problems.append(f"manifest missing artifact: {key}")
        elif current != artifact:
            problems.append(f"artifact {key}: manifest entry does not match the file on disk")
    return problems


def main(argv):
    mode = argv[1] if len(argv) > 1 else "check"
    root = Path(__file__).resolve().parent.parent
    web_dir = root / "web"
    manifest_path = web_dir / MANIFEST_NAME

    if mode == "generate":
        manifest = build_manifest(web_dir)
        manifest_path.write_text(serialize(manifest), encoding="utf-8")
        print(f"wrote {manifest_path} (release {manifest['catalogReleaseId']})")
        return 0
    if mode == "check":
        if not manifest_path.exists():
            print(f"FAIL: {manifest_path} does not exist; run: catalog_manifest.py generate")
            return 1
        manifest = load_json(manifest_path)
        problems = validate_manifest(manifest, web_dir)
        on_disk = manifest_path.read_text(encoding="utf-8")
        try:
            if serialize(build_manifest(web_dir)) != on_disk:
                problems.append("manifest bytes differ from a fresh rebuild (not reproducible)")
        except Exception as exc:
            problems.append(f"rebuild failed: {exc}")
        if problems:
            for problem in problems:
                print(f"FAIL: {problem}")
            return 1
        print(
            "OK: manifest valid; release "
            f"{manifest['catalogReleaseId']} (sequence {manifest['releaseSequence']}); "
            + ", ".join(
                f"{key} {a['dataVersion']} sha256 {a['sha256'][:12]}…"
                for key, a in sorted(manifest["artifacts"].items())
            )
        )
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
