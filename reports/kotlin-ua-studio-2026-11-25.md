# NEXORA dedicated Kotlin User-Agent Studio — 2026-11-25

## Owner-directed scope

A dedicated, separate browser UI was added for the User-Agent generator. It is linked from the main NEXORA website as **Advanced UA Studio** and is staged at `web-current/ua-studio/`.

The existing website generator and the Workstream A/B data boundaries remain in place. This is a new route and new Kotlin/JS app, not a replacement of the existing page.

## Implementation

- Frontend UI rendering, state, catalog parsing, generation, filters, clipboard and exports are authored in Kotlin (`src/Main.kt`).
- Kotlin/JS IR compiles the Kotlin source to the checked-in browser bundle `ua-studio.js`.
- `index.html` is a small static shell; `styles.css` contains the dedicated visual system.
- No Kotlin/JVM server, backend endpoint, npm runtime dependency, external JS framework or remote font dependency is required.
- Browser runtime reads same-origin JSON from `ua-studio/data/`.

## UI and feature coverage

- Independent page at `/ua-studio/`, with a sidebar, top bar, generation overview, configuration workspace, live batch summary, results, app-format inventory and data notes.
- Responsive desktop/tablet/mobile layouts.
- Android, iOS, Windows and macOS in one generator.
- Platform presets: Mobile, Desktop, All platforms; batch quick picks 10/25/50/100/250/500; total hard cap 500.
- Android/iOS device market filtering, searchable device selection and random, selected, balanced and custom per-device distribution.
- Mobile OS latest/random/specific versions per device; Windows/macOS latest/random/specific OS releases.
- Browser families: Android Chrome/Firefox/Edge/Opera/WebView; iOS Safari/Chrome/Firefox/Edge; Windows Chrome/Firefox/Edge/Opera; macOS Chrome/Firefox/Edge/Opera/Safari.
- Browser version strategies: latest, random latest five, catalog selection and manual entry.
- Android Chromium engine override for WebView, Edge and Opera.
- Locale, language and Accept-Language metadata; deduplication; copy-one/copy-all; search and platform filters; JSON/CSV/TXT export.
- Facebook and Instagram Android script-candidate formats can be opted into. They remain explicitly labelled candidate/unverified. Other catalog entries are visible as reference-only.

## Catalogs

The dedicated page bundles its own same-origin copies of:

- `data/devices.json` — 44 Android and 15 iOS records, plus mobile browser versions.
- `data/desktop-catalog.json` — eight Windows releases, ten macOS releases and five version entries per supported desktop browser.
- `data/ua_templates.json` — 11 source-classified app-template records, all candidate in the supplied catalog snapshot.

The Workstream A device source and the main Workstream B evidence/proposal data were not rewritten by the Kotlin UI work.

## Validation performed

- Kotlin/JS IR compilation completed using the installed Kotlin/JS 1.9.0 compiler.
- `node --check web-current/ua-studio/ua-studio.js` passed.
- `node tests/ua-studio-smoke.js` passed: device counts, desktop release counts, browser-version list counts, template evidence classification, route link and core Kotlin feature markers.
- Headless Chromium runtime exercised the Kotlin bundle with same-origin JSON fetches stubbed in the browser context. Page rendering, Android generation, iOS Safari, iOS Chrome/Firefox/Edge, Windows Chrome/Firefox/Edge/Opera, macOS Chrome/Firefox/Edge/Opera/Safari, Android WebView, manual version entry, app candidates, custom device distribution, result filters/search and JSON export path completed without uncaught browser errors.
- The environment blocks direct Chromium navigation to local HTTP/file URLs (`ERR_BLOCKED_BY_ADMINISTRATOR`), so the runtime exercise used a controlled about:blank browser context with the actual Kotlin bundle and the exact bundled JSON payloads. This is not a production-domain visual QA pass.

## Deployment boundary

The handoff's `vercel.json` still specifies `outputDirectory: web`; the actual current `web/` output directory is not included in this archive. The new UI is staged under `web-current/` and is not deployed by this work. To publish it, merge the changed main `index.html` and `styles.css` plus the complete `ua-studio/` directory into the full repository's current `web/` directory. Preserve the newer `web/icons/` assets and `LOCAL_ICONS` repairs. Do not replace `web/` wholesale with this older snapshot.

## Build

From `web-current/ua-studio/`, run `./build.sh` with Kotlin/JS 1.9+ and Node available. The compiled bundle is included, so a deployment does not need the compiler.
