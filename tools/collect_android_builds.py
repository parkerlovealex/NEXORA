#!/usr/bin/env python3
"""NEXORA collector #2 — Android platform build IDs from Google (DATA_PLAN step 2).

Source (official): Google's "Codenames, tags, and build numbers" page,
https://source.android.com/docs/setup/reference/build-numbers — one table
row per public build: build ID, source tag (android-14.0.0_r1), version,
supported devices, date.

For each Android version the collector takes the RELEASE build — the row
whose tag is android-{major}.0.0_r1 — as the canonical build ID for that
version (e.g. Android 14 -> UP1A.231005.007). This is the same one-build-
per-OS approach as the reference generators: most OEM user-agents print
exactly this platform build ID in their Build/ slot.

Applies that build to every android row's builds{} for each of its
osVersions. Writes a PROPOSAL only; evidence per row/version carries
{source_url, quoted_text (the table row), fetched_at, lane}.

Honest limits, stated in the report: these are Google's canonical
platform builds, not each OEM's firmware string; Pixel device-specific
builds live on developers.google.com/android/images, which is
JavaScript-rendered and needs the browser lane (follow-up).

Usage mirrors collect_ipsw_builds.py. Exit 2 if zero builds collected.
"""
import argparse
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

URL = "https://source.android.com/docs/setup/reference/build-numbers"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36"
BUILD_RE = re.compile(r"\b[A-Z]{2}\d[A-Z]\.\d{6}\.\d{3}(?:\.[A-Z]\d)?\b")
TAG_RE = re.compile(r"android-(\d+)\.0\.0_r(\d+)")


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", errors="replace")


def strip(html_fragment: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html_fragment)).strip()


def parse_release_builds(html: str) -> dict[str, dict]:
    """major version -> {build, tag, row_text, date} for the _r1 release row."""
    releases: dict[str, dict] = {}
    for row_html in re.findall(r"<tr>(.*?)</tr>", html, flags=re.S):
        text = strip(row_html)
        tag = TAG_RE.search(text)
        build = BUILD_RE.search(text)
        if not tag or not build:
            continue
        major, rev = tag.group(1), int(tag.group(2))
        date_m = re.search(r"\b(20\d{2}-\d{2}-\d{2})\b", text)
        entry = {
            "build": build.group(0),
            "tag": tag.group(0),
            "row_text": text[:300],
            "date": date_m.group(1) if date_m else "",
            "rev": rev,
        }
        cur = releases.get(major)
        if cur is None or rev < cur["rev"]:
            releases[major] = entry
    return releases


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--evidence", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--report-json")
    args = ap.parse_args()

    with open(args.input, encoding="utf-8") as f:
        data = json.load(f)
    rows = data.get("android")
    if not isinstance(rows, list) or not rows:
        print("ERROR: no android rows in input")
        return 1

    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    try:
        html = fetch(URL)
    except Exception as e:  # noqa: BLE001
        print(f"ERROR: source unreachable: {e}")
        return 2
    releases = parse_release_builds(html)
    if not releases:
        print("ERROR: zero release builds parsed — ALARM, treat as FAILED")
        return 2

    evidence = {
        "schemaVersion": 1,
        "source": "Google — Codenames, tags, and build numbers (source.android.com)",
        "fetched_at": stamp,
        "evidence_policy": "one source table row per exact OS version/build pair",
        "devices": {},
    }
    lines = ["# Android build collector — run report", "", f"Fetched at: {stamp}", ""]
    checked = changed = filled = 0
    missing_versions = set()

    for row in rows:
        checked += 1
        builds = dict(row.get("builds") or {})
        dev_ev = {"source_url": URL, "fetched_at": stamp, "builds": []}
        row_changed = False
        for ver in row.get("osVersions") or []:
            rel = releases.get(str(ver))
            if rel is None:
                missing_versions.add(str(ver))
                continue
            if builds.get(str(ver)) != rel["build"]:
                builds[str(ver)] = rel["build"]
                row_changed = True
            filled += 1
            dev_ev["builds"].append({
                "version": str(ver),
                "buildid": rel["build"],
                "source_url": URL,
                "fetched_at": stamp,
                "lane": "platform-canonical (release tag " + rel["tag"] + ")",
                "quoted_text": rel["row_text"],
            })
        row["builds"] = builds
        evidence["devices"][row.get("id")] = dev_ev
        if row_changed:
            changed += 1
        lines.append(f"- {row.get('id')}: builds now {builds}")

    if changed:
        meta = data.get("meta")
        if isinstance(meta, dict):
            today = datetime.now(timezone.utc).date()
            meta["updatedAt"] = today.isoformat()
            dv = str(meta.get("dataVersion", ""))
            m = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})\.(\d+)", dv)
            if m and (int(m.group(1)), int(m.group(2)), int(m.group(3))) == (
                today.year, today.month, today.day
            ):
                meta["dataVersion"] = f"{today:%Y.%m.%d}.{int(m.group(4)) + 1}"
            else:
                meta["dataVersion"] = f"{today:%Y.%m.%d}.1"

    lines += ["", "## Summary",
              f"- checked: {checked}  changed: {changed}  version slots filled: {filled}",
              f"- release builds used: " + ", ".join(
                  f"Android {k} -> {v['build']} ({v['tag']})" for k, v in sorted(releases.items(), key=lambda kv: int(kv[0]))),
              "- lane note: platform-canonical builds from Google's table; OEM firmware "
              "strings and Pixel device-specific builds are NOT claimed here."]
    if missing_versions:
        lines.append(f"- OS versions with no release row found: {sorted(missing_versions)}")

    report = {"fetched_at": stamp,
              "stats": {"checked": checked, "changed": changed, "filled": filled},
              "status": "proposal_ready" if filled else "failed_zero_data",
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
    return 0 if filled else 2


if __name__ == "__main__":
    sys.exit(main())
