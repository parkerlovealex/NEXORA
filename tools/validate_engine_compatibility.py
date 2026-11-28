#!/usr/bin/env python3
"""Validate Phase 4.3a engine-compatibility metadata across the catalog.

Checks (all against the published web/ artifacts):
- desktop-catalog.json: every browser version entry carries a well-formed
  engineCompatibility object; chromium-family majors come from the entry's own
  engineVersion; firefox is gecko and safari is webkit (browser-is-engine).
- devices.json: meta.browserVersionCompatibility covers exactly the versions in
  meta.browserVersions — no missing entries, no extras; known entries are
  well-formed; unknown entries stay explicitly unknown with a reason.
- ua_templates.json: any record-level engine field is evidence-shaped
  (detailed reference validation lives in validate_ua_templates.py).

Unknown is a valid, preserved state. This validator never requires metadata
to exist where the catalog honestly has none — it requires that whatever is
claimed is well-formed and consistent.

Usage: python tools/validate_engine_compatibility.py [web-dir]
Exit code 0 when valid, 1 otherwise.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

FAMILIES = {"chromium", "gecko", "webkit"}
BASES = {
    "ua-major-equals-engine-major",
    "catalog-engine-mapping",
    "browser-is-engine",
    "platform-engine-webkit",
    "evidence",
}
DESKTOP_FAMILY = {
    "chrome": "chromium",
    "edge": "chromium",
    "opera": "chromium",
    "firefox": "gecko",
    "safari": "webkit",
}


def major(version: object) -> int | None:
    try:
        return int(str(version).split(".")[0])
    except (TypeError, ValueError):
        return None


def check_compat_object(value: object, where: str, errors: list[str]) -> None:
    """A known-form compatibility object: {family, majors|null, basis}."""
    if not isinstance(value, dict):
        errors.append(f"{where}: compatibility must be an object")
        return
    if value.get("status") == "unknown":
        if not str(value.get("reason") or "").strip():
            errors.append(f"{where}: unknown compatibility requires a reason")
        return
    family = value.get("family")
    if family not in FAMILIES:
        errors.append(f"{where}: family must be one of {sorted(FAMILIES)}, got {family!r}")
    majors = value.get("majors")
    if majors is not None:
        if (
            not isinstance(majors, list)
            or not majors
            or any(not isinstance(m, int) or m < 1 for m in majors)
        ):
            errors.append(f"{where}: majors must be null or a non-empty list of positive integers")
    basis = value.get("basis")
    if basis not in BASES:
        errors.append(f"{where}: basis must be one of {sorted(BASES)}, got {basis!r}")


def validate(web_dir: Path) -> list[str]:
    errors: list[str] = []

    desktop = json.loads((web_dir / "desktop-catalog.json").read_text())
    for os_name, platform in (desktop.get("platforms") or {}).items():
        for browser, block in (platform.get("browsers") or {}).items():
            expected_family = DESKTOP_FAMILY.get(browser)
            for entry in block.get("versions") or []:
                where = f"desktop {os_name}/{browser} {entry.get('uaVersion')}"
                ec = entry.get("engineCompatibility")
                if ec is None:
                    errors.append(f"{where}: missing engineCompatibility")
                    continue
                check_compat_object(ec, where, errors)
                if not isinstance(ec, dict) or ec.get("status") == "unknown":
                    continue
                if expected_family and ec.get("family") != expected_family:
                    errors.append(
                        f"{where}: family must be {expected_family} on desktop, got {ec.get('family')!r}"
                    )
                engine_major = major(entry.get("engineVersion"))
                if ec.get("family") == "chromium" and ec.get("majors") != [engine_major]:
                    errors.append(
                        f"{where}: chromium majors must equal the entry's engineVersion major "
                        f"[{engine_major}], got {ec.get('majors')}"
                    )
                if ec.get("basis") == "browser-is-engine" and ec.get("majors") != [
                    major(entry.get("uaVersion"))
                ]:
                    errors.append(
                        f"{where}: browser-is-engine majors must equal the uaVersion major"
                    )

    devices = json.loads((web_dir / "devices.json").read_text())
    meta = devices.get("meta") or {}
    lists = meta.get("browserVersions") or {}
    sidecar = meta.get("browserVersionCompatibility")
    if not isinstance(sidecar, dict):
        errors.append("devices meta.browserVersionCompatibility is missing")
        sidecar = {}
    if set(sidecar.keys()) != set(lists.keys()):
        errors.append(
            "sidecar lists must match browserVersions lists exactly: "
            f"sidecar={sorted(sidecar.keys())} lists={sorted(lists.keys())}"
        )
    for key, versions in lists.items():
        mapping = sidecar.get(key) or {}
        if set(mapping.keys()) != set(versions):
            missing = sorted(set(versions) - set(mapping.keys()))
            extra = sorted(set(mapping.keys()) - set(versions))
            errors.append(
                f"sidecar {key}: must cover exactly the listed versions; missing={missing} extra={extra}"
            )
        for version, compat in mapping.items():
            check_compat_object(compat, f"sidecar {key} {version}", errors)

    templates = json.loads((web_dir / "ua_templates.json").read_text())
    for record in templates.get("records") or []:
        engine = record.get("engine")
        if engine is None:
            continue
        where = f"template {record.get('id')}"
        if not isinstance(engine, dict):
            errors.append(f"{where}: engine must be an object")
            continue
        if engine.get("family") not in FAMILIES:
            errors.append(f"{where}: engine.family invalid: {engine.get('family')!r}")
        if not isinstance(engine.get("major"), int) or engine.get("major", 0) < 1:
            errors.append(f"{where}: engine.major must be a positive integer")
        if engine.get("basis") != "evidence":
            errors.append(f"{where}: engine.basis must be 'evidence'")
        refs = engine.get("evidenceRefs")
        if not isinstance(refs, list) or not refs:
            errors.append(f"{where}: engine.evidenceRefs must be a non-empty array")

    return errors


def main() -> int:
    web_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "web"
    errors = validate(web_dir)
    if errors:
        print("Engine compatibility validation FAILED:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("Engine compatibility validation OK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
