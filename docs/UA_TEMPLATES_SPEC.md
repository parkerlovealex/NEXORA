# NEXORA In-App User-Agent Template Collection — Specification v1.0

Status: DRAFT for owner review. Collection does not start until this spec is approved.
Companion to DATA_SYSTEM_PROMPT.md / DATA_PLAN.md. The device database (devices.json,
dataVersion 2026.11.25.6, published) is NOT modified by this workstream.

## 1. Purpose

devices.json holds app *versions and builds*. It does not hold the actual
User-Agent string formats those apps emit from their embedded browsers.
This workstream collects those formats — observed, evidence-backed — into a
separate catalog, `ua_templates.json`, so the generator can offer an app
only when a verified template exists for it. No invented UA strings, ever.

## 2. File and location

- Repo path: `data/ua_templates.json` (beside devices.json), mirrored to the
  app assets and web copies by the same publish step as devices.json.
- Schema version: `uaTemplatesSchema: 1`, own `dataVersion` (date-based,
  independent of the device database version).

## 3. Record schema (one record per app per platform)

```json
{
  "app": "facebook",
  "platform": "android",
  "status": "pending",
  "template": "Mozilla/5.0 (Linux; Android {osVersion}; {model} Build/{buildId}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/{chromeVersion} Mobile Safari/537.36 [FBAN/FB4A;FBAV/{appVersion};FBBV/{appBuild};FBDM/{density};FBSN/{...};...]",
  "slots": {
    "osVersion": "device.osVersions[]",
    "model": "device.uaModel",
    "buildId": "device.builds[osVersion] | device.buildsRecent[]",
    "chromeVersion": "meta.versions.chrome (+ history)",
    "appVersion": "meta.apps.<app>.version | meta.apps.<app>.versions[]",
    "appBuild": "meta.apps.<app>.build | meta.apps.<app>.versions[]",
    "density": "device.display (dpi string derived from ppi/dpr)",
    "resolution": "device.display.w x device.display.h",
    "locale": "market locale (country -> language tag)"
  },
  "compatibility": { "minAndroid": null, "maxAndroid": null, "notes": "" },
  "evidence": [
    {
      "source_url": "",
      "observed": "<the real UA string or format exactly as published>",
      "quoted_text": "",
      "app_version": "",
      "app_build": "",
      "observed_at": "YYYY-MM-DD",
      "kind": "observed-ua | official-doc | technical-reference | candidate-script"
    }
  ]
}
```

Rules:
- Every `{slot}` in a template MUST appear in `slots` and MUST resolve to a
  real field of devices.json. A template with an unresolvable slot is invalid.
- `status` lifecycle: `candidate` (from starting material, unchecked) ->
  `pending` (format plausible, evidence incomplete) -> `verified` (evidence
  complete, see §5) -> `outdated` (app changed its format; kept for history).
  `rejected` for formats proven wrong. Only `verified` records are selectable
  in the generator; the UI shows pending/candidate honestly as unavailable.
- One app may hold several records (format changed across app generations);
  at most one `verified` record per app+platform is "current".

## 4. Candidate starting material (status: candidate, never auto-verified)

1. Karn's Py_Code scripts (Py_Code.zip) — real formats for Facebook and
   Instagram, Android and iOS, from an older app generation. Extracted shapes:
   - FB iOS: `[FBAN/FBIOS;FBAV/{v};FBBV/{build};FBDV/{model};FBMD/iPhone;...]`
   - IG Android: `Instagram {v} Android ({sdk}; {dpi}; {resolution}; {vendor}; {model}; ...)`
     plus the rule that the trailing ID's first 2 digits are fixed per version
     and the remaining 8 are minted.
   - FB Android WebView base: `Mozilla/5.0 (Linux; {android}; {model} Build/{build}) ...`
2. The app's existing hardcoded builders in AppUa.kt (Facebook, Instagram,
   Messenger) — same treatment: candidate until corroborated.
3. Everything else (16 apps) starts empty. Empty is a valid state.

## 5. Evidence requirements for `verified`

A record becomes `verified` only with BOTH:
- At least one `observed-ua` or `official-doc` evidence entry whose observed
  string matches the template's structure token-for-token (slots abstracted),
  AND whose app_version/app_build pair exists in devices.json
  meta.apps.<app>.versions[] (version consistency), and
- A second independent source (any kind except the same page) agreeing on
  the app-specific token block (e.g. the FBAN/FBBV segment for Meta apps).
Candidate-script evidence alone can never produce `verified`.

## 6. Collection sources, in trust order

1. Observed-UA databases and references: deviceatlas.com sample UAs,
   whatismybrowser.com, user-agents.net, ua-parser / device-detector
   fixtures on GitHub (their test YAMLs contain real app UAs).
2. Official documentation: Meta for Developers (FBAN format), TikTok /
   bytedance open-source SDKs, Telegram / WeChat developer docs, app SDK
   sources on GitHub that construct the UA in code (best evidence: the
   string built in the app's own open-source components, e.g. WebView
   wrappers, Cronet / OkHttp interceptors printing the UA).
3. Technical references: reputable UA-string reference sites, XDA /
   StackOverflow answers quoting observed strings (counts as one source,
   needs corroboration).
4. Candidate scripts (§4) — starting material only.

Per-source isolation applies (the Career-bot rule): one blocked source
fails alone; the pipeline continues with the rest.

## 7. Pipeline (reusable, not one-off strings)

- `tools/collect_ua_templates.py` — input-driven collector: reads a
  findings file (same pattern as collect_app_versions.py), merges records,
  never overwrites a `verified` record with lower-status data.
- AI (Cerebras, repo secret) may normalize candidate records and flag
  inconsistencies between sources. AI must never invent a token, a slot,
  or an evidence quote; a field it cannot source stays empty.
- `tools/validate_ua_templates.py` — gates:
  1. Syntax: balanced brackets, every `{slot}` declared and resolvable.
  2. Version consistency: evidence app_version/app_build pairs exist in
     devices.json (current or history).
  3. Platform consistency: Android templates contain no iOS-only tokens
     and vice versa (FBAN/FB4A vs FBAN/FBIOS, etc.).
  4. Evidence verification: `verified` requires §5's two-source rule;
     every evidence entry has URL + observed string + date.
  5. Duplicate detection: same app+platform+token-block = one record.
- Publication mirrors the device flow: proposal file -> validation report
  (0 errors required) -> owner-approved publish to the repo -> the app and
  website read it on their normal update path. Statuses ride along so the
  UI can show Ready / Template pending / Unavailable honestly.

## 8. Refresh cadence

App UA formats change rarely (on app redesigns, not on version bumps).
Quarterly re-verification of `verified` records against one fresh observed
source; a format not re-observed within 2 cycles is demoted to `outdated`
review. Version bumps alone never change a template's status.

## 9. Definition of done (Workstream B)

- ua_templates.json published with all 18 apps present as records
  (any honest status — an app with only `candidate`/`pending` is a valid
  outcome; a fabricated `verified` is failure).
- Collector + validator in tools/, tests passing, one full pipeline run
  reproduced from findings alone.
- Handoff note for Workstream C: the app reads devices.json +
  ua_templates.json, displays all 18 apps, selectable only when `verified`,
  and the US locale suggestion is en-US (C's own fix, listed here so the
  data side is not blamed for it later).

## 10. Out of scope

- Any change to devices.json content or schema.
- App UI / generator code (Workstream C, separate build line).
- Templates for apps outside the 18 in meta.apps.
