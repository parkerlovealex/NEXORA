#!/usr/bin/env python3
"""NEXORA Android specs collector.

Collects PPI and codename observations from public reference pages. Third-party
sites (DeviceSpecifications, PhoneDB, GSMArena) are discovery-only secondary
sources. They are retained as candidates and never populate canonical fields.
Only observations explicitly classified as primary official sources may be
merged into devices.json.

Identity checks are performed against content returned by the page, never its
generated search URL or the mere presence of a quote. Requests use ordinary
HTTP fetching with rate limits; anti-bot challenges are reported, not bypassed.

Writes a proposal and evidence files only. It never edits the live catalog.
"""
import argparse
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

DS_BASE = "https://www.devicespecifications.com"
PDB_BASE = "https://phonedb.net"
GSM_BASE = "https://m.gsmarena.com"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36"

_fetcher = None


def _load_scrapling():
    global _fetcher
    if _fetcher is None:
        from scrapling.fetchers import Fetcher
        _fetcher = Fetcher
    return _fetcher


def http_get(url: str) -> str:
    """Ordinary, rate-limited HTTP fetch. Block pages are reported, not bypassed."""
    Fetcher = _load_scrapling()
    r = Fetcher.get(url, timeout=30)
    body = r.html_content
    if isinstance(body, bytes):
        body = body.decode("utf-8", "replace")
    if r.status and r.status >= 400:
        raise RuntimeError(f"HTTP {r.status} for {url}")
    if not body or len(body.strip()) < 80:
        raise RuntimeError(f"empty or challenge response for {url}")
    return body


