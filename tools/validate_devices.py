#!/usr/bin/env python3
"""Validate devices.json (schema v2) for NEXORA.

Coherence rules enforced:
- meta block present, schemaVersion == 2, dataVersion is YYYY.MM.DD.N
- versions.chrome is a full 4-part version string
- device ids unique across all sections
- every device row is complete: brand, model, uaModel, gpu, display (w/h/dpr),
  ramGb, storageGb, cores, osVersions all present and sane
- builds (OS version -> firmware build ID) keys must be a subset of osVersions
  and build IDs must look like real firmware IDs

Usage: python3 tools/validate_devices.py [path-to-devices.json]
Exit code 0 = valid, 1 = errors (printed one per line).
"""
import json
import re
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/devices.json"
errors = []
warnings = []

try:
    with open(PATH, encoding="utf-8") as f:
        data = json.load(f)
except Exception as e:  # noqa: BLE001
    print(f"ERROR: cannot parse {PATH}: {e}")
    sys.exit(1)

meta = data.get("meta")
if not isinstance(meta, dict):
    errors.append("meta block missing")
else:
    if meta.get("schemaVersion") != 2:
        errors.append(f"meta.schemaVersion must be 2, got {meta.get('schemaVersion')!r}")
    dv = str(meta.get("dataVersion", ""))
    if not re.fullmatch(r"\d{4}\.\d{2}\.\d{2}\.\d+", dv):
        errors.append(f"meta.dataVersion must be YYYY.MM.DD.N, got {dv!r}")
    chrome = (meta.get("versions") or {}).get("chrome")
    if not chrome or not re.fullmatch(r"\d+\.\d+\.\d+\.\d+", str(chrome)):
        errors.append(f"meta.versions.chrome must be a 4-part version, got {chrome!r}")

seen_ids = set()
total = 0
for section in ("android", "ios_emulation"):
    rows = data.get(section)
    if not isinstance(rows, list) or not rows:
        errors.append(f"section {section!r} missing or empty")
        continue
    for row in rows:
        total += 1
        rid = row.get("id", "?")
        if rid in seen_ids:
            errors.append(f"duplicate device id: {rid}")
        seen_ids.add(rid)
        for field in ("brand", "model", "uaModel", "gpu", "soc"):
            if not row.get(field):
                errors.append(f"{rid}: missing {field}")
        disp = row.get("display") or {}
        if not (disp.get("w", 0) > 0 and disp.get("h", 0) > 0 and disp.get("dpr", 0) > 0):
            errors.append(f"{rid}: display w/h/dpr must all be > 0")
        if not row.get("ramGb") or not row.get("storageGb"):
            errors.append(f"{rid}: ramGb/storageGb must be non-empty")
        if not (isinstance(row.get("cores"), int) and row["cores"] > 0):
            errors.append(f"{rid}: cores must be a positive int")
        os_versions = row.get("osVersions") or []
        if not os_versions:
            errors.append(f"{rid}: osVersions empty")
        builds = row.get("builds") or {}
        for osv, build_id in builds.items():
            if osv not in os_versions:
                errors.append(f"{rid}: builds key {osv!r} not in osVersions {os_versions}")
            if not re.fullmatch(r"[A-Za-z0-9._/-]+", str(build_id)):
                errors.append(f"{rid}: build ID {build_id!r} has invalid characters")
        if not builds:
            warnings.append(f"{rid}: no build IDs yet (legacy row)")

for w in warnings:
    print(f"WARN: {w}")
for e in errors:
    print(f"ERROR: {e}")
print(f"Checked {total} devices, {len(errors)} errors, {len(warnings)} warnings")
sys.exit(1 if errors else 0)
