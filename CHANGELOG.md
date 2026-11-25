# Changelog

## 2026-11-25 — Website User-Agent Studio redesign
- Changed the public landing page to the database-driven User-Agent Studio.
- Added mixed Android/iOS quantities and random, selected-pool, balanced and custom device distribution.
- Added searchable device selection, market-aware country filtering and OS-version controls.
- Added platform-specific browser selection and explicit manual browser-version fields where the catalog has no verified version.
- Added optional in-app app catalog. App-specific templates remain disabled until verified records are published in `web/ua_templates.json`.
- Added copy, TXT/CSV/JSON export and local saved batches.
- Added optional UI sounds and reduced-motion support.
- Added private System Overview with database, API, UA-template, source automation, review and audit status.
- Preserved the existing backend API and Android source. No Android APK or published database files were changed.
- Added `docs/WEBSITE_GENERATOR_REDESIGN.md`.

# Changelog

## NEXORA Next 1.0.0 — first website implementation

- Replaced the single-screen website entry point with a responsive public device-intelligence dashboard.
- Added public device library, application version coverage, compatibility coverage and bulk QA fixture generation.
- Preserved the original User-Agent generator at `web/generator-legacy.html` with its original generation logic.
- Added a Python FastAPI backend with SQLite local development and PostgreSQL production support.
- Added private admin login, device CRUD, official-source URL validation and application-version editing.
- Added source connector status, enable/pause controls, daily Celery/Redis scheduling and manual source checks.
- Added evidence-bound AI extraction for official device and social application sources; uncertain findings enter the review queue.
- Added trusted Chrome Stable version auto-publishing with strict version validation and audit logging.
- Added proposal approval/rejection, AI assistant, source history and administrative audit log.
- Added Docker Compose, environment template, deployment guide, technical specification and automated tests.
- Kept the existing Android source and device JSON data unchanged in this release.


## 2026-11-25 — Website User-Agent Generator UX v2
- Reworked the public entry point into a focused generator with Android/iOS/Both platform selection.
- Added batch presets and clearer combined quantity controls up to 500.
- Improved random/selected/balanced/custom device selection and per-device quantity behavior.
- Added browser brand logos and Android WebView output support.
- Added opt-in in-app app selection with app logos, version/build metadata and candidate-only Facebook/Instagram format previews.
- Kept candidate app formats disabled until Workstream B verification.
- Added 148 language/locale options from the supplied reference, full UA result cards, filtering and TXT/JSON/CSV export.
- Added optional UI sounds, motion transitions and reduced-motion support.
- Restricted public navigation to the generator; catalog and admin pages remain administrator-only.
- Preserved the device catalog, UA template specification and Android app source.
