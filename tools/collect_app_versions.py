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
import sys
from datetime import datetime, timezone
from pathlib import Path

KNOWN_APPS = [
    "facebook", "instagram", "tiktok", "x", "threads", "linkedin",
    "pinterest", "reddit", "snapchat", "weibo", "telegram", "messenger",
    "wechat", "line", "kik", "discord", "viber", "fb_lite", "webview",
]


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
        version = str(fnd.get("version") or "").strip()
        source = str(fnd.get("source_url") or "").strip()
        quote = str(fnd.get("quoted_text") or "").strip()
        if not version or not source.startswith("https://") or not quote:
            rejected.append(f"{app}: finding lacks version, https source_url or quoted_text")
            continue
        entry = {"version": version}
        if fnd.get("build"):
            entry["build"] = str(fnd["build"])
        apps_meta[app] = entry
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
