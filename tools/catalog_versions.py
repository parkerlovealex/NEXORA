"""Merge an observed release into the shared app catalog format."""

RELEASE_FIELDS = ("version", "build", "sourceUrl", "sourceEvidence", "buildEvidence")


def merge_app_release(previous, observed):
    if isinstance(previous, str):
        previous = {"version": previous}
    entry = dict(previous or {})
    history = list(entry.get("versions") or [])

    def retain(release):
        if not release.get("version"):
            return
        pair = release["version"], release.get("build")
        for index, item in enumerate(history):
            if isinstance(item, dict) and (item.get("version"), item.get("build")) == pair:
                release = {**history.pop(index), **release}
                break
        history.insert(0, release)

    old_release = {key: entry[key] for key in RELEASE_FIELDS if key in entry}
    retain(old_release)
    same_version = entry.get("version") == observed["version"]
    same_build = observed.get("build", entry.get("build")) == entry.get("build")
    if not (same_version and same_build):
        for key in RELEASE_FIELDS:
            entry.pop(key, None)
    elif entry.get("build") and not observed.get("build") and entry.get("sourceEvidence"):
        entry.setdefault("buildEvidence", entry["sourceEvidence"])
    entry.update(observed)
    retain({key: entry[key] for key in RELEASE_FIELDS if key in entry})
    entry["versions"] = history
    return entry
