# NEXORA In-App User-Agent Template Collection — Specification v1.1

**Status: APPROVED FOR COLLECTION — owner authorization received 2026-11-25.**  
**Publication status: NOT PUBLISHED.** A validated proposal still requires separate owner approval before it is mirrored into the website/app runtime catalogs.

The published device catalog remains `web/devices.json`, dataVersion `2026.11.25.7` (advanced from .6 on 2026-11-25 when vendor-sourced browser versions were added; app version/build histories unchanged). Workstream B must not edit it.

## 1. Purpose

`devices.json` stores app version/build pairs and device facts. It does not establish the exact User-Agent string formats emitted by each app's embedded browser. Workstream B collects those formats into a separate `ua_templates.json` catalog. Only a template with verified, independently sourced evidence may be selectable in NEXORA.

A browser UA, Android WebView UA and app-specific in-app UA are different things. An in-app UA may retain a platform WebView/WebKit base and append an app-specific token block; the exact block and token order are app- and platform-specific. Do not assume every app uses Android `; wv)` or every iOS app adds an identifiable suffix.

## 2. Files and locations

- Canonical normalized findings: `data/ua_templates.findings.json`.
- Source inventory and access status: `data/ua_templates.sources.json`.
- Read-only candidate source archive: `data/source-materials/User-Agent.zip`.
- Collector: `tools/collect_ua_templates.py`.
- Validator: `tools/validate_ua_templates.py`.
- Generated proposal: `output/ua_templates.proposed.json`.
- Validation/report artifacts: `output/ua_templates.*.md` and `.json`.
- Runtime mirror after owner approval only: `web/ua_templates.json`; Android app asset mirroring belongs to the separate active app build line.
- Schema version: `uaTemplatesSchema: 1`; dataVersion is independent of the device catalog version.

## 3. Record model

Each record is one app + platform + format generation/variant. Several candidate records may exist for the same app/platform. At most one current `verified` record may exist for an app/platform.

```json
{
  "id": "facebook-android-v1",
  "schemaVersion": 1,
  "app": "facebook",
  "platform": "android",
  "status": "pending",
  "template": "... {osVersion} ... {appVersion} ...",
  "source_format": null,
  "slots": {
    "osVersion": "device.osVersions[]",
    "appVersion": "meta.apps.facebook.version"
  },
  "unresolved_slots": [],
  "compatibility": {"minOS": null, "maxOS": null, "notes": ""},
  "evidence": [
    {
      "source_id": "source-name",
      "source_url": "https://example.org/reference",
      "observed": "exact raw UA string when available",
      "quoted_text": "exact source excerpt proving the value",
      "app_version": "...",
      "app_build": "...",
      "observed_at": "YYYY-MM-DD or null when the source does not disclose it",
      "fetched_at": "YYYY-MM-DD",
      "kind": "observed-ua"
    }
  ]
}
```

`source_format` is retained for code-derived candidate formats that are not yet safe to turn into generator templates. `template` remains null until all slots are mapped to real catalog fields. This prevents unresolved random values from being presented as valid generator slots.

Allowed statuses:
- `candidate`: supplied script or app implementation only; not observed independently.
- `pending`: normalized structure with some evidence, but verification gate incomplete.
- `verified`: evidence gate passed; only selectable state.
- `outdated`: historically verified format no longer current.
- `rejected`: evidence disproves the candidate.

Coverage is a separate inventory for all 18 app keys in `devices.json` `meta.apps`. Coverage states include `not-collected`; these are not template statuses and never make an app selectable. `webview` is not one of the 18 app records; it is a browser engine mode.

## 4. Slot rules

Every placeholder in a normalized `template` must appear in `slots`, and every slot path must resolve against the current published `devices.json` schema. The validator uses an explicit allowlist, not arbitrary expressions.

Initial allowed paths:
- `device.brand`, `device.model`, `device.uaModel`, `device.id`, `device.codename`, `device.soc`.
- `device.osVersions[]`, `device.builds[osVersion]`.
- `device.display.w`, `.h`, `.dpr`, `.ppi`.
- `meta.versions.chrome`.
- `meta.apps.<app>.version`, `.build`, `.versions[].version`, `.versions[].build`.

Do not assume `display.ppi` is Android logical density. Do not infer chipset tokens from `soc`, SDK/API values from an OS version, app runtime IDs, FBRV values, or app-specific random build identifiers unless an authoritative source establishes the exact mapping and the data model stores it.

## 5. Evidence and verification gate

Evidence kinds are `observed-ua`, `official-doc`, `published-dataset-entry`, `technical-reference` and `candidate-script`. A curated corpus row is `published-dataset-entry`; it is not silently promoted to raw telemetry.

A record may be marked `verified` only when all conditions hold:
1. At least one `observed-ua` or `official-doc` source contains a complete raw UA or exact format description that matches the normalized template token-for-token after slot substitution.
2. A second independent source, on a different HTTPS domain and with a different source ID, independently agrees on the app-specific token block and its order.
3. The observed app_version/app_build pair exists together in `devices.json` `meta.apps.<app>` current values or version history. Never combine a version from one finding with a build from another.
4. Every slot resolves to a real catalog field; no unexplained random token remains.
5. Platform markers and token block agree with the record platform. Ambiguous iOS WebKit strings remain pending rather than being guessed as a specific app.
6. Every evidence item records `fetched_at`. A verified record also requires an actual source observation/publication date in `observed_at`; fetch date must never be relabelled as observation date.

