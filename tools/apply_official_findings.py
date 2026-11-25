#!/usr/bin/env python3
"""Apply manually verified primary-source specs findings to a proposal.

Secondary-only PPI/codename values are retained in evidence as candidates but
are removed from the publishable catalog until a primary source verifies them.
"""
import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

PRIMARY_DOMAINS = {
    "google_pixel_support": {"support.google.com"},
    "google_aosp_pixel_docs": {"source.android.com"},
    "xiaomi_official_specs": {"www.mi.com", "mi.com"},
    "honor_official_specs": {"www.honor.com", "honor.com"},
}


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save(path, obj):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def bump_version(meta):
    now = datetime.now(timezone.utc)
    today = now.date().isoformat()
    current = str(meta.get("dataVersion", ""))
    match = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})\.(\d+)", current)
    if match and "-".join(match.group(i) for i in (1, 2, 3)) == today:
        seq = int(match.group(4)) + 1
    else:
        seq = 1
    meta["dataVersion"] = f"{now:%Y.%m.%d}.{seq}"
    meta["updatedAt"] = today


def validate_source(source_name, url):
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in PRIMARY_DOMAINS.get(source_name, set()):
        raise ValueError(f"unapproved primary source {source_name}: {url}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--findings", required=True)
    ap.add_argument("--secondary-evidence")
    ap.add_argument("--existing-evidence",
                    help="previous specs evidence; preserved so verified primary facts are not lost")
    ap.add_argument("--out", required=True)
    ap.add_argument("--evidence", required=True)
    ap.add_argument("--report", required=True)
    args = ap.parse_args()

    catalog = load(args.input)
    findings = load(args.findings)
    if findings.get("schemaVersion") != 1:
        raise ValueError("official findings schemaVersion must be 1")
    evidence = load(args.secondary_evidence) if args.secondary_evidence else {
        "schemaVersion": 1, "devices": {}
    }
    evidence.setdefault("schemaVersion", 1)
    evidence.setdefault("devices", {})
    if args.existing_evidence and Path(args.existing_evidence).is_file():
        previous = load(args.existing_evidence)
        for device_id, previous_device in previous.get("devices", {}).items():
            current_device = evidence["devices"].setdefault(device_id, {})
            for field_name in ("ppi", "codename", "display_resolution"):
                previous_field = previous_device.get(field_name)
                if not isinstance(previous_field, dict):
                    continue
                current_field = current_device.setdefault(
                    field_name, {"observations": [], "verdict": previous_field.get("verdict", {})}
                )
                current_obs = current_field.setdefault("observations", [])
                seen = {
                    (o.get("source"), o.get("source_url"), str(o.get("value")), o.get("quoted_text"))
                    for o in current_obs if isinstance(o, dict)
                }
                for obs in previous_field.get("observations", []):
                    if not isinstance(obs, dict):
                        continue
                    key = (obs.get("source"), obs.get("source_url"), str(obs.get("value")), obs.get("quoted_text"))
                    if key not in seen:
                        current_obs.append(obs)
                        seen.add(key)
                # Preserve a previously verified primary verdict until a new
                # official finding supersedes it.
                if previous_field.get("verdict", {}).get("status") == "primary":
                    current_field["verdict"] = previous_field["verdict"]

    rows = {r["id"]: r for r in catalog.get("android", []) if isinstance(r, dict) and r.get("id")}
    changes = []
    errors = []
    fetched_at = findings.get("fetched_at")
    datetime.fromisoformat(str(fetched_at).replace("Z", "+00:00"))

    for device_id, row in rows.items():
        item = findings.get("devices", {}).get(device_id, {})
        dev_ev = evidence["devices"].setdefault(device_id, {})
        if item and item.get("model") != row.get("model"):
            errors.append(f"{device_id}: finding model does not match catalog model")
            continue
        for field_name in ("ppi", "codename"):
            field_ev = dev_ev.setdefault(field_name, {"observations": [], "verdict": {"status": "none", "value": None, "sources": []}})
            observations = field_ev.setdefault("observations", [])
            # Explicitly classify legacy observations as secondary unless they
            # were already verified from an approved primary source.
            for obs in observations:
                obs.setdefault("source_class", "secondary")

        # Remove secondary-only values from the publishable catalog, but retain
        # the observation and candidate in evidence for later review.
        ppi_ev = dev_ev["ppi"]
        primary_ppi = [
            o for o in ppi_ev.get("observations", [])
            if o.get("eligible") is True and o.get("source_class") == "primary"
        ]
        old_ppi = row.setdefault("display", {}).get("ppi")
        if old_ppi is not None and not primary_ppi:
            if ppi_ev.get("observations"):
                ppi_ev["verdict"] = {
                    "status": "secondary_only_candidate", "value": None,
                    "candidate_value": old_ppi,
                    "sources": sorted({o.get("source", "unknown") for o in ppi_ev["observations"]}),
                }
            else:
                ppi_ev["verdict"] = {
                    "status": "unverified_existing_value", "value": None,
                    "candidate_value": old_ppi, "sources": [],
                }
            row["display"]["ppi"] = None
            changes.append(f"{device_id}: removed unverified PPI {old_ppi!r} from publishable catalog")

        codename_ev = dev_ev["codename"]
        primary_code = [
            o for o in codename_ev.get("observations", [])
            if o.get("eligible") is True and o.get("source_class") == "primary"
        ]
        old_code = row.get("codename")
        if old_code and not primary_code:
            codename_ev["verdict"] = {
                "status": "secondary_only_candidate", "value": None,
                "candidate_value": old_code,
                "sources": sorted({o.get("source", "unknown") for o in codename_ev["observations"]}),
            }
            row["codename"] = None
            changes.append(f"{device_id}: removed unverified codename {old_code!r} from publishable catalog")

        # Apply primary PPI.
        ppi = item.get("ppi")
        if ppi:
            source_name = ppi["source"]
            url = findings["sources"][source_name]
            validate_source(source_name, url)
            value = ppi["value"]
            if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
                errors.append(f"{device_id}: invalid PPI value")
                continue
            identity = {
                "model_text": f"{row.get('brand', '')} {row['model']}".strip(),
                "resolution": [row["display"]["w"], row["display"]["h"]],
                "method": "official_product_specs_model_match",
            }
            obs = {
                "source": source_name, "source_class": "primary",
                "source_url": url, "quoted_text": ppi["quote"],
                "fetched_at": fetched_at, "value": value,
                "eligible": True, "identity": identity,
            }
            existing = next((o for o in ppi_ev["observations"]
                             if o.get("source") == source_name and o.get("value") == value), None)
            if existing is None:
                ppi_ev["observations"].append(obs)
            else:
                existing.update(obs)
            if row["display"].get("ppi") != value:
                changes.append(f"{device_id}: PPI {row['display'].get('ppi')!r} -> {value!r}")
            row["display"]["ppi"] = value
            ppi_ev["verdict"] = {
                "status": "primary", "value": value, "sources": [source_name],
                "secondary_dissent": None,
            }

        # Apply official codename.
        codename = item.get("codename")
        if codename:
            source_name = codename["source"]
            url = findings["sources"][source_name]
            validate_source(source_name, url)
            value = codename["value"]
            obs = {
                "source": source_name, "source_class": "primary",
                "source_url": url, "quoted_text": codename["quote"],
                "fetched_at": fetched_at, "value": value,
                "eligible": True,
                "identity": {
                    "model_text": f"{row.get('brand', '')} {row['model']}".strip(), "resolution": None,
                    "method": "official_aosp_device_mapping",
                },
            }
            existing = next((o for o in codename_ev["observations"]
                             if o.get("source") == source_name and o.get("value") == value), None)
            if existing is None:
                codename_ev["observations"].append(obs)
            else:
                existing.update(obs)
            if row.get("codename") != value:
                changes.append(f"{device_id}: codename {row.get('codename')!r} -> {value!r}")
            row["codename"] = value
            codename_ev["verdict"] = {
                "status": "primary", "value": value, "sources": [source_name],
            }

        # Correct official resolution where the catalog disagrees.
        display = item.get("display")
        if display:
            source_name = display["source"]
            url = findings["sources"][source_name]
            validate_source(source_name, url)
            new_w, new_h = display["w"], display["h"]
            old_res = [row["display"]["w"], row["display"]["h"]]
            if old_res != [new_w, new_h]:
                changes.append(f"{device_id}: resolution {old_res[0]}x{old_res[1]} -> {new_w}x{new_h}")
                row["display"]["w"], row["display"]["h"] = new_w, new_h
            dev_ev["display_resolution"] = {
                "observations": [{
                    "source": source_name, "source_class": "primary",
                    "source_url": url, "quoted_text": display["quote"],
                    "fetched_at": fetched_at, "value": {"w": new_w, "h": new_h},
                    "eligible": True,
                    "identity": {
                        "model_text": f"{row.get('brand', '')} {row['model']}".strip(), "resolution": [new_w, new_h],
                        "method": "official_product_specs_model_match",
                    },
                }],
                "verdict": {"status": "primary", "value": {"w": new_w, "h": new_h}, "sources": [source_name]},
            }

    if errors:
        raise ValueError("\n".join(errors))

    # Preserve evidence for all devices and ensure all third-party observations
    # are explicitly secondary.
    for device_id, dev_ev in evidence["devices"].items():
        for field in ("ppi", "codename"):
            f = dev_ev.get(field)
            if isinstance(f, dict):
                for obs in f.get("observations", []):
                    obs.setdefault("source_class", "secondary")

    evidence["source"] = "NEXORA specs evidence: primary official findings plus secondary discovery observations"
    evidence["evidence_policy"] = (
        "Primary official source required for publishable catalog values. "
        "Third-party values remain candidates; identity must be independently verified."
    )

    if changes:
        bump_version(catalog.setdefault("meta", {}))
    save(args.out, catalog)
    save(args.evidence, evidence)

    report = [
        "# Official specs findings application",
        "",
        f"Findings fetched_at: {fetched_at}",
        f"Changes: {len(changes)}",
        "",
        *[f"- {c}" for c in changes],
        "",
        "## Remaining policy",
        "- Secondary-only PPI/codename observations remain in evidence as candidates.",
        "- They are not published to the catalog.",
        "- Missing values remain null until a primary official source is found.",
    ]
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text("\n".join(report) + "\n", encoding="utf-8")
    print(f"applied={len(changes)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
