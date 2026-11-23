# NEXORA Next Deployment Guide

## Local single-service setup

```bash
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\\Scripts\\activate
pip install -r requirements.txt
cp .env.example .env
# Edit NEXORA_ADMIN_PASSWORD and NEXORA_SECRET_KEY
uvicorn backend.app:app --reload --port 8000
```

Open `http://localhost:8000`. The API docs are at `/docs`.

## Docker setup

```bash
cp .env.example .env
# Set POSTGRES_PASSWORD, NEXORA_ADMIN_PASSWORD and NEXORA_SECRET_KEY in .env
docker compose up --build -d
docker compose logs -f api worker scheduler
```

The compose file starts PostgreSQL, Redis, API, Celery worker and Celery Beat.

## Vercel frontend + separate API

1. Set Vercel project root to repository root and output directory to `web` (as configured in `vercel.json`).
2. Set `web/config.js` `apiBase` to the deployed API origin. Example: `https://api.example.com`.
3. Set backend `CORS_ORIGINS` to the exact Vercel production domain and any preview domains you intentionally support.
4. Deploy the API separately with Python 3.12, `pip install -r requirements.txt`, then `uvicorn backend.app:app --host 0.0.0.0 --port $PORT`.
5. Configure managed PostgreSQL and the production environment variables.
6. Deploy a separate worker and scheduler process using the commands in `docker-compose.yml`, or equivalent platform process definitions.
7. Enable HTTPS and hosting-level rate limiting on public API endpoints.

## Required production secrets

- `NEXORA_ADMIN_PASSWORD`
- `NEXORA_SECRET_KEY` (at least 32 random characters; use a cryptographically secure generator)
- `DATABASE_URL`
- Optional `AI_API_KEY`, `AI_API_BASE_URL`, `AI_MODEL`
- `CORS_ORIGINS`
- `CELERY_BROKER_URL` and `CELERY_RESULT_BACKEND`

## Backups and operations

- Enable managed PostgreSQL automated backups and test restore procedures.
- Monitor API errors, Celery task failures and source connector warnings.
- Rotate administrator password and signing secret when needed; signing-secret rotation invalidates active sessions.
- Back up the catalog before manual bulk imports.
- Add formal Alembic migrations before changing the database schema after production launch.
- Add per-user accounts, MFA and role-based access before inviting additional administrators.
