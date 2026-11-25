#!/usr/bin/env python3
"""Build an evidence-preserving NEXORA in-app UA template proposal.

This collector is deliberately input-driven. It does not invent templates,
turn candidate scripts into observed strings, or promote records to verified.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

STATUSES = {"candidate", "pending", "verified", "outdated", "rejected"}
STATUS_RANK = {"candidate": 0, "pending": 1, "verified": 2, "outdated": 3, "rejected": 4}
PLATFORMS = {"android", "ios"}


def load_json(path: str | Path) -> Any:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: str | Path, value: Any) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def bump_version(current: str, today: date) -> str:
    prefix = today.strftime("%Y.%m.%d.")
    if current.startswith(prefix):
        try:
            return prefix + str(int(current.rsplit(".", 1)[1]) + 1)
        except ValueError:
            pass
    return prefix + "1"


def evidence_key(item: dict[str, Any]) -> tuple[str, str, str]:
    return (
        str(item.get("source_id") or ""),
        str(item.get("source_url") or item.get("source_ref") or ""),
        str(item.get("observed") or item.get("quoted_text") or ""),
    )


def merge_evidence(old: list[dict[str, Any]], new: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    positions: dict[tuple[str, str, str], int] = {}
    for item in [*old, *new]:
        if not isinstance(item, dict):
            continue
        key = evidence_key(item)
        if key not in positions:
            positions[key] = len(result)
            result.append(dict(item))
            continue
        current = result[positions[key]]
        for field, value in item.items():
            if field not in current or current[field] in (None, ""):
                current[field] = value
    return result


def merge_record(old: dict[str, Any] | None, incoming: dict[str, Any]) -> dict[str, Any]:
    row = dict(old or {})
    new_status = str(incoming.get("status") or "candidate")
    old_status = str(row.get("status") or "candidate")

    # Verified records cannot be replaced by lower-trust or lower-status input.
    if old and old_status == "verified" and new_status != "verified":
        row["evidence"] = merge_evidence(row.get("evidence", []), incoming.get("evidence", []))
        row.setdefault("review_notes", []).append(
            f"Ignored status downgrade from {new_status}; verified record preserved."
        )
        return row

    for key, value in incoming.items():
        if key == "evidence":
            continue
        if key == "review_notes":
            row[key] = list(dict.fromkeys([*(row.get(key) or []), *(value or [])]))
        else:
            row[key] = value
    row["evidence"] = merge_evidence(row.get("evidence", []), incoming.get("evidence", []))
    return row


def build_proposal(
    devices: dict[str, Any],
    findings: dict[str, Any],
    existing: dict[str, Any] | None = None,
    today: date | None = None,
) -> tuple[dict[str, Any], dict[str, Any], str]:
    today = today or datetime.now(timezone.utc).date()
    apps = devices.get("meta", {}).get("apps", {})
    if not isinstance(apps, dict) or not apps:
        raise ValueError("devices catalog has no meta.apps object")

    incoming = findings.get("records", [])
    if not isinstance(incoming, list):
        raise ValueError("findings.records must be an array")

    records_by_id: dict[str, dict[str, Any]] = {}
    for source in ((existing or {}).get("records", []) or []):
        if isinstance(source, dict) and source.get("id"):
            records_by_id[str(source["id"])] = dict(source)

    rejected: list[dict[str, str]] = []
    for index, raw in enumerate(incoming):
        if not isinstance(raw, dict):
            rejected.append({"finding": str(index), "reason": "record is not an object"})
            continue
        row = dict(raw)
        rid = str(row.get("id") or "").strip()
        app = str(row.get("app") or "").strip()
        platform = str(row.get("platform") or "").strip()
        status = str(row.get("status") or "candidate").strip()
        if not rid or not app or app not in apps:
            rejected.append({"finding": rid or str(index), "reason": "missing id or app not in devices.meta.apps"})
            continue
        if platform not in PLATFORMS:
            rejected.append({"finding": rid, "reason": f"unsupported platform {platform!r}"})
            continue
        if status not in STATUSES:
            rejected.append({"finding": rid, "reason": f"unsupported status {status!r}"})
            continue
        row.setdefault("schemaVersion", 1)
        row.setdefault("slots", {})
        row.setdefault("evidence", [])
        records_by_id[rid] = merge_record(records_by_id.get(rid), row)

    records = sorted(records_by_id.values(), key=lambda r: (r.get("app", ""), r.get("platform", ""), r.get("id", "")))
    coverage: list[dict[str, Any]] = []
    for app in sorted(apps):
        app_records = [r for r in records if r.get("app") == app]
        platform_status = {}
        for platform in ("android", "ios"):
            rows = [r for r in app_records if r.get("platform") == platform]
            if any(r.get("status") == "verified" for r in rows):
                state = "verified"
            elif any(r.get("status") == "pending" for r in rows):
                state = "pending"
            elif any(r.get("status") == "candidate" for r in rows):
                state = "candidate"
            elif any(r.get("status") == "outdated" for r in rows):
                state = "outdated"
            else:
                state = "not-collected"
            platform_status[platform] = state
        coverage.append({"app": app, "platforms": platform_status})

    previous_version = str((existing or {}).get("dataVersion") or "")
    data_version = bump_version(previous_version, today)
    proposal = {
        "uaTemplatesSchema": 1,
        "dataVersion": data_version,
        "updatedAt": today.isoformat(),
        "deviceCatalogVersion": devices.get("meta", {}).get("dataVersion"),
        "sourcePolicy": "Observed strings and candidate source formats remain distinct; collector never promotes status.",
        "coverage": coverage,
        "records": records,
        "sources": findings.get("sources", (existing or {}).get("sources", [])),
    }
    counts = Counter(r.get("status", "unknown") for r in records)
    summary = {
        "status": "proposal_ready" if records else "no_records",
        "dataVersion": data_version,
        "deviceCatalogVersion": proposal["deviceCatalogVersion"],
        "apps": len(apps),
        "records": len(records),
        "by_status": dict(sorted(counts.items())),
        "coverage": {coverage_state: sum(
            1 for app_row in coverage for platform_state in app_row["platforms"].values()
            if platform_state == coverage_state
        ) for coverage_state in ("verified", "pending", "candidate", "outdated", "not-collected")},
        "rejected_findings": rejected,
        "collection_gaps": findings.get("collectionGaps", []),
    }
    report = "\n".join([
        "# NEXORA UA template collection report",
        "",
        f"- Status: {summary['status']}",
        f"- Proposal version: {data_version}",
        f"- Device catalog version: {proposal['deviceCatalogVersion']}",
        f"- Apps in source catalog: {len(apps)}",
        f"- Template records: {len(records)}",
        "- Status counts: " + ", ".join(f"{k}={v}" for k, v in summary["by_status"].items()),
        "- Coverage cells: " + ", ".join(f"{k}={v}" for k, v in summary["coverage"].items()),
        f"- Rejected findings: {len(rejected)}",
        "",
        "## Collection gaps",
        *([f"- {gap}" for gap in summary["collection_gaps"]] or ["- None reported."]),
        "",
        "The collector does not promote candidates or pending records to verified.",
        "Only validate_ua_templates.py can report whether a manually reviewed verified record meets the evidence gate.",
    ])
    return proposal, summary, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--devices", required=True, help="published devices.json; read-only")
    parser.add_argument("--findings", required=True, help="normalized findings JSON")
    parser.add_argument("--existing", help="previous UA template proposal/catalog to merge")
    parser.add_argument("--out", required=True, help="proposal output JSON")
    parser.add_argument("--report", required=True, help="Markdown report output")
    parser.add_argument("--report-json", help="optional JSON report output")
    args = parser.parse_args()
    try:
        devices = load_json(args.devices)
        findings = load_json(args.findings)
        existing = load_json(args.existing) if args.existing else None
        proposal, summary, report = build_proposal(devices, findings, existing)
        write_json(args.out, proposal)
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(report + "\n", encoding="utf-8")
        if args.report_json:
            write_json(args.report_json, summary)
        print(report)
        return 0 if summary["records"] else 2
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
