# Engine Compatibility — catalog contract (Phase 4.3a)

Status: introduced in catalog release **2026.11.28.3**. This document defines what the
catalog's engine-compatibility metadata means, what it guarantees, and what it must never
claim. Design authority: Phase 4.3 design agreement (audit `PHASE43-AUDIT.md`, spec
`PHASE43-DESIGN-SPEC.md` as amended).

## Principle

The catalog describes what is available; the installed engine determines what can actually
run; the interface explains the difference. Compatibility metadata is **descriptive, not
promissory**: it records which engine family and major versions a catalog selection
coheres with. It never asserts that a particular device's engine matches — that verdict
belongs to the consumer that can see the real engine (Windows launcher, Android WebView).
The website has no engine: it exposes this metadata as data and must not present runtime
compatibility verdicts.

## Where the metadata lives

- **Desktop** (`web/desktop-catalog.json`): every browser version entry carries
  `engineCompatibility: { family, majors, basis }`.
- **Mobile** (`web/devices.json`): the existing `meta.browserVersions` string arrays are
  UNCHANGED. Compatibility lives in a sidecar map,
  `meta.browserVersionCompatibility[<listKey>][<exact version string>]`, whose value is
  either a known-form object `{ family, majors, basis }` (`majors` may be `null` when the
  family is known but the engine major is not determined by the app version) or an
  unknown-form object `{ status: "unknown", reason }`.
- **UA templates** (`web/ua_templates.json`): an OPTIONAL record field
  `engine: { family, major, basis: "evidence", evidenceRefs: [...] }`, present only when a
  cited evidence entry itself documents the engine. Absence means **unknown** — the normal
  state, not an error.

## Value rules

- `family` ∈ `chromium | gecko | webkit`. Family is **platform-specific**, never inferred
  from brand alone: on iOS every browser runs WebKit, so `chromeIos`, `firefoxIos` and
  `edgeIos` are `webkit` with basis `platform-engine-webkit` and `majors: null` (the app
  version does not determine the WebKit major).
- `basis` records WHY the claim is safe:
  - `ua-major-equals-engine-major` — Chromium-family browsers whose UA version is the
    engine version (Chrome desktop/Android).
  - `catalog-engine-mapping` — the entry's own recorded `engineVersion` supplies the major
    (desktop Edge/Opera). An application version is NEVER assumed to equal an engine
    version; where no mapping is recorded (Edge/Opera on Android), the entry is explicitly
    **unknown** with a reason.
  - `browser-is-engine` — Firefox (gecko) and Safari (webkit): the browser is its own
    engine; such selections are family-incompatible with Chromium runtimes.
  - `platform-engine-webkit` — iOS browsers backed by the platform WebKit.
  - `evidence` — templates only; requires resolvable `evidenceRefs`.

## States (consumer-side vocabulary)

`compatible` · `differs` · `unsupported` · `unknown` · `no-runtime`.

- **Unknown is not differs.** `differs` requires evidence of an actual mismatch. A
  selection with no established compatibility is `unknown` (e.g. the verified Facebook
  Android template: its evidence does not document a WebView version, so its engine status
  is unknown — it must not be decorated with invented engine metadata).
- `unsupported` is reserved for engine-family mismatch (e.g. a gecko/webkit selection on a
  Chromium runtime) in engine-backed use.

## Compatibility guarantees (schema versioning)

- All 4.3a metadata is **additive**. `devices.json` remains `schemaVersion` 2; existing
  arrays, fields and record shapes are untouched, so existing consumers (including the
  Android updater's `schemaVersion == 2` gate and the 4.2B template reader) keep working
  byte-for-byte on the parts they read.
- The template schema's record key set gains exactly one optional key (`engine`); parsers
  that read known fields are unaffected, verified by the Android parser regression run.
- New metadata ships only inside a NEW catalog release (release IDs are immutable).

## Validation

- `tools/validate_engine_compatibility.py` — desktop entries present and consistent
  (chromium majors equal the entry's `engineVersion` major; firefox = gecko, safari =
  webkit), sidecar covers exactly the listed versions (no missing, no extras), unknown
  entries carry a reason.
- `tools/validate_ua_templates.py` — a template's `engine.evidenceRefs` must resolve to
  `source_id`s in the same record.
- Both run in the Catalog Validation workflow.
