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
