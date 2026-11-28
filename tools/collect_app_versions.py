#!/usr/bin/env python3
"""NEXORA collector #3 — app version/build pairs (DATA_PLAN step 3).

The app stores (Google Play shows "Varies with device" for the big apps)
and APKMirror (Cloudflare bot check) cannot be read by a plain fetcher, so
this collector is INPUT-DRIVEN: it merges a findings file produced by
whatever lane could actually read the source (the signed-in browser, or a
future API), and it refuses any entry that does not carry its own
evidence. Nothing is invented here; an app with no verified finding stays
null and is listed as a gap.

Findings file shape (JSON):
{
  "apps": {
    "facebook": {
      "version": "550.0.0.34.65",
      "build": "890804754",              // optional; FB's FBBV, IG's build
      "source_url": "https://...",
      "quoted_text": "exact text seen at the source",
      "fetched_at": "2026-11-24T00:00:00+00:00"
    }
  }
}

The merge target is devices.json meta.apps: each known app key gains
{version, build} (build omitted when the app has none). Version and build
are stored as one locked pair from one finding — never mixed across
findings.

Usage mirrors the other collectors. Exit 2 if zero apps merged.
"""
import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

if __package__:
    from .catalog_evidence import supports_version_pair
    from .catalog_versions import merge_app_release
else:
    from catalog_evidence import supports_version_pair
    from catalog_versions import merge_app_release

KNOWN_APPS = [
    "facebook", "instagram", "tiktok", "x", "threads", "linkedin",
    "pinterest", "reddit", "snapchat", "weibo", "telegram", "messenger",
    "wechat", "line", "kik", "discord", "viber", "fb_lite", "webview",
]


def bump_data_version(meta: dict, today) -> None:
    """Mirror the backend's bump_version rule (A40): a successful merge must
    advance dataVersion, because clients compare it to decide whether to
    install the catalog. Same-day bumps increment the counter; a new day
    restarts it at 1."""
    old = str(meta.get("dataVersion", ""))
    match = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})\.(\d+)", old)
    day = today.strftime("%Y.%m.%d")
    if match and ".".join(match.groups()[:3]) == day:
        meta["dataVersion"] = f"{day}.{int(match.group(4)) + 1}"
    else:
        meta["dataVersion"] = f"{day}.1"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True, help="devices.json (or a proposal)")
    ap.add_argument("--findings", required=True, help="findings JSON with evidence")
    ap.add_argument("--out", required=True)
    ap.add_argument("--evidence", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--report-json")
    args = ap.parse_args()

    with open(args.input, encoding="utf-8") as f:
        data = json.load(f)
    with open(args.findings, encoding="utf-8") as f:
        findings = (json.load(f) or {}).get("apps") or {}

    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    meta = data.setdefault("meta", {})
    apps_meta = meta.setdefault("apps", {})
    evidence = {
        "schemaVersion": 1,
        "source": "per-app sources recorded per entry",
        "fetched_at": stamp,
        "evidence_policy": "one source excerpt per app version/build pair",
        "apps": {},
    }
    lines = ["# App version collector — run report", "", f"Run at: {stamp}", ""]
    merged, rejected, gaps = 0, [], []

    for app in KNOWN_APPS:
        fnd = findings.get(app)
        if not fnd:
            gaps.append(app)
            continue
        if not isinstance(fnd, dict):
            rejected.append(f"{app}: finding must be an object")
            continue
        version = str(fnd.get("version") or "").strip()
        build = str(fnd["build"]).strip() if fnd.get("build") is not None else None
        source = str(fnd.get("source_url") or "").strip()
        quote = str(fnd.get("quoted_text") or "").strip()
        try:
            url = urlsplit(source)
            source_ok = url.scheme == "https" and url.hostname and url.username is None and url.password is None
        except ValueError:
            source_ok = False
        if not source_ok or not supports_version_pair(quote, version, build):
            rejected.append(f"{app}: an HTTPS source and a quote supporting the submitted version/build are required")
            continue
        entry = {"version": version, "sourceUrl": source, "sourceEvidence": {"url": source, "quote": quote}}
        if build is not None:
            entry["build"] = build
        apps_meta[app] = merge_app_release(apps_meta.get(app), entry)
        evidence["apps"][app] = {
            "version": version,
            "build": entry.get("build"),
            "source_url": source,
            "quoted_text": quote,
            "fetched_at": fnd.get("fetched_at") or stamp,
        }
        merged += 1
        lines.append(f"- {app}: {version}" + (f" (build {entry['build']})" if entry.get("build") else ""))

    if merged:
        today = datetime.now(timezone.utc).date()
        meta["updatedAt"] = today.isoformat()
        bump_data_version(meta, today)

    lines += ["", "## Summary", f"- merged: {merged}  rejected: {len(rejected)}  gaps: {len(gaps)}"]
    if rejected:
        lines += ["", "## Rejected findings"] + [f"- {r}" for r in rejected]
    if gaps:
        lines += ["", "## Gaps (no verified finding yet)"] + [f"- {g}" for g in gaps]

    report = {"fetched_at": stamp,
              "stats": {"merged": merged, "rejected": len(rejected), "gaps": len(gaps)},
              "status": "proposal_ready" if merged else "failed_zero_data",
              "text": "\n".join(lines) + "\n"}

    for path, obj in ((args.out, data), (args.evidence, evidence)):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=2, ensure_ascii=False)
            f.write("\n")
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(report["text"], encoding="utf-8")
    rj = args.report_json or str(Path(args.report).with_suffix(".json"))
    with open(rj, "w", encoding="utf-8") as f:
        json.dump({k: v for k, v in report.items() if k != "text"}, f, indent=2)
        f.write("\n")
    print(json.dumps(report["stats"], sort_keys=True))
    print(f"status={report['status']}")
    return 0 if merged else 2


if __name__ == "__main__":
    sys.exit(main())
