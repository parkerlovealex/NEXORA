#!/usr/bin/env python3
"""NEXORA iOS firmware collector.

Fetches IPSW firmware version/build pairs for every ios_emulation row and
writes a proposal plus per-pair evidence. It never modifies the live catalog.

Exit codes:
  0: all device lookups completed; no malformed records or conflicts
  1: invalid input/arguments or output error
  2: zero usable firmware pairs collected (data alarm)
  3: partial source failure, malformed records, or conflicting version/builds
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone, date
from pathlib import Path
from typing import Any, Callable

API = "https://api.ipsw.me/v4/device/{ident}?type=ipsw"
UA = "NEXORA-Collector/1.1 (+https://ipsw.me API v4)"
VERSION_RE = re.compile(r"^\d+(?:\.\d+){1,3}$")
BUILD_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


def api_url(ident: str) -> str:
    return API.format(ident=urllib.parse.quote(ident, safe=","))


def fetch(ident: str) -> dict[str, Any]:
    url = api_url(ident)
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        payload = json.load(response)
    if not isinstance(payload, dict):
        raise ValueError("API response must be a JSON object")
    return payload


def version_key(version: str) -> tuple[int, ...]:
    """Sort numeric dotted versions without treating suffixes as zero."""
    if not VERSION_RE.fullmatch(version):
        raise ValueError(f"unsupported firmware version format: {version!r}")
    return tuple(int(part) for part in version.split("."))


def bump_data_version(current: str, today: date) -> str:
    """Create YYYY.MM.DD.N without reusing an existing version on the same day."""
    match = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})\.(\d+)", str(current or ""))
    today_prefix = today.strftime("%Y.%m.%d")
    if match and ".".join(match.groups()[:3]) == today_prefix:
        sequence = int(match.group(4)) + 1
    else:
        sequence = 1
    return f"{today_prefix}.{sequence}"


def quote_observation(firmware: dict[str, Any]) -> str:
    """Keep an exact compact JSON excerpt of the source fields we consume."""
    fields = ("identifier", "version", "buildid", "signed", "releasedate")
    excerpt = {key: firmware[key] for key in fields if key in firmware}
    return json.dumps(excerpt, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def collect(
    data: dict[str, Any],
    fetcher: Callable[[str], dict[str, Any]] = fetch,
    fetched_at: str | None = None,
    resolve_conflicts: str = "review",
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], int]:
    """Return proposal, evidence, report, exit_code. No network writes occur."""
    now = datetime.fromisoformat(fetched_at) if fetched_at else datetime.now(timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    stamp = now.astimezone(timezone.utc).isoformat(timespec="seconds")
    today = now.astimezone(timezone.utc).date()

    if not isinstance(data, dict):
        raise ValueError("input catalog must be a JSON object")
    rows = data.get("ios_emulation")
    if not isinstance(rows, list) or not rows:
        raise ValueError("input catalog has no non-empty ios_emulation list")

    proposal = json.loads(json.dumps(data))  # isolate caller's object
    proposal_rows = proposal["ios_emulation"]
    evidence = {
        "schemaVersion": 1,
        "source": "ipsw.me API v4",
        "fetched_at": stamp,
        "evidence_policy": "one or more source excerpts per exact version/build pair",
        "devices": {},
    }

    stats = {
        "checked": 0,
        "succeeded": 0,
        "blocked": 0,
        "invalid_records": 0,
        "conflicts": 0,
        "conflicts_resolved": 0,
        "zero_firmware_devices": 0,
        "changed": 0,
        "pairs": 0,
    }
    report_lines = [
        "# NEXORA iOS firmware collector report",
        "",
        f"Fetched at: {stamp}",
        "",
        "## Device results",
    ]
    device_reports: list[dict[str, Any]] = []

    for row in proposal_rows:
        stats["checked"] += 1
        if not isinstance(row, dict):
            stats["blocked"] += 1
            report_lines.append(f"- INVALID ROW: expected object, got {type(row).__name__}")
            device_reports.append({"id": None, "status": "invalid_row"})
            continue

        device_id = str(row.get("id") or "<missing-id>")
        ident = row.get("uaModel")
        if not isinstance(ident, str) or not ident.strip():
            stats["blocked"] += 1
            report_lines.append(f"- {device_id}: BLOCKED — missing uaModel identifier")
            device_reports.append({"id": device_id, "status": "blocked", "reason": "missing uaModel"})
            continue

        source_url = api_url(ident)
        try:
            payload = fetcher(ident)
            if not isinstance(payload, dict):
                raise ValueError("API response must be a JSON object")
            returned_ident = payload.get("identifier")
            if returned_ident != ident:
                raise ValueError(
                    f"identifier mismatch: requested {ident!r}, received {returned_ident!r}"
                )
            firmwares = payload.get("firmwares")
            if not isinstance(firmwares, list):
                raise ValueError("API response has no firmware list")
        except Exception as exc:  # continue with other devices, but report partial failure
            stats["blocked"] += 1
            report_lines.append(f"- {device_id} ({ident}): SOURCE BLOCKED/ERROR — {exc}")
            device_reports.append({"id": device_id, "uaModel": ident, "status": "source_error", "reason": str(exc)})
            continue

        stats["succeeded"] += 1
        # version -> build ID -> exact source observations
        grouped: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(
            lambda: defaultdict(list)
        )
        invalid_for_device = 0

        for index, firmware in enumerate(firmwares):
            if not isinstance(firmware, dict):
                invalid_for_device += 1
                report_lines.append(
                    f"  - INVALID firmware row #{index}: expected object"
                )
                continue

            fw_ident = firmware.get("identifier")
            if fw_ident is not None and fw_ident != ident:
                invalid_for_device += 1
                report_lines.append(
                    f"  - REJECTED firmware row #{index}: identifier {fw_ident!r} "
                    f"does not match {ident!r}"
                )
                continue

            version = firmware.get("version")
            build_id = firmware.get("buildid")
            if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
                invalid_for_device += 1
                report_lines.append(
                    f"  - REJECTED firmware row #{index}: invalid version {version!r}"
                )
                continue
            if not isinstance(build_id, str) or not BUILD_RE.fullmatch(build_id):
                invalid_for_device += 1
                report_lines.append(
                    f"  - REJECTED {version}: invalid build ID {build_id!r}"
                )
                continue

            # Evidence records the exact API fields used; no signed/unsigned
            # preference is used to silently resolve different build IDs.
            grouped[version][build_id].append({
                "quoted_text": quote_observation(firmware),
                "signed": firmware.get("signed") if isinstance(firmware.get("signed"), bool) else None,
                "releasedate": firmware.get("releasedate"),
            })

        stats["invalid_records"] += invalid_for_device
        versions = sorted(grouped, key=version_key)
        if not versions:
            stats["zero_firmware_devices"] += 1
            report_lines.append(f"- {device_id} ({ident}): ZERO usable firmware pairs — review required")
        old_os_raw = row.get("osVersions")
        old_os_versions = list(old_os_raw) if isinstance(old_os_raw, list) else []
        old_builds = row.get("builds") if isinstance(row.get("builds"), dict) else {}
        new_builds: dict[str, str] = {}
        device_evidence = {
            "device_identifier": ident,
            "source_url": source_url,
            "fetched_at": stamp,
            "firmwares": [],
        }

        device_conflicts = []
        device_resolutions = []
        for version in versions:
            build_ids = sorted(grouped[version])
            for build_id in build_ids:
                observations = grouped[version][build_id]
                device_evidence["firmwares"].append({
                    "version": version,
                    "buildid": build_id,
                    "source_url": source_url,
                    "fetched_at": stamp,
                    "observations": observations,
                })

            if len(build_ids) == 1:
                new_builds[version] = build_ids[0]
            elif resolve_conflicts == "latest":
                # Karn's rule (2026-11-24): when one version ships with two
                # builds, the LATER release becomes the catalog pair; the
                # other build stays in the evidence and is named in the
                # report. Signed status breaks a same-date tie.
                def build_rank(bid: str) -> tuple[str, bool]:
                    obs = grouped[version][bid]
                    latest_date = max(str(o.get("releasedate") or "") for o in obs)
                    any_signed = any(bool(o.get("signed")) for o in obs)
                    return (latest_date, any_signed)

                winner = max(build_ids, key=build_rank)
                new_builds[version] = winner
                stats["conflicts_resolved"] += 1
                device_resolutions.append({
                    "version": version,
                    "chosen": winner,
                    "rejected": [b for b in build_ids if b != winner],
                    "rule": "later releasedate wins; signed breaks ties; rejected kept in evidence",
                })
            else:
                stats["conflicts"] += 1
                device_conflicts.append((version, build_ids))
                # Preserve the old value for review if present, but the
                # validator will reject this version while source evidence conflicts.
                if old_builds.get(version) in build_ids:
                    new_builds[version] = old_builds[version]

        new_os_versions = versions
        old_pairs = {str(k): str(v) for k, v in old_builds.items()}
        changed = (
            old_os_versions != new_os_versions
            or old_pairs != new_builds
        )
        if changed:
            stats["changed"] += 1
        stats["pairs"] += sum(1 for version in new_builds if len(grouped.get(version, {})) == 1)

        row["osVersions"] = new_os_versions
        row["builds"] = new_builds
        device_evidence["resolutions"] = device_resolutions
        evidence["devices"][device_id] = device_evidence

        additions = sorted(set(new_os_versions) - set(old_os_versions), key=version_key)
        removals = sorted(set(old_os_versions) - set(new_os_versions), key=str)
        latest = versions[-1] if versions else None
        latest_builds = sorted(grouped[latest]) if latest else []
        latest_label = (
            f"{latest} / {latest_builds[0]}"
            if latest and len(latest_builds) == 1
            else (f"{latest} / CONFLICT {', '.join(latest_builds)}" if latest else "none")
        )

        status_parts = [
            f"{len(versions)} versions",
            f"{sum(len(v) for v in grouped.values())} distinct version/build pairs",
            f"latest {latest_label}",
        ]
        if invalid_for_device:
            status_parts.append(f"{invalid_for_device} invalid records")
        if device_conflicts:
            status_parts.append(f"{len(device_conflicts)} conflicting versions")
        report_lines.append(f"- {device_id} ({ident}): " + "; ".join(status_parts))
        device_reports.append({
            "id": device_id,
            "uaModel": ident,
            "status": (
                "review_required" if (invalid_for_device or device_conflicts or not versions)
                else "collected"
            ),
            "versions": len(versions),
            "distinct_pairs": sum(len(v) for v in grouped.values()),
            "conflicts": [
                {"version": version, "buildids": build_ids}
                for version, build_ids in device_conflicts
            ],
            "resolutions": device_resolutions,
            "invalid_records": invalid_for_device,
        })

        if additions:
            report_lines.append(f"  - Added versions: {', '.join(additions)}")
        if removals:
            report_lines.append(
                f"  - Removed from source feed: {', '.join(removals)} — review before approval"
            )
        for version, build_ids in device_conflicts:
            report_lines.append(
                f"  - CONFLICT: {version} has multiple build IDs: {', '.join(build_ids)}; "
                "no source winner selected"
            )
        for res in device_resolutions:
            report_lines.append(
                f"  - RESOLVED (later release wins): {res['version']} -> {res['chosen']}; "
                f"other build(s) kept in evidence: {', '.join(res['rejected'])}"
            )

    total_pairs = sum(
        len(device.get("firmwares", []))
        for device in evidence["devices"].values()
    )
    stats["pairs"] = total_pairs

    if total_pairs:
        meta = proposal.get("meta")
        if isinstance(meta, dict) and stats["changed"]:
            meta["updatedAt"] = today.isoformat()
            meta["dataVersion"] = bump_data_version(meta.get("dataVersion", ""), today)

    report_lines += [
        "",
        "## Summary",
        f"- checked devices: {stats['checked']}",
        f"- successful source lookups: {stats['succeeded']}",
        f"- blocked/error lookups: {stats['blocked']}",
        f"- invalid firmware rows: {stats['invalid_records']}",
        f"- conflicting version entries: {stats['conflicts']}",
        f"- conflicts resolved by rule (later release wins): {stats['conflicts_resolved']}",
        f"- devices with zero usable firmware pairs: {stats['zero_firmware_devices']}",
        f"- changed device records: {stats['changed']}",
        f"- evidence-backed distinct version/build pairs: {total_pairs}",
    ]

    if (
        stats["blocked"] or stats["invalid_records"] or stats["conflicts"]
        or stats["zero_firmware_devices"]
    ):
        report_lines += [
            "",
            "## REVIEW REQUIRED",
            "This proposal contains source failures, invalid records, or conflicting pairs. "
            "Do not publish until resolved.",
        ]
    if total_pairs == 0:
        report_lines += [
            "",
            "## ALARM: ZERO FIRMWARE PAIRS",
            "Treat this run as FAILED. No empty/zero-data run is a successful collection.",
        ]

    report = {
        "fetched_at": stamp,
        "stats": stats,
        "devices": device_reports,
        "status": (
            "failed_zero_data" if total_pairs == 0 else
            "review_required" if (
                stats["blocked"] or stats["invalid_records"] or stats["conflicts"]
                or stats["zero_firmware_devices"]
            ) else
            "proposal_ready"
        ),
        "text": "\n".join(report_lines) + "\n",
    }

    if total_pairs == 0:
        exit_code = 2
    elif (
        stats["blocked"] or stats["invalid_records"] or stats["conflicts"]
        or stats["zero_firmware_devices"]
    ):
        exit_code = 3
    else:
        exit_code = 0
    return proposal, evidence, report, exit_code


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument(
        "--report-json",
        help="machine-readable report path (default: report path with .json suffix)",
    )
    parser.add_argument(
        "--resolve-conflicts",
        choices=("review", "latest"),
        default="review",
        help="'latest': when one version has two builds, the later release wins "
             "(the other stays in evidence and is named in the report). "
             "Default 'review': no winner is selected.",
    )
    args = parser.parse_args()

    try:
        with open(args.input, encoding="utf-8") as handle:
            data = json.load(handle)
        proposal, evidence, report, exit_code = collect(
            data, resolve_conflicts=args.resolve_conflicts
        )

        for path, obj in (
            (args.out, proposal),
            (args.evidence, evidence),
        ):
            Path(path).parent.mkdir(parents=True, exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(obj, handle, indent=2, ensure_ascii=False)
                handle.write("\n")

        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(report["text"], encoding="utf-8")
        report_json_path = args.report_json or str(Path(args.report).with_suffix(".json"))
        Path(report_json_path).parent.mkdir(parents=True, exist_ok=True)
        with open(report_json_path, "w", encoding="utf-8") as handle:
            json.dump({k: v for k, v in report.items() if k != "text"}, handle, indent=2)
            handle.write("\n")

        print(json.dumps(report["stats"], sort_keys=True))
        print(f"status={report['status']}")
        return exit_code
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
