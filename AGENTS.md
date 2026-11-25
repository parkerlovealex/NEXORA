# Instructions for AI Agents Working on NEXORA

Before doing any NEXORA work:

1.  Read `PROJECT_CONTEXT.md` at the repository root. It is the
    canonical project handoff and records the mission, architecture,
    completed work, current status, constraints and roadmap.
2.  Inspect the actual repository, branch, files and recent commits.
    Never assume a file exists because a previous chat or agent
    mentioned it.
3.  Clearly distinguish `verified`, `reported`, `planned` and `unknown`
    facts.
4.  Do not reopen Workstream A (database publication); it is complete
    unless the owner explicitly requests changes or a real regression is
    demonstrated.
5.  Workstream B is app-specific User-Agent template collection. Read
    `docs/UA_TEMPLATES_SPEC.md` if it exists. Production collection
    requires explicit owner approval. Facebook/Instagram scripts are
    candidates, not verified truth.
6.  Workstream C is Android app integration and belongs to the active
    Alpha application source, which may be a separate repository from
    the database.
7.  Never invent User-Agent tokens, evidence, source quotes, test
    results, APKs, commits, hashes or PR links.
8.  Do not claim browser, proxy, profile isolation, fingerprint or
    hardware-level capabilities without real implementation and
    appropriate tests.
9.  Do not modify completed database data or merge/publish new data
    without owner authorization.
10. After each milestone, update `PROJECT_CONTEXT.md` with the date,
    actual changes, test results, remaining defects and next steps.

Prefer small, tested, reviewable changes. If context is missing, inspect
the repository or ask a focused question instead of guessing.


## Website work

- The website root is the User-Agent Studio; do not restore the old overview as the public landing page.
- Read `docs/WEBSITE_GENERATOR_REDESIGN.md` before changing the website generator.
- Keep mixed Android/iOS quantities, device selection modes, optional app templates, exports and local saved sets.
- App-version data is not app-UA evidence. Do not enable an in-app app unless its template status is `verified` in `web/ua_templates.json`.
- Browser versions not present in the database must remain explicit/manual; do not silently insert guessed versions.
- The Admin System Overview is private and uses existing admin APIs. Never expose secret values in frontend code.
- Website changes do not imply Android APK changes or production deployment.
