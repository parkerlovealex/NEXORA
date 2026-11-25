#!/usr/bin/env python3
"""Fetch useragent.in's published index, decoder and In-App Browsers JSON.

The category filename is discovered from index.json. This script deliberately
never guesses the dataset filename. It preserves raw source artifacts before
any AI normalization or schema transformation.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

BASE = "https://useragent.in/"
INDEX_URL = urljoin(BASE, "assets/data/index.json")
DECODER_URL = urljoin(BASE, "assets/js/list.js")
TERMS_URL = urljoin(BASE, "terms")


def fetch(url: str, timeout: int = 25, attempts: int = 3) -> bytes:
    last_error: Exception | None = None
    for attempt in range(attempts):
        request = Request(url, headers={
            "User-Agent": "NEXORA-UA-Research/1.0 (+https://useragent.in/terms)",
            "Accept": "application/json,text/javascript,*/*;q=0.8",
        })
        try:
            with urlopen(request, timeout=timeout) as response:
                return response.read()
        except HTTPError as exc:
            last_error = exc
            if exc.code not in {429, 500, 502, 503, 504} or attempt + 1 >= attempts:
                raise
            retry_after = exc.headers.get("Retry-After", "")
            try:
                delay = min(30, max(1, int(retry_after)))
            except ValueError:
                delay = 2 ** attempt
            time.sleep(delay)
        except (URLError, TimeoutError) as exc:
            last_error = exc
            if attempt + 1 >= attempts:
                raise
            time.sleep(2 ** attempt)
    if last_error:
        raise last_error
    raise RuntimeError("fetch failed without an exception")


def walk(value: Any, path: tuple[str, ...] = ()):
    if isinstance(value, dict):
        yield path, value
        for key, child in value.items():
            yield from walk(child, path + (str(key),))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, path + (str(index),))


def discover_inapp_file(index: Any) -> str | None:
    """Return a file path only when the index explicitly labels it In-App."""
    file_keys = {"file", "filename", "path", "url", "href", "datafile", "json", "src"}
    label_keys = {"name", "label", "title", "category", "id", "slug", "key"}
    candidates: list[tuple[int, str]] = []
    for node_path, row in walk(index):
        if not isinstance(row, dict):
            continue
        labels = (" ".join(node_path) + " " + " ".join(str(row.get(k, "")) for k in label_keys)).lower()
        labels = re.sub(r"[_\s]+", "-", labels)
        if not ("in-app" in labels or "inapp" in labels):
            continue
        for key, value in row.items():
            if str(key).lower() not in file_keys or not isinstance(value, str):
                continue
            candidate = value.strip()
            if not candidate:
                continue
            parsed = urlparse(candidate)
            if parsed.scheme and parsed.netloc and parsed.netloc != "useragent.in":
                continue
            if not (candidate.lower().endswith(".json") or ".json?" in candidate.lower()):
                continue
            score = 2 if "in-app" in labels or "in app" in labels else 1
            candidates.append((score, candidate))
    if not candidates:
        return None
    candidates.sort(reverse=True)
    unique = list(dict.fromkeys(item[1] for item in candidates if item[0] == candidates[0][0]))
    return unique[0] if len(unique) == 1 else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", default="output/sources/useragent.in")
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--attempts", type=int, default=3)
    args = parser.parse_args()
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)

    try:
        index_raw = fetch(INDEX_URL, args.timeout, args.attempts)
        (out / "index.json").write_bytes(index_raw)
        index = json.loads(index_raw.decode("utf-8"))
        decoder_raw = fetch(DECODER_URL, args.timeout, args.attempts)
        (out / "list.js").write_bytes(decoder_raw)
        category_file = discover_inapp_file(index)
        if not category_file:
            report = {
                "status": "filename-not-unambiguously-discovered",
                "index_url": INDEX_URL,
                "decoder_url": DECODER_URL,
                "note": "No unique In-App Browsers JSON file was explicitly identified in index.json; no filename was guessed.",
            }
            (out / "fetch-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report, indent=2))
            return 3
        dataset_url = urljoin(BASE, category_file)
        dataset_raw = fetch(dataset_url, args.timeout, args.attempts)
        (out / "in-app-browsers.raw.json").write_bytes(dataset_raw)
        try:
            dataset = json.loads(dataset_raw.decode("utf-8"))
            top_level_count = len(dataset) if isinstance(dataset, list) else None
        except (UnicodeDecodeError, json.JSONDecodeError):
            top_level_count = None
        report = {
            "status": "raw-source-fetched",
            "index_url": INDEX_URL,
            "decoder_url": DECODER_URL,
            "dataset_url": dataset_url,
            "terms_url": TERMS_URL,
            "dataset_bytes": len(dataset_raw),
            "top_level_count_before_decoder": top_level_count,
            "note": "Raw bytes are preserved. Decode only with the downloaded list.js implementation; do not infer records from dictionary indexes.",
        }
        (out / "fetch-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        return 0
    except HTTPError as exc:
        report = {"status": "http-error", "url": exc.url, "code": exc.code, "reason": str(exc)}
    except Exception as exc:
        report = {"status": "fetch-failed", "reason": f"{type(exc).__name__}: {exc}"}
    (out / "fetch-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2), file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
