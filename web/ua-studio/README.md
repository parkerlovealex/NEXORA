# NEXORA User-Agent Studio — Kotlin/JS

A dedicated browser-first User-Agent generator page, separate from the original NEXORA website generator.

## Runtime architecture

- UI rendering, state management, catalog parsing, UA construction, filtering and export logic are written in **Kotlin**.
- `src/Main.kt` is compiled to `ua-studio.js` with the Kotlin/JS IR backend.
- `index.html` is a small browser shell; `styles.css` owns presentation only.
- No Kotlin/JVM server, API service, npm runtime dependency or third-party JavaScript framework is required.
- The compiled JavaScript bundle is included so the static page can run without compiling on the deployment host.

The app is served from `/ua-studio/` when the `web-current/` snapshot is published as the static root. The main NEXORA sidebar now links to this dedicated route.

## Included capabilities

- Android, iOS, Windows and macOS output in one workspace.
- Platform presets: Mobile, Desktop and All platforms.
- Batch sizes from 1 to 500, including quick batch presets.
- Device market filtering and device search.
- Random, selected, balanced and custom per-device distribution for mobile devices.
- Latest, random or specific mobile OS versions per device; desktop OS release selection for eight Windows releases and ten macOS releases.
- Browser selection per platform:
  - Android: Chrome, Firefox, Edge, Opera and Android WebView.
  - iOS: Safari, Chrome, Firefox and Edge.
  - Windows: Chrome, Firefox, Edge and Opera.
  - macOS: Chrome, Firefox, Edge, Opera and Safari.
- Browser version strategies: latest catalog version, random among the latest five, selected catalog version or manual version entry.
- Separate Android Chromium engine version for WebView, Edge and Opera.
- Locale, language and Accept-Language metadata.
- Duplicate suppression, one-click row copy, copy-all, result search/filter and JSON/CSV/TXT exports.
- Facebook and Instagram Android script-candidate app formats. These are labelled **candidate / unverified**; other template-catalog rows are reference-only.
- Result metadata retains device model, OS release/build, browser release/token, locale, source type and notes.

## Build

Requires Kotlin/JS 1.9+ (`kotlinc-js`). The checked-in `ua-studio.js` is already compiled.

```bash
cd web-current/ua-studio
./build.sh
```

The build script uses the Kotlin standard library bundled with the local compiler and writes the browser bundle to `ua-studio.js`.

## Data files

- `data/devices.json` — Android and iOS device records and mobile browser-version lists.
- `data/desktop-catalog.json` — Windows/macOS releases and desktop browser-version catalog.
- `data/ua_templates.json` — app-specific template inventory and evidence classifications.

The page fetches these same-origin JSON files at startup. It runs in read-only, browser-local mode; it does not call the admin API.

## Important behavior

Reduced desktop User-Agent strings do not expose every selected OS release. Windows 10 and Windows 11 use the same standard `Windows NT 10.0` platform token. Modern macOS browser UAs use frozen/reduced platform tokens. The selected release and build remain metadata in the result record.

The generated strings are compatibility templates for QA and development. They do not change the real browser, device, network identity or runtime environment. App-specific formats are source-derived candidates and must not be described as verified observed traffic.

## Deployment integration

This handoff contains the updated `web-current/` snapshot. The repository's `vercel.json` still points to `outputDirectory: web`, and the actual `web/` directory is not present in this handoff archive. Merge `web-current/index.html`, `web-current/styles.css`, `web-current/ua-studio/` and the sidebar link into the full repository's current `web/` directory. Preserve its existing icon assets and local icon repairs. Do not replace the production `web/` directory wholesale with this snapshot.
