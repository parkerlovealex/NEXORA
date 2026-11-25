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


## Website UX v2 rules

- The public landing page is the focused User-Agent generator only. Do not add theme pickers, IP tools, SEO/marketing sections or unrelated widgets.
- Keep the Android / iOS / Both platform switch, combined 500-record batch limit, quick presets and four device distribution modes.
- Use real Simple Icons brand marks for Android, Apple, browser and app brands, with a text fallback if the icon CDN is unavailable.
- Browser-only is the default. In-app generation is explicitly opt-in and must not add app-specific tokens when the toggle is off.
- Only `verified` records from `ua_templates.json` are selectable. Facebook/Instagram Python scripts and AppUa.kt are candidate-format references only. The useragent.in corpus must be fetched by discovering its category file from its index; never guess the filename or claim its 108 rows were collected unless raw rows are preserved.
- TXT export must contain one raw User-Agent per line. JSON/CSV may include metadata.
- Keep all app-version and device data source-backed. Do not promote the UserAgentGen snapshot's browser versions into verified database values.
- Device Catalog, App Versions and system administration are administrator-only in the website.
- Read `docs/WEBSITE_UX_REDESIGN_V2.md` before changing the public generator.
