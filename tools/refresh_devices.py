#!/usr/bin/env python3
"""Weekly device-data refresh for NEXORA.

What it does (v1):
1. Fetches the current Chrome Stable version for Android from Google's
   Version History API and, when newer, updates meta.versions.chrome.
2. Optionally normalizes raw new-device text into devices.json rows with the
   Cerebras AI API (--ai-input <file>): the AI may only fill the strict row
   schema; rows missing any required field are rejected, never guessed.
3. Bumps meta.dataVersion (YYYY.MM.DD.N) and meta.updatedAt when anything
   changed, and writes refresh-summary.md for the pull request body.

The caller (GitHub Actions) runs tools/validate_devices.py afterwards and
opens a PR. Nothing here merges anything.
"""
import json
import os
import re
import sys
import urllib.request
from datetime import date

DEVICES = "app/src/main/assets/devices.json"
SUMMARY = "refresh-summary.md"
CHROME_API = (
    "https://versionhistory.googleapis.com/v1/chrome/platforms/android/"
    "channels/stable/versions?pageSize=1"
)
REQUIRED_ROW = (
    "id", "brand", "model", "uaModel", "soc", "gpu", "cores",
    "display", "ramGb", "storageGb", "osVersions",
)


def fetch_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "NEXORA-Refresh/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310
        return json.load(resp)


def ver_key(v):
    return tuple(int(x) for x in v.split("."))


def ai_normalize(raw_text):
    """Ask Cerebras to turn raw device text into strict schema rows."""
    key = os.environ.get("CEREBRAS_API_KEY")
    if not key:
        print("WARN: --ai-input given but CEREBRAS_API_KEY is not set; skipping AI step")
        return []
    prompt = (
        "Convert the device information below into a JSON array of rows with "
        "EXACTLY these fields: id (kebab-case), brand, model, uaModel (the "
        "model code as it appears in a user-agent), soc, gpu, cores (int), "
        "display {w,h,dpr}, ramGb (int array), storageGb (int array), "
        "osVersions (string array), builds (object mapping each OS version "
        "to its firmware build ID). Use ONLY facts present in the text. If a "
        "field is not present, omit the whole row. Output JSON only, no prose."
        "\n\n" + raw_text[:12000]
    )
    body = json.dumps({
        "model": "llama-3.3-70b",
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0,
        "response_format": {"type": "json_object"},
    }).encode()
    req = urllib.request.Request(
        "https://api.cerebras.ai/v1/chat/completions",
        data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:  # noqa: S310
        out = json.load(resp)
    content = out["choices"][0]["message"]["content"]
    parsed = json.loads(content)
    if isinstance(parsed, dict):
        parsed = parsed.get("rows") or parsed.get("devices") or []
    rows = []
    for row in parsed if isinstance(parsed, list) else []:
        if all(row.get(f) not in (None, "", []) for f in REQUIRED_ROW):
            row.setdefault("builds", {})
            rows.append(row)
        else:
            print(f"WARN: AI row rejected, missing fields: {row.get('id', '?')}")
    return rows


def main():
    changes = []
    with open(DEVICES, encoding="utf-8") as f:
        data = json.load(f)
    meta = data.setdefault("meta", {})

    # 1. Chrome version
    try:
        latest = fetch_json(CHROME_API)["versions"][0]["version"]
        current = (meta.get("versions") or {}).get("chrome")
        if current and ver_key(latest) > ver_key(current):
            meta.setdefault("versions", {})["chrome"] = latest
            changes.append(f"Chrome version {current} -> {latest}")
        else:
            print(f"Chrome already current ({current})")
    except Exception as e:  # noqa: BLE001
        print(f"WARN: Chrome version fetch failed: {e}")

    # 2. AI-normalized new devices
    if "--ai-input" in sys.argv:
        raw = open(sys.argv[sys.argv.index("--ai-input") + 1], encoding="utf-8").read()
        known = {d["id"] for section in ("android", "ios_emulation") for d in data.get(section, [])}
        for row in ai_normalize(raw):
            if row["id"] in known:
                print(f"WARN: AI row skipped, id already exists: {row['id']}")
                continue
            data.setdefault("android", []).append(row)
            changes.append(f"New device row: {row['id']} ({row['brand']} {row['model']})")

    # 3. Version bump + write
    if changes:
        today = date.today().isoformat()
        dv = str(meta.get("dataVersion", ""))
        m = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})\.(\d+)", dv)
        if m and f"{m.group(1)}-{m.group(2)}-{m.group(3)}" == today:
            new_dv = f"{m.group(1)}.{m.group(2)}.{m.group(3)}.{int(m.group(4)) + 1}"
        else:
            new_dv = today.replace("-", ".") + ".1"
        meta["dataVersion"] = new_dv
        meta["updatedAt"] = today
        with open(DEVICES, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
        # Keep the website's bundled fallback copy in sync (same file, two readers).
        with open("web/devices.json", "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
        print("Changes:", *changes, sep="\n - ")
    else:
        print("No changes")

    with open(SUMMARY, "w", encoding="utf-8") as f:
        f.write("## Device data refresh\n\n")
        if changes:
            f.write("\n".join(f"- {c}" for c in changes) + "\n")
            f.write(f"\nNew dataVersion: {meta.get('dataVersion')}\n")
        else:
            f.write("No changes this run.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
