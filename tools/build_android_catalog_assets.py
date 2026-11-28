#!/usr/bin/env python3
"""Generate the Android app's bundled UA catalog assets from the canonical catalog.

Outputs (never edit by hand — regenerate after every catalog release):
  app/src/main/assets/ua_templates.json    — verbatim copy of web/ua_templates.json
  app/src/main/assets/ua_slot_context.json — the approved slot-resolution context,
      generated from web/devices.json: catalogReleaseId, deviceCatalogVersion and
      meta.apps (application versions and builds). The Android adapter resolves
      verified-template slots against this context only.

Fails without writing anything when the templates catalog and the devices catalog
do not belong to the same catalog release.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    devices = json.loads((ROOT / "web" / "devices.json").read_text())
    templates_text = (ROOT / "web" / "ua_templates.json").read_text()
    templates = json.loads(templates_text)

    meta = devices.get("meta") or {}
    release_id = meta.get("catalogReleaseId")
    device_version = meta.get("dataVersion")
    if templates.get("catalogReleaseId") != release_id:
        print("catalogReleaseId mismatch: templates "
              f"{templates.get('catalogReleaseId')} != devices {release_id}", file=sys.stderr)
        return 1
    if templates.get("deviceCatalogVersion") != device_version:
        print("deviceCatalogVersion mismatch: templates "
              f"{templates.get('deviceCatalogVersion')} != devices {device_version}", file=sys.stderr)
        return 1

    apps = {}
    for app_id, entry in sorted((meta.get("apps") or {}).items()):
        if not isinstance(entry, dict):
            continue
        apps[app_id] = {
            "version": entry.get("version"),
            "build": entry.get("build"),
            "versions": entry.get("versions") or [],
        }
    context = {
        "catalogReleaseId": release_id,
        "deviceCatalogVersion": device_version,
        "apps": apps,
    }

    assets = ROOT / "app" / "src" / "main" / "assets"
    (assets / "ua_templates.json").write_text(templates_text)
    (assets / "ua_slot_context.json").write_text(json.dumps(context, indent=2) + "\n")
    print(f"wrote app/src/main/assets/ua_templates.json (verbatim, dataVersion {templates.get('dataVersion')})")
    print(f"wrote app/src/main/assets/ua_slot_context.json (release {release_id}, "
          f"deviceCatalogVersion {device_version}, {len(apps)} apps)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
