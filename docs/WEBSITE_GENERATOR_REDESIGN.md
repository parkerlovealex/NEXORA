# NEXORA Website — User-Agent Studio & Admin Control Center

**Status:** Implemented in the uploaded website source package; deployment not verified.  
**Date:** 2026-11-25

## Public entry point

The root website now opens directly to the User-Agent Studio rather than the old device-intelligence overview.

### Generator capabilities
- Mixed Android, iOS, Windows and macOS batch quantities.
- Independent quantities per platform; combined maximum 500.
- Device selection modes: random, selected pool, balanced and custom per-device quantities.
- Searchable device catalog selection.
- Latest-supported, random-supported or explicit mobile OS version; desktop selectors include the latest five Windows 11 releases, last three Windows 10 feature releases and latest ten macOS major releases.
- Platform-specific browser family options for Android, iOS, Windows and macOS.
- Chrome Android version is prefilled from `meta.versions.chrome`.
- Mobile browser versions not present in the mobile catalog remain manual; desktop versions are supplied by the separately source-documented desktop catalog.
- Optional in-app browser section showing all app records.
- In-app app choices are selectable only when a corresponding `verified` record exists in `ua_templates.json`.
- Country and language are metadata, not injected into standard UA strings.
- Duplicate removal, copy-one, copy-all, TXT/CSV/JSON export and local saved sets.
- Optional interface sound feedback (off by default).
- Motion respects `prefers-reduced-motion`.

## Data honesty and limitations
- This uploaded repository contains the device catalog and 18 app version/build records, but no `ua_templates.json`.
- Therefore all 18 in-app browser entries show `TEMPLATE PENDING` and are disabled. No app token is fabricated.
- Standard browser UAs are compatibility templates built from catalog device/OS/build data and the chosen browser version. They are not observations of a real device fingerprint.
- iOS records are reference templates; generation does not emulate an iOS runtime. Standard iPhone UAs do not expose the exact iPhone model.
- Mobile Safari, Firefox, Edge, Opera and iOS Chrome versions remain manual unless present in the mobile catalog. Desktop browser version lists are supplied by a separate dated source catalog with five stable releases per supported browser.
- Locale is recorded as country/language/Accept-Language metadata, not inserted into the UA string.

## Administrator control center
After authentication, the administrator can access:
- System Overview with API, database version, device/app counts, UA-template catalog state and review queue.
- Device Catalog and App Versions.
- Compatibility matrix.
- Source connectors and automation runs.
- Review queue.
- AI assistant.
- Audit log.
- System settings and deployment guidance.

The dashboard never displays secret environment-variable values. It uses the existing FastAPI admin APIs and does not introduce a new customer cloud database.

## Deployment notes
- `web/ua_templates.json` is an optional future asset. Publish it alongside `web/devices.json` only after Workstream B has approved and verified data.
- When the API is unavailable, the site uses the bundled `web/devices.json` in read-only mode.
- Static Vercel hosting cannot provide admin operations by itself; configure `NEXORA_CONFIG.apiBase` to the deployed FastAPI service for authentication and control-center APIs.
- This package changes website files only; it does not build or modify the Android APK.


## Superseded by UX v2

The current public layout and interaction design are documented in `docs/WEBSITE_UX_REDESIGN_V2.md`. UX v2 replaces the earlier five-card-heavy layout with a compact platform/batch, device, browser/app and advanced-options flow. It keeps all prior data-integrity boundaries.


## Desktop UA extension — 2026-11-25

Windows/macOS desktop browser generation is implemented in the supplied `web-current/` source snapshot; production integration is not verified. The desktop catalog is a separate file (`data/desktop-platforms-2026-11-25.json`) mirrored to `web-current/desktop-catalog.json`. `vercel.json` points to `web/`, which is absent from this handoff pack; port the desktop asset and code into the actual `web/` directory without overwriting its newer icon repairs. The catalog does not alter `devices.json`, the app-version catalog or Workstream B app-specific UA templates.

Reduced-UA behavior is preserved: Windows 10 and Windows 11 use the same `Windows NT 10.0; Win64; x64` token; Chrome-family and Safari macOS UAs use `Macintosh; Intel Mac OS X 10_15_7`; Firefox uses `Macintosh; Intel Mac OS X 10.15`. Selected OS release/build information is stored as output metadata.