Candidate-script evidence alone can never verify a record. App version pages do not prove UA structure. A database category count does not prove that NEXORA has collected its individual rows.

## 6. Source strategy

### Primary observed-UA corpus: useragent.in

- Category: **In-App Browsers**, source page `https://useragent.in/user-agents`.
- The page reports **108 entries** in this category and names Facebook, Instagram, TikTok, WeChat, LINE webviews and Electron desktop apps among the coverage.
- The page says in-app entries are transcribed from real observed traffic. However, the site's Terms of Use also say the dataset is not a sample of live traffic and that entries do not all correspond to shipping configurations. Preserve both qualifications: classify rows as `published-dataset-entry`, not raw `observed-ua` telemetry, unless a separate raw observation is supplied.
- Dataset index: `https://useragent.in/assets/data/index.json`. Discover the category filename from this index. Do not guess it.
- Terms: `https://useragent.in/terms` permit downloading, using, modifying and redistributing the parser/dataset, including commercially; attribution is appreciated but not required.
- Retrieval status in this run: the browser fetch returned HTTP 429 and Python `urllib` failed DNS resolution. No raw rows were copied into NEXORA. Retry from the GitHub Actions collection runner; retain the original JSON and decoder version before normalizing.

### Other sources

1. App-observed UA datasets and fixtures: DeviceAtlas, WhatIsMyBrowser, ua-parser / device-detector test fixtures and similar repositories.
2. Official app/SDK source code: Meta, TikTok/ByteDance, Telegram, WeChat and platform-specific WebView wrappers where the UA construction is explicit.
3. Technical references and issue reports: use for discovery/corroboration only, not sole verification.
4. User-supplied `User-Agent.zip` and `AppUa.kt`: candidate material only. The Python scripts generate randomized values and contain historical app versions; they must not be run to manufacture evidence or promoted to verified truth.

Per-source isolation applies: one blocked host must not stop the rest of the collection pipeline.

## 7. Pipeline and AI normalization

- `tools/fetch_useragent_in.py` fetches `index.json` and the site's decoder, discovers the exact in-app category filename from the index, and preserves the raw category JSON. It never guesses a filename.
- `.github/workflows/ua-templates-collect.yml` runs quarterly or manually, retries the source fetch from GitHub Actions, uploads raw artifacts, optionally runs the AI normalizer when a decoded rows file is supplied, validates a proposal and opens a draft evidence PR. It never publishes the runtime catalog.
- `tools/normalize_ua_templates_ai.py` uses `CEREBRAS_API_KEY` to propose slot normalization from decoded source rows. It preserves source evidence kind in code, rejects unknown row IDs, and never returns `verified`. The useragent.in category is `published-dataset-entry`; it cannot alone satisfy the raw `observed-ua` evidence requirement.
- `tools/collect_ua_templates.py` merges normalized findings into a proposal. It preserves verified records against lower-status refreshes and never upgrades status.
- `tools/validate_ua_templates.py` checks schema, app/platform identity, status, exact slot declarations and resolution, evidence completeness, independent sources, app version/build history, platform consistency, exact template-to-observation matching and duplicate current verified records.
- AI may extract tokens, compare source structures, propose normalized slots and flag inconsistencies. AI output is untrusted input: it may not invent tokens, app versions, build IDs, source URLs, quotes, source rows or missing fields. Every proposed value must point back to a source excerpt.
- Store raw source files, raw UA strings, row IDs, fetch timestamps and normalized findings separately. Never discard source originals after AI normalization.
- The collector creates a proposal only. A zero-error validation report is necessary but not sufficient for publication; owner approval is still required.

## 8. Initial collection state

- `User-Agent.zip` is preserved as candidate evidence. Its Facebook and Instagram Android/iOS builders remain candidate-only because they choose random app/browser versions and mint dynamic IDs/build suffixes.
- `AppUa.kt` contributes additional candidate shapes for Facebook, Instagram and Messenger. These are app implementation candidates, not independent observed-UA evidence.
- The useragent.in corpus is registered as the first external source, but its raw 108 rows were not retrieved in this run due HTTP 429. The source-reported count is not a NEXORA collection count.
- No template is verified in this initial proposal.

## 9. Refresh cadence and definition of done

Recheck verified templates quarterly against a fresh independent source. App version changes alone do not change UA template status. After two missed verification cycles, move a template to outdated review.

Workstream B is done when all 18 apps have explicit coverage rows (including honest `not-collected` states), the source collection pipeline and validator tests pass, the full proposal can be reproduced from preserved findings, and each verified template meets §5. Apps without evidence remain unavailable; completeness is not a reason to invent a template.

## 10. Out of scope

- Editing `devices.json` or reopening Workstream A.
- Website/app runtime integration and en-US locale fix (Workstream C).
- Enabling any app template before it is verified and separately approved for publication.
- Desktop Electron templates in the Android/iOS generator; retain as source leads only unless desktop generation becomes an approved product scope.
