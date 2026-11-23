# NEXORA Next — Technical Specification

**Version:** 1.0.0 implementation baseline  
**Product mode:** Public catalog and generator + private administrator control center  
**Primary objective:** Make the device/application compatibility catalog centrally editable, automatically monitored and AI-assisted without requiring manual source-file edits.

## 1. Product scope

### Public capabilities

1. Explore Android and iOS-emulation device records.
2. Search by model, brand and model identifier.
3. Review stored application-version metadata and clearly see missing values.
4. Generate bulk compatibility QA fixtures from existing catalog records.
5. Export generated datasets as JSON, CSV or newline-delimited JSON.
6. Read the current catalog version and update timestamp.
7. Access the preserved legacy User-Agent generator as a separate page.

### Private administrator capabilities

1. Sign in using server-configured credentials.
2. Create, edit and delete device records.
3. Edit application-version metadata.
4. Inspect configured official information sources.
5. Run source monitoring on demand.
6. Review, approve or reject proposed data changes.
7. Use the AI assistant to analyze catalog quality and suggest changes.
8. Inspect audit history and recent automation runs.

### Explicitly deferred

- Android application API sync client (the API is prepared, but the app is unchanged in this release).
- Public user accounts and personal workspaces.
- Multiple administrator roles, SSO and MFA.
- Billing, paid plans and quotas.
- Fully autonomous discovery of every manufacturer and social application version.
- Browser fingerprint or account-identity spoofing features in the new Bulk Studio.

## 2. Architecture

```text
                  OFFICIAL SOURCE CONNECTORS
                    |       |       |
                    v       v       v
               SOURCE MONITOR / FETCH LAYER
                          |
             +------------+-------------+
             |                          |
             v                          v
     TRUSTED STRUCTURED DATA       SOURCE PAGE TEXT
             |                          |
             v                          v
       STRICT VALIDATOR          OPTIONAL AI EXTRACTION
             |                          |
             |                    PROPOSAL QUEUE
             |                          |
             +------------+-------------+
                          |
                    ADMIN APPROVAL
                          |
                          v
                 CENTRAL POSTGRESQL
                          |
             +------------+-------------+
             |            |             |
             v            v             v
        PUBLIC API    ADMIN API     AUDIT / HISTORY
             |            |
             v            v
       PUBLIC WEBSITE  PRIVATE CONTROL CENTER
             |
             v
        BULK STUDIO / EXPORTS
```

### Technology baseline

- Frontend: static HTML, CSS and vanilla JavaScript for this migration release. The existing `styles.css` design system is reused and completed rather than introducing a framework build pipeline.
- Backend: FastAPI and Pydantic.
- ORM: SQLAlchemy 2.
- Production database: PostgreSQL.
- Local development database: SQLite fallback.
- Background processing: Celery with Redis and Celery Beat.
- AI: configurable OpenAI-compatible chat-completions provider. Provider credentials remain server-side.
- Deployment: static frontend on Vercel or FastAPI static serving; API and workers on a Python-capable host or Docker deployment.

FastAPI is used for the first API implementation because it is lightweight, typed and straightforward to deploy beside the existing static frontend. The data/API contracts are framework-independent.

## 3. Data model

### `devices`

- `id` — stable kebab-case identifier, primary key.
- `platform` — `android` or `ios_emulation`.
- `brand`, `model` — normalized display labels.
- `record` — JSON document following the existing schema-v2 device record.
- `source_status` — legacy, admin verified or AI reviewed.
- `created_at`, `updated_at` — timestamps.

The JSON record preserves existing fields: `id`, `brand`, `model`, `uaModel`, `soc`, `gpu`, `cores`, `display`, `ramGb`, `storageGb`, `osVersions`, `builds` and optional `markets`.

### `nexora_meta`

Stores the existing metadata block, including schema version, data version, update date, browser versions and application version map.

### `sources`

Stores source identity, category, official URL, trusted flag, last check/success timestamps, status and last observed details.

### `change_proposals`

Stores AI/source/manual candidate changes. Every proposal includes kind, title, confidence, payload, evidence, status and decision timestamp.

### `audit_logs`

Stores actor, action, target, change details and timestamp for admin changes and trusted auto-publishing.

### `refresh_runs`

Stores each source-monitor execution and its status/summary.

## 4. Data lifecycle and automation policy

### Source categories

- Structured trusted API: may auto-publish only for a connector explicitly configured as trusted and only after strict validation and monotonic version checks.
- Official product/support pages: monitored; AI may extract candidate facts from retrieved source text when AI is configured. Candidate device rows require complete schema and a review proposal.
- App release pages: candidate version changes require evidence and review unless a dedicated structured connector is later implemented and approved for auto-publish.
- Unknown or third-party sources: not configured for auto-publishing.

### Current connector behavior

| Connector | Retrieval | Current behavior | Publish policy |
|---|---|---|---|
| Google Chrome Version History API | Structured JSON | Detects Android Chrome Stable version | Auto-publishes a valid newer four-part version |
| Mozilla Product Details | Structured JSON | Detects Firefox version | Creates review proposal |
| Android releases page | Official page text | Stores observation; optional AI extraction | Review only |
| Apple device support | Official page text | Stores observation; optional AI extraction | Review only |
| Samsung Galaxy | Official page text | Stores observation; optional AI extraction | Review only |
| Motorola smartphones | Official page text | Stores observation; optional AI extraction | Review only |
| Google Play listings for Facebook, Facebook Lite, Instagram, TikTok, Snapchat, WhatsApp, Telegram, Messenger, LinkedIn, Discord, X, Threads, Pinterest, Reddit, Weibo, WeChat, LINE, Kik and Viber | Official app listing pages | Stores observation; optional AI version extraction | Review only |

