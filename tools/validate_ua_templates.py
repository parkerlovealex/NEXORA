#!/usr/bin/env python3
"""Validate NEXORA UA templates against devices.json and evidence rules."""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from publicsuffixlist import PublicSuffixList

PUBLISHER_DOMAINS = PublicSuffixList()
STATUSES = {"candidate", "pending", "verified", "outdated", "rejected"}
KINDS = {"observed-ua", "official-doc", "published-dataset-entry", "technical-reference", "candidate-script"}
PLATFORMS = {"android", "ios"}
VERSION_RE = re.compile(r"^\d{4}\.\d{2}\.\d{2}\.\d+$")
SLOT_RE = re.compile(r"\{([A-Za-z][A-Za-z0-9_]*)\}")


def load_json(path: str | Path) -> Any:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def resolve_slot_path(path: str, app: str, devices: dict[str, Any]) -> bool:
    """Whitelist resolvable device/app paths; no arbitrary expressions."""
    meta = devices.get("meta", {})
    apps = meta.get("apps", {})
    entry = apps.get(app)
    if not isinstance(entry, dict):
        entry = {}
    if path == f"meta.apps.{app}.version":
        return isinstance(entry.get("version"), str)
    if path == f"meta.apps.{app}.build":
        return isinstance(entry.get("build"), (str, int))
    if path == f"meta.apps.{app}.versions[].version":
        return any(isinstance(x, dict) and x.get("version") for x in (entry.get("versions") or []))
    if path == f"meta.apps.{app}.versions[].build":
        return any(isinstance(x, dict) and x.get("build") for x in (entry.get("versions") or []))
    if path == "meta.versions.chrome":
        versions = meta.get("versions")
        return isinstance(versions, dict) and isinstance(versions.get("chrome"), str)
    if not path.startswith("device."):
        return False
    field = path.removeprefix("device.")
    allowed = {
        "brand", "model", "uaModel", "codename", "soc", "id",
        "osVersions[]", "builds[osVersion]", "display.w", "display.h",
        "display.dpr", "display.ppi",
    }
    if field not in allowed:
        return False
    rows = devices.get("android", []) + devices.get("ios_emulation", [])
    for row in rows:
        if field == "osVersions[]" and row.get("osVersions"):
            return True
        if field == "builds[osVersion]" and row.get("builds"):
            return True
        value: Any = row
        for part in field.split("."):
            value = value.get(part) if isinstance(value, dict) else None
        if value is not None:
            return True
    return False


def template_matches_observed(template: str, observed: str) -> bool:
    # Replace slots in the original template, escaping all literal text.
    pieces = []
    cursor = 0
    for match in SLOT_RE.finditer(template):
        pieces.append(re.escape(template[cursor:match.start()]))
        pieces.append(r"[^;()\[\]]+?")
        cursor = match.end()
    pieces.append(re.escape(template[cursor:]))
    try:
        return re.fullmatch("".join(pieces), observed) is not None
    except re.error:
        return False


def corroborates(template: str, slots: dict, app: str, evidence: dict) -> bool:
    if not template or evidence.get("kind") not in KINDS - {"candidate-script"}:
        return False
    expected = template
    for slot, path in slots.items():
        for field in ("version", "build"):
            if path in (f"meta.apps.{app}.{field}", f"meta.apps.{app}.versions[].{field}"):
                supplied = evidence.get(f"app_{field}")
                if supplied:
                    expected = expected.replace("{" + slot + "}", str(supplied))
    observed = evidence.get("observed")
    if isinstance(observed, str) and observed:
        return template_matches_observed(expected, observed)
    # An official specification may quote the complete format instead of a sample.
    quote = evidence.get("quoted_text")
    return evidence.get("kind") == "official-doc" and isinstance(quote, str) and (
        template in quote or expected in quote
    )


