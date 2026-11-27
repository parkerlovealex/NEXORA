"""Verify proposed values against retained source excerpts without network access."""
import re

MEASUREMENT_LABELS = {
    "osVersions": r"\b(?:Android|iOS|OS(?:\s+version)?)\b",
    "display.ppi": r"\b(?:ppi|pixels per inch)\b",
    "cores": r"\bcores?\b",
    "display.dpr": r"\b(?:dpr|(?:device\s+)?pixel\s+ratio|scale\s+factor)\b",
    "ramGb": r"\b(?:ram(?:gb)?|memory)\b",
    "storageGb": r"\b(?:storage(?:gb)?|rom|capacity)\b",
}

def contains_value(quote, value):
    if not isinstance(quote, str) or isinstance(value, bool) or not isinstance(value, (str, int, float)):
        return False
    token = str(value).strip()
    if not token:
        return False
    # A version, build, or measurement must be an entire value in the excerpt.
    # A trailing sentence period is valid, while a period followed by another digit is not.
    pattern = rf"(?<![\w.+-]){re.escape(token)}(?![\w+-]|\.\w)"
    return re.search(pattern, quote, re.IGNORECASE) is not None


def supports_version_pair(quote, version, build=None):
    if not contains_value(quote, version):
        return False
    if build is None:
        return True
    if not contains_value(quote, build):
        return False
    version_token = rf"(?<![\w.+-]){re.escape(str(version))}(?![\w+-]|\.\w)"
    build_token = rf"(?<![\w.+-]){re.escape(str(build))}(?![\w+-]|\.\w)"
    separator = r"[\s:()|,=\[\]\"'–—-]+"
    build_label = r"(?:build(?:\s*(?:id|number|code))?|version\s*code|fbbv|cfbundleversion)"
    version_first = rf"{version_token}{separator}(?:{build_label}{separator})?{build_token}"
    build_first = rf"^\s*{build_label}{separator}{build_token}{separator}(?:version|release){separator}{version_token}"
    # Each line is one release entry. A neighboring release cannot supply its build.
    return any(re.search(version_first, line, re.I) or re.search(build_first, line, re.I)
               for line in quote.splitlines())


def device_field_values(record):
    """Keep paired firmware claims together and address nested facts by their field path."""
    fields = {}
    for key, value in record.items():
        if key == "builds" and isinstance(value, dict):
            for os_version, build in value.items():
                fields[f"builds.{os_version}"] = [os_version, build]
        elif key == "buildsRecent" and isinstance(value, list):
            for index, pair in enumerate(value):
                fields[f"buildsRecent.{index}"] = ([pair.get("version"), pair.get("build")]
                                                   if isinstance(pair, dict) else [None])
        elif isinstance(value, dict):
            for name, nested in value.items():
                fields[f"{key}.{name}"] = [nested]
        elif value not in (None, [], ""):
            fields[key] = value if isinstance(value, list) else [value]
    return fields


def supports_device_field(record, key, values, quote):
    if key.startswith(("builds.", "buildsRecent.")):
        if len(values) != 2 or not all(contains_value(quote, value) for value in values):
            return False
        os_version, build = (re.escape(str(v)) for v in values)
        version = rf"(?<![\w.+-]){os_version}(?![\w.+-])"
        firmware = rf"(?<![\w.+-]){build}(?![\w+-]|\.\w)"
        sep = r"""[\s:="'(),|–—-]+"""
        label = r"(?:build(?:\s*(?:id|number))?|firmware(?:\s*version)?)"
        os_label = r"(?:Android|iOS|OS(?:\s+version)?)"
        return bool(re.search(rf"{version}{sep}(?:{label}{sep})?{firmware}|{firmware}{sep}{os_label}{sep}{version}", quote, re.I))
    label = MEASUREMENT_LABELS.get(key)
    if label and not re.search(label, quote, re.I):
        return False
    if key in {"display.w", "display.h"}:
        display = record["display"]
        dimensions = rf"(?<!\d){re.escape(str(display.get('w')))}\s*[x×]\s*{re.escape(str(display.get('h')))}(?!\d)"
        if re.search(dimensions, quote, re.I):
            return True
        label = "width" if key == "display.w" else "height"
        if not re.search(rf"\b{label}\b", quote, re.I):
            return False
    if key == "id" and len(values) == 1:
        for name in ("model", "uaModel", "id"):
            item = record.get(name)
            if isinstance(item, str) and contains_value(quote, item):
                if re.sub(r"[^a-z0-9]+", "-", item.lower()).strip("-") == values[0]:
                    return True
    return bool(values) and all(contains_value(quote, item) for item in values)


def checked_device_quotes(record, evidence, source_text):
    """Check the supplied excerpt for every extracted fact, including any firmware pairs."""
    if not isinstance(record, dict) or not isinstance(evidence, dict) or not isinstance(source_text, str):
        return None
    result = {}
    for key, values in device_field_values(record).items():
        quote = evidence.get(key, evidence.get(key.split(".")[0]))
        if not isinstance(quote, str) or not quote.strip() or quote not in source_text:
            return None
        if not supports_device_field(record, key, values, quote):
            return None
        result[key] = quote
    return result
