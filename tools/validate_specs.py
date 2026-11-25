#!/usr/bin/env python3
"""Validate specs evidence and primary-source publication rules."""
import argparse
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

PRIMARY = {
    "google_pixel_support": {"support.google.com"},
    "google_aosp_pixel_docs": {"source.android.com"},
    "xiaomi_official_specs": {"mi.com", "www.mi.com"},
    "honor_official_specs": {"honor.com", "www.honor.com"},
}
SECONDARY = {
    "devicespecifications": {"devicespecifications.com", "www.devicespecifications.com"},
    "phonedb": {"phonedb.net", "www.phonedb.net"},
    "gsmarena": {"gsmarena.com", "www.gsmarena.com"},
}


def timestamp_ok(value):
    try:
        datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return True
    except ValueError:
        return False


def norm(value):
    return re.sub(r"[^a-z0-9]+", " ", str(value or "").lower()).strip()


def host_allowed(source, url, source_class):
    host = (urlparse(url).hostname or "").lower()
    table = PRIMARY if source_class == "primary" else SECONDARY
    return host in table.get(source, set())


def validate(catalog, evidence):
    errors, warnings = [], []
    if not isinstance(catalog, dict) or not isinstance(evidence, dict):
        return ["catalog/evidence root must be objects"], warnings
    if evidence.get("schemaVersion") != 1:
        errors.append("specs evidence schemaVersion must be 1")
        return errors, warnings
    ev_devices = evidence.get("devices")
    if not isinstance(ev_devices, dict):
        errors.append("specs evidence.devices must be an object")
        return errors, warnings

    rows = catalog.get("android")
    if not isinstance(rows, list):
        errors.append("catalog.android must be a list")
        return errors, warnings

    known = set()
    for row in rows:
        if not isinstance(row, dict) or not row.get("id"):
            continue
        did = row["id"]
        known.add(did)
        display = row.get("display") if isinstance(row.get("display"), dict) else {}
        dev_ev = ev_devices.get(did)
        if not isinstance(dev_ev, dict):
            warnings.append(f"{did}: no specs evidence record")
            dev_ev = {}

        for field in ("ppi", "codename"):
            field_ev = dev_ev.get(field) if isinstance(dev_ev.get(field), dict) else {}
            observations = field_ev.get("observations", [])
            if not isinstance(observations, list):
                errors.append(f"{did}.{field}: observations must be a list")
                continue
            primary_values = []
            secondary_values = []
            for i, obs in enumerate(observations):
                prefix = f"{did}.{field}.observation[{i}]"
                if not isinstance(obs, dict):
                    errors.append(f"{prefix}: must be an object")
                    continue
                source = obs.get("source")
                source_class = obs.get("source_class")
                url = obs.get("source_url")
                quote = obs.get("quoted_text")
                if source_class not in ("primary", "secondary"):
                    errors.append(f"{prefix}: source_class must be primary or secondary")
                elif not isinstance(url, str) or not host_allowed(source, url, source_class):
                    errors.append(f"{prefix}: source URL/domain is not approved for {source_class} source {source!r}")
                if not timestamp_ok(obs.get("fetched_at")):
                    errors.append(f"{prefix}: invalid fetched_at")
                if not isinstance(quote, str) or not quote.strip():
                    errors.append(f"{prefix}: quoted_text is required")
                if obs.get("eligible") is True:
                    identity = obs.get("identity")
                    if not isinstance(identity, dict):
                        if source_class == "primary":
                            errors.append(f"{prefix}: primary eligible observation needs identity evidence")
                        else:
                            warnings.append(f"{prefix}: legacy secondary observation has no identity metadata")
                    else:
                        model_text = norm(identity.get("model_text"))
                        expected_model = norm(row.get("model"))
                        expected_brand_model = norm(f"{row.get('brand', '')} {row.get('model', '')}")
                        if source_class == "primary" and expected_model not in model_text and expected_brand_model not in model_text:
                            errors.append(f"{prefix}: identity model text does not match catalog model")
                    value = obs.get("value")
                    if field == "ppi":
                        if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value <= 0:
                            errors.append(f"{prefix}: PPI must be a positive number")
                        if source_class == "primary" and isinstance(value, (int, float)):
                            if str(value) not in quote:
                                # 422.0 and 422 are equivalent for quoted integer sources.
                                if not (float(value).is_integer() and f"{int(value)} PPI" in quote):
                                    errors.append(f"{prefix}: quote does not prove PPI value")
                    else:
                        if not isinstance(value, str) or not value.strip():
                            errors.append(f"{prefix}: codename must be a non-empty string")
                        elif source_class == "primary" and value.lower() not in quote.lower():
                            errors.append(f"{prefix}: quote does not prove codename")
                    if source_class == "primary":
                        primary_values.append(value)
                    else:
                        secondary_values.append(value)

            catalog_value = display.get("ppi") if field == "ppi" else row.get("codename")
            verdict = field_ev.get("verdict") if isinstance(field_ev.get("verdict"), dict) else {}
            if primary_values:
                distinct = set(primary_values)
                if len(distinct) > 1:
                    errors.append(f"{did}.{field}: conflicting primary-source values {sorted(map(str, distinct))}")
                elif catalog_value != next(iter(distinct)):
                    errors.append(f"{did}.{field}: catalog value {catalog_value!r} does not match primary evidence {next(iter(distinct))!r}")
                elif verdict.get("status") != "primary":
                    errors.append(f"{did}.{field}: primary evidence must have verdict status 'primary'")
            elif catalog_value is not None:
                warnings.append(f"{did}.{field}: catalog value has no eligible primary-source evidence")
            elif secondary_values:
                warnings.append(f"{did}.{field}: secondary-only candidates are not publishable")
            elif field == "codename":
                warnings.append(f"{did}: codename remains unknown; no primary evidence")

        resolution_ev = dev_ev.get("display_resolution")
        if isinstance(resolution_ev, dict):
            for i, obs in enumerate(resolution_ev.get("observations", [])):
                if not isinstance(obs, dict):
                    errors.append(f"{did}.display_resolution.observation[{i}] must be an object")
                    continue
                source = obs.get("source")
                url = obs.get("source_url")
                if obs.get("source_class") != "primary" or not host_allowed(source, url, "primary"):
                    errors.append(f"{did}.display_resolution.observation[{i}]: not an approved primary source")
                if not timestamp_ok(obs.get("fetched_at")) or not obs.get("quoted_text"):
                    errors.append(f"{did}.display_resolution.observation[{i}]: missing timestamp or quote")
                value = obs.get("value")
                if obs.get("eligible") is True:
                    if value != {"w": display.get("w"), "h": display.get("h")}:
                        errors.append(f"{did}: display resolution evidence does not match catalog")
        else:
            # No resolution evidence is required unless the catalog has an explicit resolution correction.
            pass

    unknown = set(ev_devices) - known
    for did in sorted(unknown):
        errors.append(f"specs evidence contains unknown Android device {did}")
    return errors, warnings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("catalog")
    ap.add_argument("--evidence", required=True)
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()
    try:
        catalog = json.loads(Path(args.catalog).read_text(encoding="utf-8"))
        evidence = json.loads(Path(args.evidence).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}")
        return 2
    errors, warnings = validate(catalog, evidence)
    if args.strict:
        errors.extend(f"STRICT: {w}" for w in warnings)
    for err in errors:
        print("ERROR:", err)
    for warning in warnings:
        print("WARN:", warning)
    print(f"Checked {len(catalog.get('android', []))} Android devices, {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
