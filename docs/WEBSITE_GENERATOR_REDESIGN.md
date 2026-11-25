# NEXORA Website — User-Agent Studio & Admin Control Center

**Status:** Implemented in the uploaded website source package; deployment not verified.  
**Date:** 2026-11-25

## Public entry point

The root website now opens directly to the User-Agent Studio rather than the old device-intelligence overview.

### Generator capabilities
- Mixed Android and iOS batch quantities.
- Independent quantities per platform; combined maximum 500.
- Device selection modes: random, selected pool, balanced and custom per-device quantities.
- Searchable device catalog selection.
- Latest-supported, random-supported or explicit OS version.
- Platform-specific browser family options.
- Chrome Android version is prefilled from `meta.versions.chrome`.
- Other browser versions are deliberately manual until a verified browser-version source is available.
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
- Safari, Firefox, Edge, Opera and iOS Chrome versions must be supplied manually unless future verified version metadata is added. Chrome Android uses the database value.
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