def validate(data: Any, devices: dict[str, Any]) -> tuple[list[str], list[str], dict[str, int]]:
    errors: list[str] = []
    warnings: list[str] = []
    counts: dict[str, int] = {}
    if not isinstance(data, dict):
        return ["catalog root must be an object"], warnings, counts
    if data.get("uaTemplatesSchema") != 1:
        errors.append("uaTemplatesSchema must equal 1")
    version = str(data.get("dataVersion") or "")
    if not VERSION_RE.fullmatch(version):
        errors.append(f"dataVersion must be YYYY.MM.DD.N, got {version!r}")
    else:
        try:
            date(*(int(x) for x in version.split(".")[:3]))
        except ValueError:
            errors.append(f"dataVersion has invalid calendar date: {version!r}")
    try:
        date.fromisoformat(str(data.get("updatedAt") or ""))
    except ValueError:
        errors.append("updatedAt must be YYYY-MM-DD")

    apps = devices.get("meta", {}).get("apps", {})
    records = data.get("records")
    if not isinstance(records, list):
        errors.append("records must be an array")
        return errors, warnings, counts

    ids: set[str] = set()
    current_verified: set[tuple[str, str]] = set()
    duplicate_templates: set[tuple[str, str, str]] = set()
    for index, row in enumerate(records):
        prefix = f"records[{index}]"
        if not isinstance(row, dict):
            errors.append(f"{prefix} must be an object")
            continue
        rid = str(row.get("id") or "")
        app = str(row.get("app") or "")
        platform = str(row.get("platform") or "")
        status = str(row.get("status") or "")
        counts[status] = counts.get(status, 0) + 1
        if not rid or rid in ids:
            errors.append(f"{prefix} has missing or duplicate id {rid!r}")
        ids.add(rid)
        if app not in apps:
            errors.append(f"{prefix} app {app!r} is not in devices.meta.apps")
        if platform not in PLATFORMS:
            errors.append(f"{prefix} platform must be android or ios")
        if status not in STATUSES:
            errors.append(f"{prefix} has invalid status {status!r}")
        if row.get("schemaVersion", 1) != 1:
            errors.append(f"{prefix} schemaVersion must equal 1")

        template = row.get("template")
        source_format = row.get("source_format")
        slots = row.get("slots") if isinstance(row.get("slots"), dict) else {}
        if status in {"pending", "verified", "outdated"} and (not isinstance(template, str) or not template):
            errors.append(f"{prefix} status {status} requires a normalized template")
        if status == "candidate" and not (isinstance(source_format, str) and source_format.strip()) and not (isinstance(template, str) and template.strip()):
            errors.append(f"{prefix} candidate requires source_format or template")
        if isinstance(template, str) and template:
            placeholders = SLOT_RE.findall(template)
            if len(placeholders) != len(set(placeholders)):
                # Repeated slots are expected, so only check the declared set below.
                pass
            if set(placeholders) != set(slots):
                errors.append(f"{prefix} template slots {sorted(set(placeholders))} do not equal declared slots {sorted(slots)}")
            for slot, path in slots.items():
                if not isinstance(path, str) or not resolve_slot_path(path, app, devices):
                    errors.append(f"{prefix} slot {slot!r} has unresolvable path {path!r}")
            if platform == "android" and re.search(r"iPhone; CPU iPhone OS|iPad; CPU OS", template):
                errors.append(f"{prefix} Android template contains an iOS platform token")
            if platform == "ios" and re.search(r"Linux; Android", template):
                errors.append(f"{prefix} iOS template contains an Android platform token")
            signature = re.sub(r"\{[A-Za-z][A-Za-z0-9_]*\}", "{}", template)
            key = (app, platform, signature)
            if key in duplicate_templates:
                errors.append(f"{prefix} duplicates an app/platform/template signature")
            duplicate_templates.add(key)

        evidence = row.get("evidence") if isinstance(row.get("evidence"), list) else []
        matching_sources: list[tuple[str, str, bool]] = []
        has_version_pair = False
        for ev_index, ev in enumerate(evidence):
            ep = f"{prefix}.evidence[{ev_index}]"
            if not isinstance(ev, dict):
                errors.append(f"{ep} must be an object")
                continue
            kind = ev.get("kind")
            if kind not in KINDS:
                errors.append(f"{ep} has invalid kind {kind!r}")
            source_id = str(ev.get("source_id") or "").strip().casefold()
            publisher = None
            source_url = str(ev.get("source_url") or "")
            source_ref = str(ev.get("source_ref") or "")
            if source_url:
                try:
                    parsed = urlparse(source_url)
                    if parsed.scheme == "https" and parsed.hostname and parsed.username is None:
                        publisher = PUBLISHER_DOMAINS.privatesuffix(parsed.hostname.rstrip(".").encode("idna").decode("ascii"))
                except (ValueError, UnicodeError):
                    pass
                if not publisher:
                    errors.append(f"{ep} source_url must be https")
            elif not (kind == "candidate-script" and source_ref):
                errors.append(f"{ep} requires https source_url (or source_ref for candidate-script)")
            if not str(ev.get("quoted_text") or "").strip():
                errors.append(f"{ep} quoted_text is required")
            matches = bool(source_id and publisher and isinstance(template, str) and corroborates(template, slots, app, ev))
            if matches:
                matching_sources.append((source_id, publisher, kind in {"observed-ua", "official-doc"}))
            fetched_at = ev.get("fetched_at")
            try:
                date.fromisoformat(str(fetched_at or ""))
            except ValueError:
                errors.append(f"{ep} fetched_at must be YYYY-MM-DD")
            observed_at = ev.get("observed_at")
            if observed_at:
                try:
                    date.fromisoformat(str(observed_at))
                except ValueError:
                    errors.append(f"{ep} observed_at must be YYYY-MM-DD when supplied")
            elif status == "verified":
                errors.append(f"{ep} verified evidence requires an actual observed_at date")
            ev_version = ev.get("app_version")
            ev_build = ev.get("app_build")
            if ev_version and ev_build and app in apps:
                app_entry = apps.get(app)
                if not isinstance(app_entry, dict):
                    app_entry = {}
                pairs = {(str(x.get("version")), str(x.get("build"))) for x in (app_entry.get("versions") or []) if isinstance(x, dict)}
                pairs.add((str(app_entry.get("version")), str(app_entry.get("build"))))
                if (str(ev_version), str(ev_build)) in pairs:
                    has_version_pair = has_version_pair or matches
                elif status == "verified":
                    errors.append(f"{ep} app version/build pair is absent from devices.meta.apps.{app}")

        if status == "verified":
            pair = (app, platform)
            if pair in current_verified:
                errors.append(f"{prefix} creates more than one current verified template for {app}/{platform}")
            current_verified.add(pair)
            if not any(primary and first_id != other_id and first_site != other_site
                       for first_id, first_site, primary in matching_sources
                       for other_id, other_site, _ in matching_sources):
                errors.append(f"{prefix} verified status requires two independent source IDs on distinct HTTPS domains that each match the template")
            if not any(primary for _, _, primary in matching_sources):
                errors.append(f"{prefix} verified status requires observed-ua or official-doc evidence")
            if not has_version_pair:
                errors.append(f"{prefix} verified status requires a matching app_version/app_build pair")

    devices_version = devices.get("meta", {}).get("dataVersion")
    if data.get("deviceCatalogVersion") is not None and data.get("deviceCatalogVersion") != devices_version:
        errors.append(f"deviceCatalogVersion {data.get('deviceCatalogVersion')!r} does not match the devices catalog version {devices_version!r}")

    # Coverage is a separate inventory; it never makes an app selectable.
    coverage = data.get("coverage", [])
    if not isinstance(coverage, list):
        errors.append("coverage must be an array")
    else:
        coverage_apps = [x.get("app") for x in coverage if isinstance(x, dict)]
        if set(coverage_apps) != set(apps):
            errors.append("coverage must contain exactly every app in devices.meta.apps")
        for item in coverage:
            if not isinstance(item, dict) or not isinstance(item.get("platforms"), dict):
                errors.append("each coverage row must contain app and platforms")
                continue
            if set(item["platforms"]) != PLATFORMS:
                errors.append(f"coverage for {item.get('app')} must include android and ios")
            for state in item["platforms"].values():
                if state not in {*STATUSES, "not-collected"}:
                    errors.append(f"coverage for {item.get('app')} has invalid state {state!r}")
        if len(coverage_apps) != len(set(coverage_apps)):
            errors.append("coverage contains duplicate app rows")
        # A29: a coverage cell is a claim about the records, so derive the
        # expected state from the records and compare — a status word in
        # the coverage table is never accepted on its own.
        precedence = ["verified", "candidate", "pending", "outdated", "rejected"]
        derived: dict[tuple[str, str], str] = {}
        for record in records:
            if not isinstance(record, dict):
                continue
            key = (record.get("app"), record.get("platform"))
            status = record.get("status")
            if key[0] is None or key[1] is None or status not in STATUSES:
                continue
            current = derived.get(key)
            if current is None or precedence.index(status) < precedence.index(current):
                derived[key] = status
        for item in coverage:
            if not isinstance(item, dict) or not isinstance(item.get("platforms"), dict):
                continue
            for platform in PLATFORMS:
                if platform not in item["platforms"]:
                    continue
                expected = derived.get((item.get("app"), platform), "not-collected")
                actual = item["platforms"][platform]
                if actual != expected:
                    errors.append(f"coverage for {item.get('app')}/{platform} says {actual!r} but the records derive {expected!r}")
    return errors, warnings, counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog", help="ua_templates.proposed.json")
    parser.add_argument("--devices", required=True, help="published devices.json")
    parser.add_argument("--report", help="optional Markdown report")
    args = parser.parse_args()
    try:
        catalog = load_json(args.catalog)
        devices = load_json(args.devices)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    errors, warnings, counts = validate(catalog, devices)
    summary = [
        "# NEXORA UA template validation",
        "",
        f"- Records: {sum(counts.values())}",
        "- Statuses: " + (", ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "none"),
        f"- Errors: {len(errors)}",
        f"- Warnings: {len(warnings)}",
        "",
    ]
    summary += [f"- ERROR: {item}" for item in errors]
    summary += [f"- WARNING: {item}" for item in warnings]
    output = "\n".join(summary)
    print(output)
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(output + "\n", encoding="utf-8")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