A source page returning text does not mean its data has been extracted or verified. AI extraction is only enabled when a server-side provider key is configured. It is instructed to return only complete facts explicitly present in the source, with exact field-level evidence quotes. The backend verifies those quotes against fetched source text and validates candidate values before creating a proposal. Unsupported or incomplete candidates are discarded.

### Trusted auto-publish guardrails

1. Source must be configured as trusted.
2. Connector must return structured data in its expected schema.
3. Version value must match the connector's strict format.
4. Version must be newer than the currently published value.
5. Change must be audited.
6. Data version and update date are incremented.
7. No device specifications are auto-inferred.

## 5. AI agent responsibilities

### Research/extraction agent

- Receives content only from allowlisted official source domains.
- Extracts complete device facts when present.
- Must not infer missing fields.
- Returns structured JSON candidates, not direct database mutations.
- Candidate output is validated by deterministic Python code.

### Data normalization agent

- Can normalize casing and labels in a proposed record.
- Must preserve model identifiers and technical values exactly as evidenced.
- Cannot create source citations or claim unsupported verification.

### Quality assistant

- Answers administrator questions about data coverage.
- Explains missing fields and validation rules.
- Suggests a plan or draft changes.
- Does not execute writes or publish data.

### Update coordinator

- Schedules source jobs and reports status.
- Routes uncertain changes to review.
- Does not bypass validation or approval.

## 6. API contract

### Public endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/v1/health` | Service and DB health |
| GET | `/api/v1/data` | Full schema-v2 catalog |
| GET | `/api/v1/stats` | Public catalog counts |
| GET | `/api/v1/devices` | Filtered device search |

### Admin endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/v1/admin/login` | Create 12-hour signed session |
| GET | `/api/v1/admin/overview` | Admin overview and pending count |
| POST/PUT/DELETE | `/api/v1/admin/devices` | Device CRUD |
| POST | `/api/v1/admin/app-versions` | Update app version metadata |
| GET | `/api/v1/admin/sources` | Source connector status |
| GET | `/api/v1/admin/runs` | Refresh job history |
| POST | `/api/v1/admin/refresh` | Queue a source-monitor run |
| GET | `/api/v1/admin/proposals` | Review proposals |
| POST | `/api/v1/admin/proposals` | Create proposal |
| POST | `/api/v1/admin/proposals/{id}/decision` | Approve/reject proposal |
| GET | `/api/v1/admin/audit` | Audit history |
| POST | `/api/v1/admin/ai/assist` | AI analysis response |

Admin endpoints require `Authorization: Bearer <token>`.

## 7. Security requirements

- Admin password and HMAC secret are environment-only.
- Session tokens expire after 12 hours.
- Password comparisons use constant-time comparison.
- Admin endpoints require bearer authentication.
- AI provider key is never sent to the browser.
- Source URL fetches are restricted to a fixed official-domain allowlist.
- Device records are validated on the server before create/update/approval.
- Proposal approval is an explicit admin action.
- Admin changes and source auto-publishing are audited.
- Production requires HTTPS, restricted CORS, rate limiting, backups, secret rotation and database migration management.

## 8. Bulk Studio behavior

The new generator builds compatibility QA fixtures by enumerating actual device × supported OS combinations from the central catalog. It does not invent unsupported combinations. Each fixture contains catalog identity, platform, OS, chipset, GPU, cores, display, RAM/storage options, build ID (nullable), markets, source status and data version.

- Filters: platform, manufacturer, model query and OS version.
- Quantity: 1–500 fixtures per client-side batch.
- Preview: first 15 rows.
- Exports: JSON, CSV and NDJSON.
- Missing firmware build IDs remain null and are shown as missing.
- The original User-Agent generator is preserved at `/generator-legacy.html`.

## 9. Deployment topology

Recommended production topology:

- Frontend: Vercel static site (`web/`).
- API: Python host or container.
- Database: managed PostgreSQL.
- Worker: Celery worker container.
- Scheduler: Celery Beat container.
- Broker: Redis.

For small/self-hosted deployments, `docker-compose.yml` runs all services. The frontend can also be served from FastAPI at `/` for a single-origin deployment.

## 10. Release acceptance criteria

- Existing device data loads from the central API or bundled fallback.
- Public visitors can search and export the catalog.
- Public visitors can generate a dataset from actual device/OS combinations.
- Admin login is disabled unless a password and signing secret are configured.
- Admin CRUD validates required fields and rejects duplicate IDs.
- Admin changes bump data version and write audit events.
- Source monitoring isolates individual connector failures.
- Trusted Chrome version updates can publish only after strict format and newer-version checks.
- AI-extracted device records and social application versions become evidence-bound review proposals and never publish directly in this release.
- Proposal approval validates the record again before publishing.
- Legacy generator and Android source remain present in the archive.
