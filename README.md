# NEXORA Next

**Public device intelligence and compatibility-data platform with a private AI-assisted control center.**

NEXORA Next upgrades the original NEXORA website while preserving the existing Android project and the original User-Agent generator as a separate legacy page.

## Product decisions

- Public visitors can browse the device catalog, review application-version coverage and generate compatibility QA datasets.
- Only authenticated administrators can edit the shared catalog, change application metadata, run source monitoring, approve proposals or use the AI assistant.
- Trusted structured sources can auto-publish only the narrowly configured version updates that pass validation (currently Chrome Stable from Google's Chrome Version History API).
- New device discoveries and social application version candidates from official Google Play listings become evidence-bound proposals and require administrator approval.
- AI suggestions never directly modify or publish records.
- The Android application is not changed in this release. The API is versioned so Android sync can be added later.

## Project structure

```text
NEXORA-main/
├── app/                         # Existing Android application (preserved)
├── backend/
│   ├── app.py                   # FastAPI, database models, public/admin APIs
│   ├── celery_app.py            # Optional scheduled worker configuration
│   └── tasks.py                 # Background source-monitor task
├── web/
│   ├── index.html               # NEXORA Next dashboard
│   ├── app.js                   # Dashboard, public generator and admin UI
│   ├── styles.css               # Responsive neutral dashboard design
│   ├── config.js                # API origin configuration
│   ├── devices.json             # Bundled public catalog fallback
│   ├── generator-legacy.html    # Preserved original generator page
│   └── generator-legacy.js      # Preserved original generator logic
├── tools/                       # Existing device refresh and validation scripts
├── tests/                       # API and data-management tests
├── docs/TECHNICAL_SPEC.md       # Product and system specification
├── docs/DEPLOYMENT.md           # Local and production deployment
├── Dockerfile
├── docker-compose.yml
├── requirements.txt
└── .env.example
```

## Quick start (local)

### Option A — one-process API + website (SQLite)

1. Use Python 3.12+.
2. Install dependencies: `pip install -r requirements.txt` (and `pip install -r requirements-dev.txt` to run tests).
3. Copy `.env.example` to `.env` and set `NEXORA_ADMIN_PASSWORD` and a random `NEXORA_SECRET_KEY` of at least 32 characters.
4. Start the service: `uvicorn backend.app:app --reload --port 8000`.
5. Open `http://localhost:8000`.
6. Public API documentation: `http://localhost:8000/docs`.

SQLite is a local-development fallback. For a real public deployment, use managed PostgreSQL and configure backups.

### Option B — PostgreSQL + Redis + worker + scheduler

1. Copy `.env.example` to `.env`.
2. Set `POSTGRES_PASSWORD`, `NEXORA_ADMIN_PASSWORD`, `NEXORA_SECRET_KEY` and optionally AI provider settings.
3. Run `docker compose up --build`.
4. Open `http://localhost:8000`.

The Celery Beat scheduler runs the official source monitor once every 24 hours. A manual run is also available from the private Automation Center.

## Environment variables

| Variable | Required | Purpose |
|---|---:|---|
| `DATABASE_URL` | Production | PostgreSQL connection string; SQLite is default for local development |
| `NEXORA_ADMIN_PASSWORD` | Yes for admin | Shared administrator password |
| `NEXORA_SECRET_KEY` | Yes for admin | HMAC session signing key, minimum 32 characters |
| `AI_API_KEY` | Optional | Server-side OpenAI-compatible provider key |
| `AI_API_BASE_URL` | Optional | Provider base URL, defaults to OpenAI API |
| `AI_MODEL` | Optional | Model name, defaults to `gpt-4.1-mini` |
| `CORS_ORIGINS` | Production | Comma-separated allowed frontend origins |
| `CELERY_BROKER_URL` | Worker | Redis broker URL |
| `CELERY_RESULT_BACKEND` | Worker | Redis result backend |
| `NEXORA_DATA_FILE` | Optional | Alternate seed JSON file path |

Never put admin passwords, signing keys or AI provider keys in `web/config.js`, HTML, JavaScript or a public repository.

## Current API surface

### Public

- `GET /api/v1/health`
- `GET /api/v1/data`
- `GET /api/v1/stats`
- `GET /api/v1/devices`

### Administrator (Bearer token required)

- `POST /api/v1/admin/login`
- `GET /api/v1/admin/overview`
- `POST /api/v1/admin/devices`
- `PUT /api/v1/admin/devices/{id}`
- `DELETE /api/v1/admin/devices/{id}`
- `POST /api/v1/admin/app-versions`
- `GET /api/v1/admin/sources`
- `PATCH /api/v1/admin/sources/{id}`
- `GET /api/v1/admin/runs`
- `POST /api/v1/admin/refresh`
- `GET /api/v1/admin/proposals`
- `POST /api/v1/admin/proposals`
- `POST /api/v1/admin/proposals/{id}/decision?decision=approve|reject`
- `GET /api/v1/admin/audit`
- `POST /api/v1/admin/ai/assist`

## Validation and tests

- Validate the bundled catalog: `python tools/validate_devices.py app/src/main/assets/devices.json`.
- Run API tests with environment variables set before import, for example:

```bash
DATABASE_URL=sqlite:////tmp/nexora-test.db \
NEXORA_ADMIN_PASSWORD='test-only-password' \
NEXORA_SECRET_KEY='test-secret-key-that-is-at-least-32-characters' \
pytest -q
```

- Check JavaScript syntax: `node --check web/app.js && node --check web/generator-legacy.js`.
- Check Python syntax: `python -m compileall backend tools`.

## Important current limitations

- The AI assistant and AI extraction require a configured server-side AI API key. Without it, the public catalog and admin data tools work, but AI requests return a clear configuration error.
- Source monitoring checks official connectors, including Google Play listings for the supported social and messaging application catalog. Chrome Stable is currently the only source allowed to auto-publish. Mozilla version changes and AI-extracted device/application candidates are queued for approval. Official page content is not treated as verified data unless exact evidence quotes and deterministic validation pass.
- The current admin authentication is a single shared administrator password with 12-hour signed sessions. It is suitable as a first private control plane, not a multi-user enterprise identity system. Add per-user accounts, MFA and role-based permissions before inviting a large editor group.
- The public generator creates structured QA compatibility fixtures. The original User-Agent generator is preserved separately. The new generator does not fabricate platform fingerprints or claim that unknown application versions are verified.
- API rate limiting, CAPTCHA/abuse controls, managed backups, observability and database migrations must be configured before high-traffic production launch.

See [Technical Specification](docs/TECHNICAL_SPEC.md) and [Deployment Guide](docs/DEPLOYMENT.md).
