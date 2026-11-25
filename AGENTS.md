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
