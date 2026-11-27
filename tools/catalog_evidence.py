"""Verify proposed values against retained source excerpts without network access."""
import re

MEASUREMENT_LABELS = {
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


def checked_device_quotes(record, evidence, source_text):
    """Return each affected field's source quote, or None if any fact is unsupported."""
    if not isinstance(record, dict) or not isinstance(evidence, dict) or not isinstance(source_text, str):
        return None
    fields = {}
    for key, value in record.items():
        if key == "builds" and isinstance(value, dict):
            for os_version, build in value.items():
                fields[f"builds.{os_version}"] = [os_version, build]
        elif isinstance(value, dict):
            for name, nested in value.items():
                fields[f"{key}.{name}"] = [nested]
        else:
            fields[key] = value if isinstance(value, list) else [value]
    result = {}
    for key, values in fields.items():
        quote = evidence.get(key)
        if not isinstance(quote, str) or not quote.strip() or quote not in source_text:
            return None
        if key in MEASUREMENT_LABELS and not re.search(MEASUREMENT_LABELS[key], quote, re.I):
            return None
        if key in {"display.w", "display.h"}:
            display = record["display"]
            dimensions = rf"(?<!\d){re.escape(str(display.get('w')))}\s*[x×]\s*{re.escape(str(display.get('h')))}(?!\d)"
            if re.search(dimensions, quote, re.I):
                result[key] = quote
                continue
            label = "width" if key == "display.w" else "height"
            if not re.search(rf"\b{label}\b", quote, re.I):
                return None
        if key == "id" and len(values) == 1:
            # The local key can be the deterministic slug of the quoted model or model code.
            identities = [record.get(k) for k in ("model", "uaModel", "id")]
            if any(isinstance(item, str) and contains_value(quote, item) and
                   re.sub(r"[^a-z0-9]+", "-", item.lower()).strip("-") == values[0] for item in identities):
                result[key] = quote
                continue
        if not values or not all(contains_value(quote, item) for item in values):
            return None
        result[key] = quote
    return result