def strip(fragment: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", fragment)).strip()


def norm(s: str) -> str:
    s = re.sub(r"([a-z])(\d)", r"\1 \2", (s or "").lower())
    s = re.sub(r"(\d)([a-z])", r"\1 \2", s)
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


# --------------------------------------------------------------------------
# DeviceSpecifications adapter
# --------------------------------------------------------------------------

def ds_autocomplete(query: str, cache: "Cache") -> list[dict]:
    """Site autocomplete -> [{name, url}] (payload is non-strict JSON,
    so results are extracted by regex from the raw body)."""
    url = (DS_BASE + "/index.php?action=search&language=en&search="
           + re.sub(r"\s+", "+", query.strip()))
    raw = cache.fetch("ds", "ac_" + re.sub(r"[^A-Za-z0-9]+", "_", query),
                      lambda: http_get(url))
    out = []
    for m in re.finditer(r"model\\*/([a-f0-9]{8})", raw):
        pre = raw[max(0, m.start() - 900):m.start()]
        name = ""
        idx = pre.rfind("line-height: 40px;")
        if idx >= 0:
            seg = pre[idx:]
            q = seg.find('">')
            if q >= 0:
                name = seg[q + 2:].split("&lt;")[0].strip()
        out.append({"name": name,
                    "url": DS_BASE + "/en/model/" + m.group(1)})
    return out


def ds_parse_page(html: str) -> dict:
    def cell(label):
        m = re.search(r"<td>" + re.escape(label) + r"(?:<p>.*?</p>)?</td><td>(.*?)</td>",
                      html, re.S)
        return strip(m.group(1)) if m else None

    ppi = None
    pd = cell("Pixel density")
    if pd:
        m = re.search(r"(\d+)\s*ppi", pd)
        if m:
            ppi = int(m.group(1))
    res = None
    rv = cell("Resolution")
    if rv:
        m = re.search(r"(\d+)\s*x\s*(\d+)", rv)
        if m:
            res = (int(m.group(1)), int(m.group(2)))
    title = re.search(r"<title>(.*?)</title>", html, re.S)
    return {"alias": cell("Model alias"), "model": cell("Model"),
            "ppi": ppi, "res": res,
            "title": strip(title.group(1)) if title else ""}


def name_score(cand_name: str, row: dict) -> int:
    """How well a candidate display name matches the row's model."""
    cn = norm(cand_name)
    brand, model = norm(row["brand"]), norm(row["model"])
    full = model if model.startswith(brand) else f"{brand} {model}"
    bare = re.sub(r"^(samsung|google|xiaomi|oneplus|motorola|oppo|vivo|honor|realme|tecno|infinix|nothing)\s+", "", full)
    bare = re.sub(r"^moto\s+", "", bare)
    score = 0
    if cn == full or cn == bare:
        score = 3
    elif cn.startswith(full) or cn.startswith(bare):
        score = 2
    elif bare and bare in cn:
        score = 1
    return score


FILLER = {"5g", "4g", "lte", "td"}


def toks(x: str) -> list:
    """Name tokens with filler words (5G/4G/LTE) removed BEFORE norm(),
    so norm's letter<->digit splitting cannot break them apart."""
    x = re.sub(r"\b(5g|4g|lte)\b", " ", (x or "").lower())
    return [t for t in norm(x).split() if t not in FILLER]


def exact_name(cand_name: str, row: dict) -> bool:
    """True when the candidate name is exactly the row's model (ignoring
    filler tokens like 5G) — a sibling suffix (Plus/Pro/Ultra/Neo/SE...)
    makes it a different device and returns False."""
    cn = toks(cand_name)
    brand, model = norm(row["brand"]), norm(row["model"])
    full = model if model.startswith(brand) else f"{brand} {model}"
    bare = re.sub(r"^(samsung|google|xiaomi|oneplus|motorola|oppo|vivo|honor|realme|tecno|infinix|nothing)\s+", "", full)
    bare = re.sub(r"^moto\s+", "", bare)
    return (cn == toks(full) or cn == toks(bare) or cn == toks(model)
            or cn == toks(f"{brand} {bare}"))


def ds_observe(row: dict, cache: "Cache") -> dict | None:
    """Return a verified DS observation for the row, or None.
    Observation: {source, source_url, ppi, res, alias, model, eligible,
    quote} — eligible only when identity is verified."""
    ua = row["uaModel"]
    queries = []
    if re.search(r"\d", ua) and " " not in ua.strip():
        queries.append(ua)                                # model code
    full = row["model"] if norm(row["model"]).startswith(norm(row["brand"])) \
        else f"{row['brand']} {row['model']}"
    queries += [full, row["model"], full + " 5G"]
    seen, candidates = set(), []
    for q in queries:
        for it in ds_autocomplete(q, cache):
            if it["url"] not in seen:
                seen.add(it["url"])
                candidates.append(it)
    candidates.sort(key=lambda it: (
        -name_score(it["name"], row), len(toks(it["name"])),
        len(norm(it["name"]))))
    best = None        # strongest eligible observation
    fallback = None    # first ineligible observation that still has a value
    for it in candidates[:6]:
        mid = it["url"].rstrip("/").split("/")[-1]
        html = cache.fetch("ds", f"model_{mid}",
                           lambda u=it["url"]: http_get(u))
        p = ds_parse_page(html)
        alias_hit = bool(p["alias"] and ua.lower() in p["alias"].lower())
        res_hit = p["res"] == (row["display"]["w"], row["display"]["h"])
        name_hit = exact_name(it["name"], row) or exact_name(p["title"], row)
        eligible = alias_hit or (name_hit and res_hit)
        strength = (2 if alias_hit else 0) + (1 if name_hit and res_hit else 0)
        identity_method = ("ua_model_alias" if alias_hit else
                           "exact_model_and_resolution" if name_hit and res_hit else
                           "unverified")
        obs = {"source": "devicespecifications", "source_class": "secondary",
               "source_url": it["url"], "value": p["ppi"], "res": p["res"],
               "alias": p["alias"], "page_name": it["name"], "eligible": eligible,
               "identity": {"model_text": p["title"] or it["name"],
                            "alias_text": p["alias"],
                            "resolution": list(p["res"]) if p["res"] else None,
                            "method": identity_method},
               "quote": (f"Model alias: {p['alias']}; Resolution: "
                         f"{p['res'][0]} x {p['res'][1]} pixels; "
                         f"Pixel density: {p['ppi']} ppi"
                         if p["ppi"] and p["res"] else
                         f"page parsed: alias={p['alias']!r} ppi={p['ppi']}")}
        if eligible and p["ppi"]:
            if best is None or strength > best[0]:
                best = (strength, obs)
        elif fallback is None and p["ppi"]:
            fallback = obs
    if best is not None:
        return best[1]
    return fallback


# --------------------------------------------------------------------------
# Page cache (politeness + reproducibility)
# --------------------------------------------------------------------------

class Cache:
    def __init__(self, root: Path, delay: float):
        self.root = root
        self.delay = delay
        root.mkdir(parents=True, exist_ok=True)

    def fetch(self, source: str, key: str, getter) -> str:
        f = self.root / source / (re.sub(r"[^A-Za-z0-9_.-]+", "_", key) + ".html")
        if f.exists():
            return f.read_text(encoding="utf-8", errors="replace")
        f.parent.mkdir(parents=True, exist_ok=True)
        body = getter()
        f.write_text(body, encoding="utf-8")
        time.sleep(self.delay)
        return body


# --------------------------------------------------------------------------
# PhoneDB adapter — ordinary HTTP fetch where reachable, with an optional
# findings import for the blocked lane. Search URLs are never identity proof.
# A blocked response is recorded; anti-bot challenges are not bypassed.
#
# PhoneDB datasheet pages print, per variant:
#   Codename: "<Brand> <codename>"   (e.g. "Samsung Muse 1",
#   "Google Husky", "Xiaomi Houji") — the manufacturer codename; for
#   Google/Xiaomi it is also the Android device codename.
#   Resolution: "1080x2340";  Pixel Density: "419 PPI" (PhoneDB computes
#   this from its own measured diagonal, so it can differ by a few ppi
#   from the manufacturer figure — the 2-of-3 rule arbitrates).
# --------------------------------------------------------------------------

def pdb_parse_page(html: str) -> dict:
    text = strip(html)

    def grab(label, pat):
        m = re.search(re.escape(label) + r"\s*:?\s*" + pat, text)
        return m.group(1) if m else None

    codename = grab("Codename", r"([A-Z][A-Za-z0-9][A-Za-z0-9 .+-]{0,38}?)(?:\s{2,}| OEM ID| General Extras| Device Category|$)")
    ppi_s = grab("Pixel Density", r"(\d+)\s*PPI")
    res = None
    rm = re.search(r"Resolution\s*:?\s*(\d{3,4})x(\d{3,4})\b", text)
    if rm:
        res = (int(rm.group(1)), int(rm.group(2)))
    model = grab("Model", r"(.{3,80}?)(?:\s{2,}| Brief| Released|$)")
    return {"codename_field": codename.strip() if codename else None,
            "ppi": int(ppi_s) if ppi_s else None, "res": res,
            "model": model}


def pdb_live_observe(row: dict, cache: "Cache") -> dict | None:
    """Live attempt: PhoneDB search in a real browser, then the first
    matching variant's detailed_specs page. Raises on blocked networks
    (the caller records it as a source error)."""
    q = re.sub(r"\s+", "+", f"{row['brand']} {row['model']} {row['uaModel']}")
    search_url = f"{PDB_BASE}/index.php?m=device&s=query&q={q}"
    html = cache.fetch("pdb", f"search_{row['id']}",
                       lambda: http_get(search_url))
    links = re.findall(r'href="(index\.php\?m=device&id=\d+&c=[^"]+&d=detailed_specs)"', html)
    if not links:
        links = re.findall(r'href="(index\.php\?m=device&id=\d+&c=[^"]+)"', html)
    if not links:
        return None
    page_url = PDB_BASE + "/" + links[0].replace("&amp;", "&")
    page = cache.fetch("pdb", f"page_{row['id']}", lambda: http_get(page_url))
    p = pdb_parse_page(page)
    return pdb_obs_from(row, page_url, p)


def pdb_obs_from(row: dict, url: str, p: dict) -> dict | None:
    if not p:
        return None
    if isinstance(p.get("res"), list):
        p = {**p, "res": tuple(p["res"])}
    model_seen = p.get("model") or ""
    ua = row["uaModel"]
    ua_match = bool(ua and ua.lower() in model_seen.lower())
    exact_model_match = bool(model_seen and exact_name(model_seen, row))
    identity = ua_match or exact_model_match
    identity_method = "ua_model_in_page_model" if ua_match else (
        "exact_model_name" if exact_model_match else "unverified"
    )
    resolution = list(p["res"]) if p.get("res") else None
    out = {}
    if p.get("codename_field"):
        codename = p["codename_field"]
        for brand in (row["brand"], "BBK"):
            if codename.lower().startswith(brand.lower() + " "):
                codename = codename[len(brand) + 1:]
                break
        out["codename_obs"] = {
            "source": "phonedb", "source_class": "secondary",
            "source_url": url, "value": codename.strip(),
            "eligible": bool(identity),
            "identity": {"model_text": model_seen, "resolution": resolution,
                         "method": identity_method},
            "quote": f"Codename: {p['codename_field']}; Model: {model_seen}"}
    if p.get("ppi"):
        res_hit = p.get("res") == (row["display"]["w"], row["display"]["h"])
        out["ppi_obs"] = {
            "source": "phonedb", "source_class": "secondary",
            "source_url": url, "value": p["ppi"],
            "eligible": bool(identity and (res_hit or p.get("res") is None)),
            "identity": {"model_text": model_seen, "resolution": resolution,
                         "method": identity_method},
            "quote": (f"Resolution: {p['res'][0]}x{p['res'][1]}; "
                      f"Pixel Density: {p['ppi']} PPI"
                      if p.get("res") else f"Pixel Density: {p['ppi']} PPI")}
    return out or None


def pdb_observe(row: dict, cache: "Cache", findings: dict | None,
                live: bool = False) -> dict | None:
    if findings is not None and row["id"] in findings:
        f = findings[row["id"]]
        p = {"codename_field": f.get("codename_field"),
             "ppi": f.get("ppi"), "res": f.get("res"),
             "model": f.get("model_seen")}
        return pdb_obs_from(row, f["url"], p)
    if not live:
        return None
    return pdb_live_observe(row, cache)


# --------------------------------------------------------------------------
# GSMArena adapter — ordinary HTTP only, plus an optional findings import.
# A challenge or block is reported rather than bypassed. GSMArena prints,
# page: "1080 x 2340 pixels, 19.5:9 ratio (~416 ppi density)".
# Corroboration only — kept low-volume by design.
# --------------------------------------------------------------------------

def gsm_parse_page(html: str) -> dict:
    text = strip(html)
    ppi = None
    m = re.search(r"\(~(\d+)\s*ppi density\)", text) or re.search(r"(\d+)\s*ppi density", text)
    if m:
        ppi = int(m.group(1))
    res = None
    rm = re.search(r"(\d{3,4}) x (\d{3,4}) pixels", text)
    if rm:
        res = (int(rm.group(1)), int(rm.group(2)))
    title = re.search(r"<title>(.*?)</title>", html, re.S)
    return {"ppi": ppi, "res": res,
            "title": strip(title.group(1)) if title else ""}


def gsm_obs_from(row: dict, url: str, p: dict, quote: str | None) -> dict | None:
    if not p or not p.get("ppi"):
        return None
    if isinstance(p.get("res"), list):
        p = {**p, "res": tuple(p["res"])}
    res_hit = p.get("res") == (row["display"]["w"], row["display"]["h"])
    model_hit = exact_name(p.get("title", ""), row)
    identity = bool(model_hit and res_hit)
    return {"source": "gsmarena", "source_class": "secondary",
            "source_url": url, "value": p["ppi"], "eligible": identity,
            "identity": {"model_text": p.get("title", ""),
                         "resolution": list(p["res"]) if p.get("res") else None,
                         "method": "exact_model_and_resolution" if identity else "unverified"},
            "quote": quote or (f"Model: {p.get('title', '')}; "
                               f"Resolution: {p['res'][0]} x {p['res'][1]} pixels; "
                               f"(~{p['ppi']} ppi density)" if p.get("res")
                               else f"~{p['ppi']} ppi density")}


def gsm_observe(row: dict, cache: "Cache", findings: dict | None,
                live: bool = False) -> dict | None:
    if findings is not None and not live:
        if not (findings.get(row["id"]) or {}).get("gsm_ppi"):
            return None
    if findings and row["id"] in findings and findings[row["id"]].get("gsm_ppi"):
        f = findings[row["id"]]
        return gsm_obs_from(row, f.get("gsm_url") or f["url"],
                            {"ppi": f["gsm_ppi"], "res": f.get("gsm_res"),
                             "title": f.get("model_seen", "")},
                            f.get("gsm_quote"))
    if not live:
        return None
    # Live attempt (low volume: one search + one page, cached).
    q = re.sub(r"\s+", "+", f"{row['brand']} {row['model']}")
    search_url = f"{GSM_BASE}/results.php3?sQuickSearch=yes&sName={q}"
    html = cache.fetch("gsm", f"search_{row['id']}",
                       lambda: http_get(search_url))
    links = re.findall(r'href="([a-z0-9_]+-\d+\.php)"', html)
    if not links:
        return None
    page_url = GSM_BASE + "/" + links[0]
    page = cache.fetch("gsm", f"page_{row['id']}", lambda: http_get(page_url))
    return gsm_obs_from(row, page_url, gsm_parse_page(page), None)


# --------------------------------------------------------------------------
# Merge — primary official evidence can publish. Secondary observations
# remain candidates regardless of how many secondary sources agree; they
# cannot become primary by repetition. Conflicting primary sources are held.
# --------------------------------------------------------------------------

def merge_field(observations: list[dict]) -> dict:
    """Accept primary evidence; retain secondary-only observations as candidates."""
    eligible = [o for o in observations if o and o.get("eligible") and o.get("value") is not None]
    if not eligible:
        return {"status": "none", "value": None, "sources": []}

    primary = [o for o in eligible if o.get("source_class") == "primary"]
    secondary = [o for o in eligible if o.get("source_class") != "primary"]

    if primary:
        by_value: dict = {}
        for obs in primary:
            by_value.setdefault(obs["value"], []).append(obs["source"])
        if len(by_value) > 1:
            return {
                "status": "primary_conflict", "value": None,
                "sources": sorted({o["source"] for o in primary}),
                "values": {str(k): sorted(set(v)) for k, v in by_value.items()},
            }
        value, sources = next(iter(by_value.items()))
        secondary_dissent = {
            str(v): sorted({o["source"] for o in secondary if o["value"] == v})
            for v in {o["value"] for o in secondary if o["value"] != value}
        }
        return {
            "status": "primary", "value": value,
            "sources": sorted(set(sources)),
            "secondary_dissent": secondary_dissent or None,
        }

    by_value: dict = {}
    for obs in secondary:
        by_value.setdefault(obs["value"], set()).add(obs["source"])
    ranked = sorted(by_value.items(), key=lambda kv: -len(kv[1]))
    candidate_value, candidate_sources = ranked[0]
    status = "corroborated_secondary_candidate" if len(candidate_sources) >= 2 else "single_source_candidate"
    return {
        "status": status,
        "value": None,
        "candidate_value": candidate_value,
        "sources": sorted(candidate_sources),
        "values": {str(k): sorted(v) for k, v in by_value.items()},
    }


def bump_meta(data: dict) -> None:
    meta = data.get("meta")
    if not isinstance(meta, dict):
        return
    today = datetime.now(timezone.utc).date()
    meta["updatedAt"] = today.isoformat()
    dv = str(meta.get("dataVersion", ""))
    m = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})\.(\d+)", dv)
    if m and (int(m.group(1)), int(m.group(2)), int(m.group(3))) == (
            today.year, today.month, today.day):
        meta["dataVersion"] = f"{today:%Y.%m.%d}.{int(m.group(4)) + 1}"
    else:
        meta["dataVersion"] = f"{today:%Y.%m.%d}.1"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--evidence", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--report-json")
    ap.add_argument("--only", help="comma-separated device ids (default: all)")
    ap.add_argument("--cache-dir", default="/tmp/specs-cache")
    ap.add_argument("--delay", type=float, default=1.5,
                    help="seconds between live fetches (politeness)")
    ap.add_argument("--sources", default="ds,pdb,gsm",
                    help="comma list of sources to use: ds,pdb,gsm")
    ap.add_argument("--findings",
                    help="findings JSON for the blocked lanes (phonedb/gsmarena "
                         "values read through a working lane, with quotes)")
    ap.add_argument("--live-blocked", action="store_true",
                    help="attempt ordinary live HTTP fetches for PhoneDB/GSMArena; "
                         "no stealth or anti-bot challenge bypass is used")
    ap.add_argument("--fail-on-source-errors", action="store_true",
                    help="return exit code 3 when any source is unreachable")
    args = ap.parse_args()

    if "SCRAPLING_PROXY" not in os.environ and not os.environ.get("http_proxy"):
        pass  # Leave this source as blocked; do not bypass the challenge.

    with open(args.input, encoding="utf-8") as f:
        data = json.load(f)
    rows = data.get("android")
    if not isinstance(rows, list) or not rows:
        print("ERROR: no android rows in input")
        return 1
    findings = None
    if args.findings:
        with open(args.findings, encoding="utf-8") as f:
            findings = json.load(f).get("devices", {})
    only = {s.strip() for s in args.only.split(",")} if args.only else None
    use = {s.strip() for s in args.sources.split(",")}

    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    cache = Cache(Path(args.cache_dir), args.delay)
    evidence = {"schemaVersion": 1,
                "source": "specs lane: devicespecifications + phonedb + gsmarena",
                "fetched_at": stamp,
                "evidence_policy": "per-field source observations with quotes; "
                                   "2-source agreement preferred, conflicts stored nowhere",
                "devices": {}}
    lines = ["# Specs collector — run report", "", f"Fetched at: {stamp}", ""]
    stats = {"checked": 0, "codename_filled": 0, "ppi_filled": 0,
             "ppi_corroborated": 0, "ppi_single": 0, "conflicts": 0,
             "changed_devices": 0, "changed_fields": 0,
             "source_errors": {}, "source_successes": {}}
    device_reports = []

    for row in rows:
        if only and row["id"] not in only:
            continue
        stats["checked"] += 1
        obs = {"ppi": [], "codename": []}
        errs = []
        if "ds" in use:
            try:
                o = ds_observe(row, cache)
                if o:
                    obs["ppi"].append(o)
            except Exception as e:  # noqa: BLE001
                errs.append(f"ds: {type(e).__name__}: {e}")
        if "pdb" in use:
            try:
                o = pdb_observe(row, cache, findings, live=args.live_blocked)
                if o:
                    if o.get("ppi_obs"):
                        obs["ppi"].append(o["ppi_obs"])
                    if o.get("codename_obs"):
                        obs["codename"].append(o["codename_obs"])
            except Exception as e:  # noqa: BLE001
                errs.append(f"pdb: {type(e).__name__}: {e}")
        if "gsm" in use:
            try:
                o = gsm_observe(row, cache, findings, live=args.live_blocked)
                if o:
                    obs["ppi"].append(o)
            except Exception as e:  # noqa: BLE001
                errs.append(f"gsm: {type(e).__name__}: {e}")
        for e in errs:
            src = e.split(":")[0]
            stats["source_errors"][src] = stats["source_errors"].get(src, 0) + 1
        for source_name, source_obs in (
            ("ds", obs["ppi"]),
            ("pdb", [o for o in obs["codename"] + obs["ppi"] if o.get("source") == "phonedb"]),
            ("gsm", [o for o in obs["ppi"] if o.get("source") == "gsmarena"]),
        ):
            if source_obs:
                stats["source_successes"][source_name] = stats["source_successes"].get(source_name, 0) + 1

        ppi_m = merge_field(obs["ppi"])
        cod_m = merge_field(obs["codename"])
        changed = False
        if ppi_m["status"] == "primary":
            if row["display"].get("ppi") != ppi_m["value"]:
                row["display"]["ppi"] = ppi_m["value"]
                changed = True
                stats["changed_fields"] += 1
            stats["ppi_filled"] += 1
            if ppi_m["status"] == "primary":
                stats["ppi_corroborated"] += 1
            else:
                stats["ppi_single"] += 1
        if ppi_m["status"] in ("conflict", "primary_conflict"):
            stats["conflicts"] += 1
        if cod_m["status"] == "primary":
            if row.get("codename") != cod_m["value"]:
                row["codename"] = cod_m["value"]
                changed = True
                stats["changed_fields"] += 1
            stats["codename_filled"] += 1
        if cod_m["status"] in ("conflict", "primary_conflict"):
            stats["conflicts"] += 1

        def slim(o):
            return {"source": o["source"],
                    "source_class": o.get("source_class", "secondary"),
                    "source_url": o["source_url"],
                    "quoted_text": o["quote"], "fetched_at": stamp,
                    "value": o["value"], "eligible": o["eligible"],
                    "identity": o.get("identity")}
        evidence["devices"][row["id"]] = {
            "ppi": {"observations": [slim(o) for o in obs["ppi"] if o],
                    "verdict": ppi_m},
            "codename": {"observations": [slim(o) for o in obs["codename"] if o],
                         "verdict": cod_m},
        }
        if changed:
            stats["changed_devices"] += 1
        device_reports.append({
            "id": row["id"], "ppi": ppi_m, "codename": cod_m,
            "errors": errs, "changed": changed})
        ppi_detail = (
            f" {ppi_m['value']!r} ({', '.join(ppi_m['sources'])})"
            if ppi_m.get("value") is not None else
            (f" candidate={ppi_m.get('candidate_value')!r} "
             f"({', '.join(ppi_m.get('sources', []))})"
             if ppi_m.get("candidate_value") is not None else
             (f" values={ppi_m.get('values')}"
              if ppi_m.get("status") in ("conflict", "primary_conflict") else "")))
        cod_detail = (
            f" {cod_m['value']!r}" if cod_m.get("value") is not None else
            (f" candidate={cod_m.get('candidate_value')!r}"
             if cod_m.get("candidate_value") is not None else ""))
        lines.append(
            f"- {row['id']}: ppi={ppi_m['status']}{ppi_detail}"
            + f" | codename={cod_m['status']}{cod_detail}"
            + (f" | ERRORS: {'; '.join(e[:140] for e in errs)}" if errs else ""))

    filled = stats["codename_filled"] + stats["ppi_filled"]
    candidates = sum(
        1 for device in device_reports
        for field in (device["ppi"], device["codename"])
        if field.get("candidate_value") is not None
    )
    if stats["changed_fields"]:
        bump_meta(data)
    lines += ["", "## Summary",
              f"- checked: {stats['checked']}",
              f"- codename filled from primary sources: {stats['codename_filled']}",
              f"- ppi filled from primary sources: {stats['ppi_filled']}",
              f"- field conflicts (nothing stored): {stats['conflicts']}",
              f"- changed devices: {stats['changed_devices']}",
              f"- changed fields: {stats['changed_fields']}",
              f"- source successes: {stats['source_successes'] or 'none'}",
              f"- secondary-only candidates (not published): {candidates}",
              f"- source errors: {stats['source_errors'] or 'none'}"]
    if filled == 0:
        lines += ["", "## ALARM: zero fields filled — treat this run as FAILED."]
    report = {"fetched_at": stamp, "stats": stats, "devices": device_reports,
              "status": "proposal_ready" if filled else (
                  "candidates_only" if candidates else "failed_zero_data"),
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
    print(json.dumps(stats, sort_keys=True))
    print(f"status={report['status']}")
    if args.fail_on_source_errors and stats["source_errors"]:
        return 3
    return 0 if filled or candidates else 2


if __name__ == "__main__":
    sys.exit(main())
