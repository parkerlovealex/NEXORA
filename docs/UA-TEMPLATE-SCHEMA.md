# UA template schema — the canonical contract

`web/ua_templates.json` is the one canonical catalog of in-app browser
user-agent templates. The website (UA Studio) consumes it directly; the
Android app and the Windows browser adapt to this schema — the schema is
never simplified to fit a consumer. Formal contract:
`catalog/ua-templates.schema.json` (JSON Schema, draft 2020-12).

## File-level fields

| Field | Meaning |
| --- | --- |
| `uaTemplatesSchema` | Always `1`. |
| `dataVersion` | This artifact's own revision (`YYYY.MM.DD.N`); see `docs/CATALOG-VERSIONING.md`. |
| `updatedAt` | Date of the last content change (`YYYY-MM-DD`). |
| `deviceCatalogVersion` | The `devices.json` `dataVersion` this file was validated against. Must equal the published devices `dataVersion`. |
| `catalogReleaseId` | The shared catalog release this artifact belongs to. |
| `sourcePolicy` | The standing rule: observed strings and candidate source formats remain distinct; the collector never promotes status. |
| `coverage` | One row per app in `devices.json` `meta.apps`, with a state per platform (below). |
| `records` | The template records (below). |
| `sources` | Provenance of the collection itself. |

## Records

Every record carries: `id`, `app`, `platform` (`android` | `ios`), `status`,
`template`, `source_format`, `slots`, `unresolved_slots`, `review_notes`,
`schemaVersion` (always `1`), `evidence`, and optionally `compatibility`
(`minOS`, `maxOS`, `notes`).

### Verified records

- `status: "verified"`, `template` is the normalized UA string,
  `source_format` is `null`, `unresolved_slots` is empty.
- Every `{placeholder}` in the template is declared in `slots`, and every
  slot path resolves against the approved catalog fields (the whitelist in
  `tools/validate_ua_templates.py` — `meta.apps.<app>.version/.build`,
  `meta.apps.<app>.versions[]…`, `meta.versions.chrome`, and named device
  fields). No unresolved placeholders.
- Evidence is preserved in full and must include: at least two independent
  source IDs on two different HTTPS domains; at least one `observed-ua` or
  `official-doc` entry; an app version/build pair that exists in
  `devices.json`; and the template must match an exact observed UA.
- **Fixture rule:** when every slot resolves from the current app
  version/build in `devices.json`, substituting those values into the
  template must reproduce a recorded observed UA byte-for-byte. The
  Facebook Android record is the standing example: only its `appVersion`
  slot resolves (`meta.apps.facebook.version`); the literal `Android 10; K`
  and `Chrome/158.0.0.0` tokens are preserved exactly as observed — the
  catalog's Chrome 159 value is never substituted without a matching
  observation.
- At most one current verified record per app + platform.

### Candidate records

- `status: "candidate"`, `template` is `null`, `source_format` holds the
  source string as collected.
- Every placeholder in `source_format` is accounted for in
  `unresolved_slots`, with two documented exceptions:
  - **Derived slots:** a `formatted_<name>` placeholder is the formatting
    of the unresolved `<name>` value (e.g. `formatted_ios_version` derives
    from `ios_version`), so it is not listed separately.
  - **Alternation markers:** when `source_format` contains alternative
    shapes joined by ` OR `, `unresolved_slots` may name the structural
    choice itself — but only for the specific records the marker is
    documented for in the validator's `ALTERNATION_MARKERS` map
    (currently `optional_IABMV` for the optional `IABMV/1` block
    in the Facebook script candidates).
- `slots` is empty, source evidence and review notes are preserved, and a
  candidate is **never** promoted to verified automatically — promotion
  requires observed evidence meeting the verified rules above.

## Coverage semantics

A coverage state is the **highest evidence level available** for that app
and platform — `verified` if a verified record exists, else `candidate` if
a candidate record exists, else the highest remaining record status, else
`not-collected`. It is an inventory statement, **not** a claim that every
application or device variant has been collected, and it never makes an
app selectable by itself. The validator enforces that coverage and records
agree; the Facebook/Android row reading `pending` while a verified record
existed was the inconsistency this rule now catches (corrected in catalog
release 2026.11.28.2).

## Validation

```sh
pip install -r requirements-dev.txt   # provides the jsonschema validator
python3 tools/validate_ua_templates.py web/ua_templates.json --devices web/devices.json
python3 tools/catalog_manifest.py check   # manifest + schema + semantic validation
```

`tools/catalog_manifest.py check` runs all three layers: manifest
consistency, JSON Schema validation of `ua_templates.json` with a real
Draft 2020-12 validator (the `jsonschema` package — if it is missing, the
check fails loudly rather than skipping), and the semantic rules in
`tools/validate_ua_templates.py`.
