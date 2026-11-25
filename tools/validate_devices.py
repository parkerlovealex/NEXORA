#!/usr/bin/env python3
"""Validate NEXORA devices.json schema v2 and optional collector evidence.

Usage:
  python3 validate_devices.py [catalog.json] [--evidence evidence.json] [--strict]

Warnings are permitted by default so incomplete Android build-ID collection can
be staged incrementally. iOS firmware mappings are required to be complete.
--strict promotes all warnings to errors.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

VERSION_RE = re.compile(r"^\d+(?:\.\d+){0,3}$")
BUILD_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
DATA_VERSION_RE = re.compile(r"^(\d{4})\.(\d{2})\.(\d{2})\.(\d+)$")
COUNTRY_RE = re.compile(r"^[A-Z]{2}$")


def positive_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
        and value > 0
    )


def parse_timestamp(value: Any) -> bool:
    if not isinstance(value, str) or not value:
        return False
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return True
    except ValueError:
        return False


def load_json(path: str) -> Any:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def validate_evidence(
    catalog: dict[str, Any],
    evidence: Any,
    errors: list[str],
) -> None:
    if not isinstance(evidence, dict):
        errors.append("evidence root must be an object")
        return
    if evidence.get("schemaVersion") != 1:
        errors.append(
            f"evidence.schemaVersion must be 1; legacy aggregate evidence is not accepted"
        )
        return
    devices = evidence.get("devices")
    if not isinstance(devices, dict):
        errors.append("evidence.devices must be an object")
        return

    ios_rows = catalog.get("ios_emulation")
    if not isinstance(ios_rows, list):
        return
    for row in ios_rows:
        if not isinstance(row, dict):
            continue
        device_id = str(row.get("id", "?"))
        ident = row.get("uaModel")
        device_ev = devices.get(device_id)
        if not isinstance(device_ev, dict):
            errors.append(f"{device_id}: evidence missing device record")
            continue
        if device_ev.get("device_identifier") != ident:
            errors.append(
                f"{device_id}: evidence identifier does not match uaModel {ident!r}"
            )
        source_url = device_ev.get("source_url")
        parsed = urlparse(source_url if isinstance(source_url, str) else "")
        if (
            parsed.scheme != "https"
            or parsed.netloc != "api.ipsw.me"
            or not unquote(parsed.path).startswith("/v4/device/")
            or unquote(parsed.path).split("/")[-1] != ident
        ):
            errors.append(f"{device_id}: evidence source_url is not its ipsw.me v4 device endpoint")
        if not parse_timestamp(device_ev.get("fetched_at")):
            errors.append(f"{device_id}: invalid evidence fetched_at")

        firmware_records = device_ev.get("firmwares")
        if not isinstance(firmware_records, list):
            errors.append(f"{device_id}: evidence firmwares must be a list")
            continue

        evidence_pairs: dict[tuple[str, str], int] = {}
        version_builds: dict[str, set[str]] = {}
        for index, record in enumerate(firmware_records):
            prefix = f"{device_id}: evidence firmware #{index}"
            if not isinstance(record, dict):
                errors.append(f"{prefix} must be an object")
                continue
            version = record.get("version")
            build_id = record.get("buildid")
            if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
                errors.append(f"{prefix} has invalid version {version!r}")
                continue
            if not isinstance(build_id, str) or not BUILD_RE.fullmatch(build_id):
                errors.append(f"{prefix} has invalid buildid {build_id!r}")
                continue
            pair = (version, build_id)
            evidence_pairs[pair] = evidence_pairs.get(pair, 0) + 1
            version_builds.setdefault(version, set()).add(build_id)

            record_url = record.get("source_url")
            if record_url != source_url:
                errors.append(f"{prefix} source_url does not match device source")
            if not parse_timestamp(record.get("fetched_at")):
                errors.append(f"{prefix} has invalid fetched_at")

            observations = record.get("observations")
            if not isinstance(observations, list) or not observations:
                errors.append(f"{prefix} must contain at least one source observation")
                continue
            for obs_index, observation in enumerate(observations):
                quote = observation.get("quoted_text") if isinstance(observation, dict) else None
                if not isinstance(quote, str) or not quote:
                    errors.append(f"{prefix} observation #{obs_index} has no quoted_text")
                    continue
                try:
                    excerpt = json.loads(quote)
                except json.JSONDecodeError:
                    errors.append(f"{prefix} observation #{obs_index} quote is not JSON")
                    continue
                if not isinstance(excerpt, dict):
                    errors.append(f"{prefix} observation #{obs_index} quote must be an object")
                    continue
                if excerpt.get("version") != version or excerpt.get("buildid") != build_id:
                    errors.append(
                        f"{prefix} observation #{obs_index} quote does not prove its version/build pair"
                    )
                if excerpt.get("identifier") not in (None, ident):
                    errors.append(
                        f"{prefix} observation #{obs_index} identifier does not match {ident!r}"
                    )

        # A multi-build version is acceptable ONLY when the evidence carries
        # an explicit resolution naming the chosen build (Karn's rule:
        # later release wins; the rejected build stays in the evidence).
        resolutions = device_ev.get("resolutions")
        chosen_map: dict[str, str] = {}
        if isinstance(resolutions, list):
            for res in resolutions:
                if (
                    isinstance(res, dict)
                    and isinstance(res.get("version"), str)
                    and isinstance(res.get("chosen"), str)
                ):
                    chosen_map[res["version"]] = res["chosen"]

        for version, build_ids in version_builds.items():
            if len(build_ids) > 1 and version not in chosen_map:
                errors.append(
                    f"{device_id}: conflicting evidence for {version}: {sorted(build_ids)}"
                )

        catalog_builds = row.get("builds") if isinstance(row.get("builds"), dict) else {}
        for version, build_id in catalog_builds.items():
            if not isinstance(version, str) or not isinstance(build_id, str):
                continue
            if (version, build_id) not in evidence_pairs:
                errors.append(
                    f"{device_id}: catalog pair {version} -> {build_id} has no matching evidence"
                )
        for version, build_id in evidence_pairs:
            if catalog_builds.get(version) == build_id:
                continue
            if chosen_map.get(version) == catalog_builds.get(version) and build_id in version_builds.get(version, set()):
                continue  # rejected build of a resolved version, kept as evidence
            errors.append(
                f"{device_id}: evidence pair {version} -> {build_id} is not the catalog pair"
            )


def validate_data(
    data: Any,
    evidence: Any = None,
    evidence_supplied: bool = False,
) -> tuple[list[str], list[str], int]:
    errors: list[str] = []
    warnings: list[str] = []

    if not isinstance(data, dict):
        return ["catalog root must be a JSON object"], warnings, 0

    meta = data.get("meta")
    if not isinstance(meta, dict):
        errors.append("meta block missing or not an object")
    else:
        if meta.get("schemaVersion") != 2:
            errors.append(f"meta.schemaVersion must be 2, got {meta.get('schemaVersion')!r}")
        data_version = str(meta.get("dataVersion", ""))
        match = DATA_VERSION_RE.fullmatch(data_version)
        if not match:
            errors.append(
                f"meta.dataVersion must be YYYY.MM.DD.N, got {data_version!r}"
            )
        else:
            try:
                date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
            except ValueError:
                errors.append(f"meta.dataVersion contains an invalid calendar date: {data_version!r}")
        updated_at = meta.get("updatedAt")
        if updated_at is not None:
            try:
                date.fromisoformat(str(updated_at))
            except ValueError:
                errors.append(f"meta.updatedAt must be YYYY-MM-DD, got {updated_at!r}")
        versions = meta.get("versions")
        chrome = versions.get("chrome") if isinstance(versions, dict) else None
        if not isinstance(chrome, str) or not re.fullmatch(r"\d+\.\d+\.\d+\.\d+", chrome):
            errors.append(
                f"meta.versions.chrome must be a 4-part version, got {chrome!r}"
            )

    seen_ids: set[str] = set()
    total = 0
    for section in ("android", "ios_emulation"):
        rows = data.get(section)
        if not isinstance(rows, list) or not rows:
            errors.append(f"section {section!r} missing or empty")
            continue

        for index, row in enumerate(rows):
            total += 1
            if not isinstance(row, dict):
                errors.append(f"{section}[{index}] must be an object")
                continue

            rid = row.get("id")
            if not isinstance(rid, str) or not rid.strip():
                errors.append(f"{section}[{index}]: id must be a non-empty string")
                rid = f"{section}[{index}]"
            elif rid in seen_ids:
                errors.append(f"duplicate device id: {rid}")
            else:
                seen_ids.add(rid)

            for field in ("brand", "model", "uaModel", "gpu", "soc"):
                value = row.get(field)
                if not isinstance(value, str) or not value.strip():
                    errors.append(f"{rid}: missing/invalid {field}")

            display = row.get("display")
            if not isinstance(display, dict):
                errors.append(f"{rid}: display must be an object")
            else:
                for field in ("w", "h"):
                    value = display.get(field)
                    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                        errors.append(f"{rid}: display.{field} must be a positive integer")
                if not positive_number(display.get("dpr")):
                    errors.append(f"{rid}: display.dpr must be a positive number")

            for field in ("ramGb", "storageGb"):
                values = row.get(field)
                if (
                    not isinstance(values, list)
                    or not values
                    or any(not isinstance(v, int) or isinstance(v, bool) or v <= 0 for v in values)
                ):
                    errors.append(f"{rid}: {field} must be a non-empty list of positive integers")
                elif len(values) != len(set(values)):
                    errors.append(f"{rid}: {field} contains duplicates")

            cores = row.get("cores")
            if not isinstance(cores, int) or isinstance(cores, bool) or cores <= 0:
                errors.append(f"{rid}: cores must be a positive integer")

            os_versions = row.get("osVersions")
            if not isinstance(os_versions, list) or not os_versions:
                errors.append(f"{rid}: osVersions must be a non-empty list")
                os_versions = []
            else:
                for version in os_versions:
                    if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
                        errors.append(f"{rid}: invalid OS version {version!r}")
                if all(isinstance(v, str) for v in os_versions):
                    if len(os_versions) != len(set(os_versions)):
                        errors.append(f"{rid}: osVersions contains duplicates")

            builds = row.get("builds")
            if builds is None:
                builds = {}
            if not isinstance(builds, dict):
                errors.append(f"{rid}: builds must be an object")
                builds = {}

            for os_version, build_id in builds.items():
                if os_version not in os_versions:
                    errors.append(
                        f"{rid}: builds key {os_version!r} not in osVersions"
                    )
                if not isinstance(build_id, str) or not BUILD_RE.fullmatch(build_id):
                    errors.append(f"{rid}: invalid build ID {build_id!r}")

            valid_os_versions = {v for v in os_versions if isinstance(v, str)}
            missing_builds = sorted(valid_os_versions - set(builds))
            if section == "ios_emulation" and missing_builds:
                errors.append(
                    f"{rid}: iOS build IDs missing for OS versions: {', '.join(missing_builds)}"
                )
            elif missing_builds:
                warnings.append(
                    f"{rid}: Android build IDs missing for OS versions: {', '.join(missing_builds)}"
                )

            markets = row.get("markets")
            if markets is None or markets == []:
                warnings.append(f"{rid}: no verified official-market list")
            elif not isinstance(markets, list) or any(
                not isinstance(m, str) or not COUNTRY_RE.fullmatch(m) for m in markets
            ):
                errors.append(f"{rid}: markets must be ISO-style two-letter uppercase codes")
            elif len(markets) != len(set(markets)):
                errors.append(f"{rid}: markets contains duplicates")

    if evidence_supplied:
        validate_evidence(data, evidence, errors)

    return errors, warnings, total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "catalog",
        nargs="?",
        default="app/src/main/assets/devices.json",
        help="path to devices.json",
    )
    parser.add_argument("--evidence", help="per-pair collector evidence JSON")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="promote incomplete-data warnings (such as Android build IDs) to errors",
    )
    args = parser.parse_args()

    try:
        data = load_json(args.catalog)
        evidence = load_json(args.evidence) if args.evidence else None
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: cannot read input: {exc}")
        return 2

    errors, warnings, total = validate_data(
        data, evidence=evidence, evidence_supplied=bool(args.evidence)
    )
    if args.strict:
        errors.extend(f"STRICT: {warning}" for warning in warnings)
        warnings = []

    for warning in warnings:
        print(f"WARN: {warning}")
    for error in errors:
        print(f"ERROR: {error}")
    print(f"Checked {total} devices, {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
