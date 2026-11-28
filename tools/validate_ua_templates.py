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

STATUSES = {"candidate", "pending", "verified", "outdated", "rejected"}
KINDS = {"observed-ua", "official-doc", "published-dataset-entry", "technical-reference", "candidate-script"}
PLATFORMS = {"android", "ios"}
VERSION_RE = re.compile(r"^\d{4}\.\d{2}\.\d{2}\.\d+$")
SLOT_RE = re.compile(r"\{([A-Za-z][A-Za-z0-9_]*)\}")
# Documented structural alternation markers, scoped to the specific records they were
# documented for: an unresolved_slots entry that is not a {placeholder} is accepted
# only when the record is listed here with that marker AND the source_format actually
# contains an " OR " alternation. Extend only by documenting the new record + marker
# in docs/UA-TEMPLATE-SCHEMA.md in the same change.
ALTERNATION_MARKERS = {
    "facebook-android-motorola-script-v1": {"optional_IABMV"},
    "facebook-android-samsung-script-v1": {"optional_IABMV"},
}


def load_json(path: str | Path) -> Any:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def resolve_slot_path(path: str, app: str, devices: dict[str, Any]) -> bool:
    """Whitelist resolvable device/app paths; no arbitrary expressions."""
    meta = devices.get("meta", {})
    apps = meta.get("apps", {})
    if path == f"meta.apps.{app}.version":
        return isinstance(apps.get(app, {}).get("version"), str)
    if path == f"meta.apps.{app}.build":
        return isinstance(apps.get(app, {}).get("build"), (str, int))
    if path == f"meta.apps.{app}.versions[].version":
        return any(isinstance(x, dict) and x.get("version") for x in apps.get(app, {}).get("versions", []))
    if path == f"meta.apps.{app}.versions[].build":
        return any(isinstance(x, dict) and x.get("build") for x in apps.get(app, {}).get("versions", []))
    if path == "meta.versions.chrome":
        return isinstance(meta.get("versions", {}).get("chrome"), str)
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
    status_rank = {"verified": 5, "candidate": 4, "pending": 3, "outdated": 2, "rejected": 1}
    best_status: dict[tuple[str, str], str] = {}
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
        pair_key = (app, platform)
        if status in status_rank and (pair_key not in best_status or status_rank[status] > status_rank[best_status[pair_key]]):
            best_status[pair_key] = status
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

        if status == "candidate" and template is None and isinstance(source_format, str) and source_format and isinstance(row.get("unresolved_slots"), list):
            placeholders = set(SLOT_RE.findall(source_format))
            unresolved = {str(x) for x in row.get("unresolved_slots") or []}
            derived = {p for p in placeholders if p.startswith("formatted_") and p[len("formatted_"):] in unresolved}
            unaccounted = placeholders - unresolved - derived
            if unaccounted:
                errors.append(f"{prefix} candidate placeholders not accounted for in unresolved_slots: {sorted(unaccounted)}")
            extra = unresolved - placeholders
            allowed_extra = ALTERNATION_MARKERS.get(str(row.get("id")), set())
            unrecognized = extra - allowed_extra
            if unrecognized or (extra and " OR " not in source_format):
                errors.append(f"{prefix} candidate unresolved_slots not recognized as placeholders or documented alternation markers: {sorted(extra)}")

        evidence = row.get("evidence") if isinstance(row.get("evidence"), list) else []
        source_ids: set[str] = set()
        observed_sources: set[str] = set()
        has_observed_or_official = False
        has_version_pair = False
        observed_values: list[str] = []
        for ev_index, ev in enumerate(evidence):
            ep = f"{prefix}.evidence[{ev_index}]"
            if not isinstance(ev, dict):
                errors.append(f"{ep} must be an object")
                continue
            kind = ev.get("kind")
            if kind not in KINDS:
                errors.append(f"{ep} has invalid kind {kind!r}")
            source_id = str(ev.get("source_id") or "")
            if source_id:
                source_ids.add(source_id)
            source_url = str(ev.get("source_url") or "")
            source_ref = str(ev.get("source_ref") or "")
            if source_url:
                parsed = urlparse(source_url)
                if parsed.scheme != "https" or not parsed.netloc:
                    errors.append(f"{ep} source_url must be https")
                else:
                    observed_sources.add(parsed.netloc.lower())
            elif not (kind == "candidate-script" and source_ref):
                errors.append(f"{ep} requires https source_url (or source_ref for candidate-script)")
            if not str(ev.get("quoted_text") or "").strip():
                errors.append(f"{ep} quoted_text is required")
            observed = ev.get("observed")
            if isinstance(observed, str) and observed:
                observed_values.append(observed)
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
            if kind in {"observed-ua", "official-doc"}:
                has_observed_or_official = True
            ev_version = ev.get("app_version")
            ev_build = ev.get("app_build")
            if ev_version and ev_build and app in apps:
                pairs = {(str(x.get("version")), str(x.get("build"))) for x in apps[app].get("versions", []) if isinstance(x, dict)}
                pairs.add((str(apps[app].get("version")), str(apps[app].get("build"))))
                if (str(ev_version), str(ev_build)) in pairs:
                    has_version_pair = True
                elif status == "verified":
                    errors.append(f"{ep} app version/build pair is absent from devices.meta.apps.{app}")

        if status == "verified" and row.get("unresolved_slots"):
            errors.append(f"{prefix} verified record must not carry unresolved_slots")
        if status == "verified":
            pair = (app, platform)
            if pair in current_verified:
                errors.append(f"{prefix} creates more than one current verified template for {app}/{platform}")
            current_verified.add(pair)
            if len(source_ids) < 2 or len(observed_sources) < 2:
                errors.append(f"{prefix} verified status requires two independent source IDs and two HTTPS domains")
            if not has_observed_or_official:
                errors.append(f"{prefix} verified status requires observed-ua or official-doc evidence")
            if not has_version_pair:
                errors.append(f"{prefix} verified status requires a matching app_version/app_build pair")
            if not isinstance(template, str) or not any(template_matches_observed(template, value) for value in observed_values):
                errors.append(f"{prefix} verified template does not match any exact observed UA")
            current_paths = {f"meta.apps.{app}.version": "version", f"meta.apps.{app}.build": "build"}
            if isinstance(template, str) and slots and all(path in current_paths for path in slots.values()):
                built = template
                for slot, path in slots.items():
                    built = built.replace("{" + slot + "}", str(apps.get(app, {}).get(current_paths[path], "")))
                if built not in observed_values:
                    errors.append(f"{prefix} verified fixture build does not match any observed UA")

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
            for platform_name, state in item["platforms"].items():
                expected = best_status.get((str(item.get("app")), platform_name), "not-collected")
                if state != expected:
                    errors.append(f"coverage for {item.get('app')}/{platform_name} says {state!r} but records support {expected!r}")
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
