# NEXORA --- Project Context & Handoff

**Purpose:** Persistent project memory for ChatGPT, coding agents,
researchers and developers.\
**Last updated:** 2026-11-28 (Alpha30 Wave 2 checkpoint)\
**Status:** Living document --- update after every meaningful milestone.

> Read this file before planning or changing NEXORA. Distinguish
> confirmed repository facts from user/agent-reported status. Never
> claim that a build, test, deployment or device check passed unless you
> actually ran or inspected it.

## 1. Project mission

NEXORA is intended to become a fast, secure, genuinely usable mobile
browser with profile management, database-driven User-Agent generation,
proxy management, privacy diagnostics and eventually deeper
browser-engine-level controls.

The product should be practical and honest: - A usable browser comes
before cosmetic feature expansion. - Every visible control must work or
clearly state that it is unavailable/planned. - Never show fake success
messages or pretend a capability exists. - JavaScript User-Agent changes
are not hardware or engine-level fingerprint modification. - Do not
claim hardware-level anti-detect capabilities unless they are
implemented and independently verified.

NEXORA is intended to be free and usable without mandatory customer
registration or a paid customer cloud-profile service.

## 2. Repository boundaries and source of truth

There are separate project concerns. Do not confuse them:

1.  **Database repository:** GitHub is the source of truth for public
    technical catalogs consumed by the app.
2.  **Android application source:** The active Alpha application
    source/build line is separate from the old Android source that may
    exist in the database repository or older archives.
3.  **Project documentation:** This file and `AGENTS.md` preserve
    context and working rules.

Do not assume the database repository contains the current Android
application source. An old app module or old version number in the
database repository does not establish the version installed on the
user's phone.

The database is not a customer cloud backend. Do not introduce Supabase,
Firebase, PostgreSQL hosting or a customer profile service unless the
owner explicitly changes the product direction. Browser profiles and
saved generation batches are intended to be stored locally.

## 3. Current project status

The following is the latest status reported by the project owner/agent
as of 2026-11-25. Verify against the actual repository or device before
treating it as independently confirmed.

### Workstream A --- Device and application database

**Status: COMPLETE --- do not reopen.**

Reported state: - Database version: `2026.11.25.6` - 59 devices and 18
applications. - Published to GitHub `main`. - Both `devices.json` copies
updated. - Live website checked. - Validator: 0 errors, 0 warnings. -
Evidence retained.

Do not re-plan or re-recommend Workstream A. Do not modify the published
device/build/app-version data as part of Workstream B or C. Only revisit
A if the owner explicitly requests it or a concrete regression is
demonstrated.

### Workstream B --- In-app User-Agent template collection

**Status: Specification reported written; collection waits for owner
approval.**

Reported specification path: `docs/UA_TEMPLATES_SPEC.md`.

Important: The specification file's existence has been verified in the
actual GitHub repository: `docs/UA_TEMPLATES_SPEC.md` on branch `main`
(commit b2805ad, raw content inspected 2026-11-25). In any other working
copy or archive, still inspect before assuming — if missing there,
report that rather than pretending it exists.

The intended specification includes: - Separate `ua_templates.json`
catalog. - Resolvable slots only. - Statuses such as candidate, pending,
verified and outdated. - Two independent sources required for verified
status. - Evidence attached to every verified template. - Collector and
validator pipeline. - AI may extract/normalize/flag conflicts but must
never invent tokens or missing values. - Existing Facebook and Instagram
Python scripts are candidate starting material, not verified truth.

Collection must not begin until the owner has reviewed and explicitly
approved the specification. If approval is unclear, pause production
collection and ask.

The 18 application records in the current database are: Facebook,
Facebook Lite, Instagram, TikTok, X, Threads, LinkedIn, Pinterest,
Reddit, Snapchat, Weibo, Telegram, Messenger, WeChat, LINE, Kik, Discord
and Viber.

The current database's app version/build records are NOT app-specific
User-Agent templates. A known app version/build does not prove what its
embedded browser emits.

### Workstream C --- Android application integration

**Status: Planned app-build work, separate from database collection.**

Integrate the verified `ua_templates.json` catalog into the active Alpha
application line after B has usable verified data.

Expected work: - Connect app version/build data and verified UA
templates without conflating them. - Show all 18 app choices with honest
availability states. - `Ready` only when a compatible verified template
exists. - `Pending` when app version/build data exists but a verified UA
template does not. - `Outdated` when the template no longer matches
supported app versions. - Do not generate or apply invented app
tokens. - Fix country/language suggestion behavior: selecting United
States should suggest `en-US`; country and language should remain
independently customizable. - Test database refresh and
stale-cache/version reporting. - Test both standalone UA generation and
transfer into Profile Studio.

The older Python scripts for Facebook/Instagram are only candidates
until independently verified.

## 4. Product architecture and user experience

### Main product areas

-   Browser / Browser Workspace
-   Profile Studio / Profile Manager
-   Standalone User-Agent Generator
-   Proxy Center
-   Privacy & Diagnostics
-   Automation Center (future/partial)
-   Extension Center (engine-dependent/future)
-   Settings & System
-   Database/catalog status

Avoid dead navigation items. Every menu/button needs a working
destination, or an honest disabled/planned state.

### Profile Studio --- approved direction

Use a clear, premium, restrained interface and a three-step creation
flow.

**Step 1 --- Quantity and OS** - Android and iOS can be selected in the
same batch. - Android and iOS quantities are configured independently. -
Total quantity is calculated automatically. - Profile naming/prefix may
be offered. - Performance limits must be measured on real devices.

**Step 2 --- Device and browser configuration** - Device selection:
random from eligible catalog, specific device selection, balanced
distribution, or custom per-device quantities. - Searchable device
picker. - OS version, browser family/version, locale and compatibility
filters. - In-app browser selection is optional and empty by default. -
Selecting Chrome/Safari/etc. must NOT automatically include
Facebook/Instagram or other embedded-app User-Agents.

**Step 3 --- User-Agent source** - Automatic compatible generation
(default). - Reuse a saved batch from the standalone User-Agent
Generator. - Paste/import UA records, followed by validation. - Show
compatibility and source/database revision before creation.

Important platform truth: - NEXORA's Android app cannot create a genuine
iOS runtime/profile merely by selecting iOS. - iOS selections on Android
are UA/configuration templates for testing or export, unless a separate
native iOS app/runtime exists. - Do not label iOS UA templates as
genuine iOS device execution.

### Standalone User-Agent Generator --- approved direction

It is a separate tool, not buried inside Profile Studio.

Required behavior: - Android and iOS can be generated together. -
Independent quantity per OS. - Device pool can be random or restricted
to specifically selected models. - Distribution modes: random, balanced,
custom per-device quantity. - Browser family selection is separate from
in-app browser selection. - In-app browser list is optional and none are
selected by default. - If no in-app browser is selected, generate only
standalone browser UA templates; do not include app-specific tokens. -
App choices should be driven by the verified template catalog, not
hardcoded assumptions. - Locale/country selection with sensible defaults
and independent language customization. - Copy individual, copy all,
export and save reusable batches. - Direct transfer of saved batches
into Profile Studio. - Proposed UA batch maximum: 500 records; enforce
tested limits and avoid memory/performance issues.

App-specific UAs are only included when explicitly selected and
supported by verified evidence. Do not treat a generic WebView UA as a
verified Facebook, Instagram or other app UA.

### Shared generation engine

Profile Studio and the standalone generator should share: - Database
client/catalog loader. - Compatibility rules. - UA template resolver. -
Validation. - Batch model and local saved-batch store.

Do not duplicate generation logic across two screens.

## 5. Browser and network reliability --- top product priority

The owner has previously reported serious browser issues: -
Search/navigation pages stuck loading, including DuckDuckGo. - Other
websites also stuck loading. - Direct browsing without proxy taking
roughly 6--10 seconds in one test. - Proxy assignment not behaving as
expected in bulk and single-profile scenarios. - No obvious Home
navigation control, making it difficult to return home and test
direct/proxy behavior. - Difficulty determining whether a profile is
actually using its assigned proxy.

Treat these as unresolved user-reported defects unless the active source
and real-device tests demonstrate they are fixed.

Browser reliability work must include: - Direct browsing without
proxy. - HTTPS and redirects. - DNS resolution and timeouts. - Search
engine navigation. - Loading indicator and cancellation. - Clear
recoverable error states. - Home button and predictable navigation. -
Back/forward behavior. - Proxy on/off state visible to user. - Actual
exit-IP verification. - No silent direct fallback when a proxy is
required. - Network failure recovery.

Do not claim browsing is fixed based only on compilation or unit tests.
Verify on a real Android device.

## 6. Proxy architecture and profile isolation

The owner expects per-profile proxy assignment and isolation.

Important technical risk: Android System WebView proxy configuration can
be app-wide rather than independently scoped to each WebView/profile.
Verify the exact APIs and implementation in the active source. Do not
claim simultaneous independent proxy contexts until proven.

Test: - Authenticated HTTP and SOCKS5 transport as supported by the
chosen architecture. - Profile-to-proxy assignment. - Bulk sequential
assignment and deduplication. - Proxy connectivity and actual exit IP. -
Failure behavior and no-direct-fallback policy. - DNS/WebRTC leak
behavior. - Profile switching and network state. - Cross-profile
cookie/storage isolation.

If the current engine cannot provide the promised isolation, document
the limitation and present an architectural decision: constrained single
active proxied profile versus migration to an engine/network
architecture that can support independent contexts.

Do not fabricate MAC changes. Do not label generated identifiers as
actual Android hardware MAC modifications.

## 7. Fingerprint and engine strategy

The current architecture has been described as Android System WebView.
Verify the active app source before relying on this statement.

WebView-level JavaScript overrides cannot provide complete
hardware-level fingerprint control.

Long-term objective: - Investigate a customized Chromium-based engine if
true engine-level fingerprint and independent network-context control
are essential. - Keep WebView capabilities and limitations accurately
documented. - Advertise only protections that have been implemented and
independently tested. - Do not claim that changing a UA changes the real
device, OS, GPU, hardware or browser engine.

## 8. Beta 0.1 release gates

NEXORA is not Beta 0.1-ready until the release gates are demonstrated on
supported real Android devices.

Required gates: 1. Reliable direct browsing, HTTPS, search, redirects
and error recovery. 2. Proxy routing verified by actual exit IP and
failure/leak tests. 3. Profile cookie, storage and session isolation
demonstrated. 4. Bulk profile creation, persistence and restart behavior
verified. 5. UA generation and saved-batch transfer verified. 6.
Fingerprint/privacy claims tested and accurately scoped. 7. DNS/WebRTC
and network leak tests completed. 8. Backup/restore verified. 9.
Performance and long-session stability tested. 10. Full Gradle build,
APK assembly and lint checks pass. 11. Signing continuity and upgrade
installation verified. 12. Regression suite and release notes completed.

Do not use arbitrary completion percentages as proof of readiness.
Maintain a gate-by-gate status with evidence.

## 9. Working rules for every AI agent

At the start of every new chat/task: 1. Read `AGENTS.md`. 2. Read this
`PROJECT_CONTEXT.md`. 3. Inspect the actual repository, branch and
current files. 4. Check recent commits and open PRs if GitHub access is
available. 5. Identify the active Android source versus the database
repository. 6. Summarize the current state and next task before making
changes.

During work: - Separate facts into `verified`, `reported`, `planned` and
`unknown`. - Do not infer that a file exists from a prior chat or agent
claim; inspect it. - Do not claim tests/builds/device checks passed
unless executed. - Do not invent commits, hashes, PR links, APKs or
download artifacts. - Preserve existing working behavior and test
before/after. - Make focused changes on a branch. - Keep evidence and
reports for data collection. - Never expose signing keys, API keys,
proxy credentials or other secrets. - Do not change Workstream A or
merge data without explicit owner authorization. - Ask one concise
question only when a genuinely blocking decision is missing. - Prefer
implementation and verified deliverables over repeated planning. - No
fake buttons, fake success states or misleading feature labels.

After every milestone: - Update this file's status and date. - Record
files changed and commit/PR references. - Record tests actually run and
their results. - Record remaining defects and next milestone. - Keep
completed work closed unless a regression is proven.

## 10. Website status — User-Agent Studio redesign

**Status: Implemented in the uploaded website source package; deployment not verified.**

The website root (`web/index.html`) now opens directly to a premium, mobile-first User-Agent Studio rather than the old device-intelligence overview.

Implemented in this source package:
- Mixed Android/iOS quantities with a combined 500-record limit.
- Random, selected-pool, balanced and custom per-device distribution.
- Searchable device catalog and per-platform OS version selection.
- Platform-specific browser family selection.
- Chrome Android version sourced from the current database; other browser versions require explicit user input until a verified browser-version catalog exists.
- Optional in-app browser catalog showing all 18 apps. Because this repository does not contain `web/ua_templates.json`, all app templates remain Pending and disabled. No app-specific tokens are fabricated.
- Country filters the catalog by supported market; language/Accept-Language metadata, duplicate removal, copy, TXT/CSV/JSON export and browser-local saved batches.
- Optional interface sounds (off by default) and reduced-motion support.
- Admin System Overview with API/catalog/device/app/template/source/review status, plus existing device, app, compatibility, automation, review, AI, audit and settings pages.

Important boundaries:
- Browser UA outputs are compatibility templates, not hardware-level fingerprint modification.
- iOS output is a reference UA template, not native iOS runtime emulation. Standard iPhone UA strings do not identify exact iPhone models.
- Country/language metadata is not inserted into standard UA strings.
- No Android APK changes are included.
- Deployment is not verified. Static Vercel hosting needs `NEXORA_CONFIG.apiBase` pointed at the FastAPI service for admin APIs; bundled catalog fallback remains read-only.

Full details: `docs/WEBSITE_GENERATOR_REDESIGN.md`.

## 11. Immediate next steps

1.  Inspect the actual database repository and confirm whether
    `docs/UA_TEMPLATES_SPEC.md` exists on the intended branch.
    Workstream A remains closed.
2.  Obtain/confirm the owner's explicit approval of the UA template
    specification.
3.  Execute Workstream B: implement collectors, parser, AI
    normalization, validator and evidence reports; investigate all 18
    apps; keep unsupported templates pending; prepare a PR without
    merging.
4.  Execute Workstream C in the active Android app source: integrate
    verified templates, fix the `en-US` locale suggestion and test
    standalone/bulk generation.
5.  Continue browser reliability, proxy verification and
    profile-isolation work toward Beta 0.1.

------------------------------------------------------------------------

**Canonical project direction:** NEXORA should be a fast, secure,
honest, database-driven browser. GitHub stores the shared technical
catalog; the Android app consumes it; user profiles and saved generation
batches remain local. Workstream A is complete. B is verified app-UA
data collection. C is app integration. Browser reliability and
real-device verification remain essential to Beta 0.1.


## Current handoff — Website UX v2 (2026-11-25)

The website source was redesigned from the supplied UserAgentGen saved page and the supplied `User-Agent.zip` Python scripts. The public entry point is now a focused generator, not a general data dashboard.

- Platform: Android / iOS / Both.
- Batch: 10/25/50/100/250/500 presets; combined max 500.
- Device modes: Random / Selected / Balanced / Custom; searchable brand/model list.
- Browser families: Android Chrome/Firefox/Edge/Opera/WebView; iOS Safari/Chrome/Firefox/Edge.
- App mode: explicit opt-in. All 18 apps are listed; only verified `ua_templates.json` entries are selectable.
- The supplied Facebook/Instagram scripts are shown as candidate format previews only. They do not become verified templates and randomized app IDs/build suffixes are not treated as official data.
- Output: full UA preview, copy-one/copy-all, TXT one UA per line, JSON/CSV and local saved batches.
- Locale: 148 reference language options; country filters catalog markets.
- Public nav is generator-only; device/app catalogs and all admin tools are behind sign-in.
- Single graphite/mint design; no theme picker or unrelated public tools.

Verification: JS syntax passed; HTML has no duplicate IDs/missing direct DOM IDs; generator VM tests passed for Android, mixed Android+iOS, selected, balanced, custom, WebView and dedupe; 20 backend API/collector tests passed; device validator reports 59 devices / 0 errors / 0 warnings.

Browser visual test is not claimed: Chromium navigation to local HTTP/file URLs was blocked with `ERR_BLOCKED_BY_ADMINISTRATOR`. Production deployment is not verified.

No changes were made to `web/devices.json`, Android source/APK or `docs/UA_TEMPLATES_SPEC.md`. Workstream B still owns in-app template collection and verification.

## Update — 2026-11-25 (website fix rounds, owner-directed, live-verified)

After the UX v2 deployment, the owner reported the live generator broken and directed four fix rounds. All were built locally, tested, pushed to `main` and verified on https://nexorauser.vercel.app/ the same day:

1. **Init bug (commit d05e011):** `web/app.js` defined `init()` but never called it, so the catalog, app list and Generate never started. Added the invocation; catalog loads (DATA version + device counts) and generation works.
2. **Generator layout + candidates (b62ff30):** result cards restructured (brand logo + model header, full-width UA, source label); stacked-layout CSS fixes; per owner order, the Facebook/Instagram **script-candidate** formats became selectable on the website and are labelled "Script candidate · unverified" on every output. This is an owner-ordered website exception for his own supplied scripts only — it does not change Workstream B, does not make any template `verified`, and `web/ua_templates.json` still does not exist. Other apps remain TEMPLATE PENDING/disabled.
3. **Heading, logo, sign-in (9c68128, 57677a7):** duplicate generator heading block removed (catalog status moved into the topbar); brand marks replaced with the original NEXORA ghost logo (inline SVG from the app's `ic_launcher_ghost` drawable); the Sign in chip is hidden on public pages and shown only on `/admin` (or with an active admin session), where the login dialog auto-opens. `/admin` is served by a `vercel.json` rewrite — note the destination must be `/`, not `/index.html`: with `cleanUrls: true` the `/index.html` destination 404'd (fixed after live verification caught it).
4. **Safari trap + DATABASE panel (70af334):** a checked browser with no version set (e.g. Safari iOS) no longer kills the whole batch — it is skipped with a clear note ("Safari iOS version is required. Open Advanced options.") while other platforms still generate. The DATABASE status panel was removed from the results column per owner order.
5. **Browser versions (f087692, 8307653, 41427be):** database advanced to **2026.11.25.7** (validator: 59 devices, 0 errors, 0 warnings). `meta.browserVersions` now holds the last 5 stable versions for Chrome Android/iOS, Firefox Android/iOS, Edge Android/iOS, Opera Android and Safari iOS, collected 2026-11-25 from vendor sources (Google Version History API, Mozilla product-details + iOS release notes, Microsoft Edge release notes, APKMirror Opera uploads, Apple Safari release notes); sources recorded in `data/evidence/browser-versions-2026-11-25.json`. Each Advanced-options version field offers the 5 as a pick-list; values remain explicit user picks, never auto-inserted. `meta.versions.chrome` updated to 159.0.8154.33.

Live verification 2026-11-25 (real browser): ghost logo present, no public Sign in, no duplicate heading, DATA 2026.11.25.7, no DATABASE panel; mixed 5 Android + 5 iOS batch returns the 5 Android results with the Safari note; picking Safari 27.2 generates iOS strings containing `Version/27.2`. Owner phone confirmation of the final state is still pending.

Addendum (2026-11-25, owner-directed): browser version fields now **default to the latest catalog version** for every browser (previously only Chrome Android was prefilled; the rest waited for manual entry, which the owner flagged as unrealistic for normal users). A **Version mode** control in Advanced options offers "Latest version (default)" or "Random · last 3 versions" — in Random mode each generated result independently rolls one of the newest 3 entries from `meta.browserVersions`. Manual pick/type in any field still overrides in Latest mode.

Addendum (2026-11-25, icon repairs): audited all 38 Simple Icons slugs the site references — 5 return 404 on cdn.simpleicons.org (`microsoftedge`, `linkedin`, `facebookmessenger`, `nothing`, `realme`). Fixes: Edge and LinkedIn logos are self-hosted in `web/icons/` (from the simple-icons package, brand-color fills) and wired via a `LOCAL_ICONS` map in `brandLogo()` plus the two static Edge images; Messenger's slug corrected to `messenger` (the old slug no longer exists); Samsung, Nothing and Realme use the designed letter tile instead — Samsung's only available mark is the full wordmark, illegible at the 24px tile size.

Addendum (2026-11-25, Workstream B function update): the UA template collection pipeline is installed (tools/collect_ua_templates.py, fetch_useragent_in.py, normalize_ua_templates_ai.py, validate_ua_templates.py; tests/test_ua_templates_pipeline.py; quarterly + manual workflow `.github/workflows/ua-templates-collect.yml`, draft PRs only, never auto-publishes). Spec is now v1.1 (approved for collection; publication separately gated). The candidate catalog is published at `web/ua_templates.json` (dataVersion 2026.11.25.4, built against device catalog 2026.11.25.7): 11 records, ALL candidate (Facebook Android/iOS, Instagram Android/iOS, Messenger Android), 0 verified, validator 0 errors / 0 warnings. Website UI unchanged — the generator's existing catalog plumbing reads the file; only `verified` records can become selectable, so generation behaviour is unchanged until verification promotes a record. The useragent.in corpus fetch is still unresolved (HTTP 429 / DNS on the collection side; the workflow retries from GitHub runners and must discover the category filename from index.json — the "108" figure is source-reported, not collected rows).


## Addendum — Workstream B research continuation (2026-11-25)

Research coverage now spans all 18 app keys across Android and iOS (36 platform cells). Three new pending findings were staged: Facebook Android FB_IAB/FB4A no-wv, Facebook iOS FBAN/FBIOS, and Instagram iOS app-token format. The full coverage matrix and blockers are in `reports/workstream-b-research-2026-11-25.md` and `data/ua_templates.research-2026-11-25.json`.

The 11 original candidates remain intact. `data/ua_templates.proposed.json` remains unchanged. `web/ua_templates.json` is not included in this handoff pack; the last reported runtime state in PROJECT_CONTEXT remains 11 candidate / 0 verified, but this pass did not revalidate it. The official collector/validator was not run because those scripts, tests and workflow are absent from this uploaded handoff pack. The useragent.in index remains blocked by HTTP 429; no dataset filename was guessed. Resolve the `.6` versus `.7` device-catalog baseline discrepancy before promotion.


## Desktop UA generator extension — 2026-11-25

**Status: implemented in the supplied `web-current/` source snapshot; production integration not verified.**

The supplied website User-Agent generator snapshot supports four output platforms: Android, iOS, Windows and macOS. `vercel.json` still points to the `web/` output directory, which is not included in this handoff pack. The full repository's `web/` source must be merged with these changes; do not replace it wholesale because it contains newer icon repairs. Desktop support is deliberately separate from Workstream A's device catalog and Workstream B's app-specific in-app UA template catalog.

- Added Windows OS release selection: latest five Windows 11 release trains (26H2, 26H1, 25H2, 24H2, 23H2) and last three Windows 10 feature releases (22H2, 21H2, 21H1).
- Added ten macOS major releases: 27 Golden Gate, 26 Tahoe, 15 Sequoia, 14 Sonoma, 13 Ventura, 12 Monterey, 11 Big Sur, 10.15 Catalina, 10.14 Mojave and 10.13 High Sierra.
- Added five stable browser release entries per supported desktop browser: Chrome, Firefox, Edge and Opera on Windows/macOS; Safari on macOS only.
- Desktop UA builder emits reduced, browser-appropriate platform tokens. It does not claim to expose the selected Windows/macOS release inside the UA string; those selections are output metadata.
- Desktop generation uses virtual desktop records, not physical device rows. Android/iOS device distribution and device catalog are unchanged.
- In-app app-specific UA selection remains mobile-only and governed by `UA_TEMPLATES_SPEC.md`.
- New data: `data/desktop-platforms-2026-11-25.json`; staged website asset: `web-current/desktop-catalog.json` (must be copied/merged into `web/desktop-catalog.json` in the full repository).
- New tests: `tests/desktop-ua-smoke.js`.
- Workstream A `data/devices-2026.11.25.7.json`, Workstream B findings/proposal/catalog, `AGENTS.md`, and Android app source are unchanged.

**Validation performed:** `node --check web-current/app.js` passed. Desktop catalog structural checks passed (5 Windows 11 + 3 Windows 10, 10 macOS, five versions for every supported desktop browser). Desktop UA smoke tests passed for Windows Chrome/Firefox/Edge and macOS Chrome/Firefox/Opera/Safari, plus Desktop/All/Both quantity distribution. HTML ID uniqueness and required desktop control IDs are checked by the smoke test. Visual/browser interaction testing and live deployment remain unverified.

**Next:** merge the changes into the full repository's `web/index.html`, `web/app.js` and `web/styles.css`, preserving the existing `web/icons/` assets and `LOCAL_ICONS` repairs; add `web/desktop-catalog.json`; then test mixed batches and the 500-record cap in preview. Do not modify the Workstream A device catalog to store desktop OS data.



Addendum (2026-11-25, research round applied in the full repository): the official collector and validator were run against the extended findings. First validation FAILED with 2 errors — the Facebook iOS and Instagram iOS pending records carry no normalized template (FBRV, locale and the Instagram runtime ID cannot be slotted from any catalog field), and the validator requires a template for pending records. Resolution, following the collector's existing rejection design: the collector now holds any pending/verified/outdated finding without a normalized template in findings (rejected with reason recorded in the collection report) instead of passing it into the proposal; the two iOS records remain fully preserved in data/ua_templates.findings.json and data/ua_templates.research-2026-11-25.json. Regenerated proposal: ua_templates dataVersion 2026.11.25.5 — 12 records (11 candidate + 1 pending: the observed Facebook Android FB_IAB/FB4A no-wv variant), 0 verified, validator 0 errors / 0 warnings, pipeline tests still green; republished to web/ua_templates.json (no behaviour change: only verified records can become selectable). The .6 vs .7 baseline question is RESOLVED: .7 is the authoritative published device catalog (live-verified 2026-11-25); the spec's baseline line was corrected to .7 — app version/build histories are identical between .6 and .7. The Facebook Android pending pair (588.0.0.42.55 / 707615019) was independently confirmed present in the .7 history; the Facebook iOS pair and Instagram iOS version were confirmed absent, which is why those records cannot advance yet.

Addendum (2026-11-25, desktop extension applied): the Desktop UA extension was audited and merged into the repository's current web/ files (their base was the current live snapshot — all icon repairs, LOCAL_ICONS, ghost logo, /admin gating and version-mode code preserved; NOT a wholesale copy). Spot-verified against independent sources: Windows 11 26H2 build 26300, Nov 10 security build 26300.9974 and Nov 19 preview 26300.10075 all match Microsoft reporting; macOS 27 Golden Gate and its 27.2.1 latest version match Apple's. Live changes: web/app.js, web/index.html, web/styles.css (desktop modes Windows/macOS/Desktop/All, OS selectors, desktop browser lists, desktop counters/filters), NEW web/desktop-catalog.json, cache-bust token bumped to ?v=20261125-9 (the package had left it at -8, which would have mixed stale cached JS with the new HTML on the owner's phone). data/desktop-platforms-2026-11-25.json added as the evidence record; tests/desktop-ua-smoke.js added with its two source paths adapted from the handoff layout (web-current/) to the repo layout (web/). The pack's stale ua_templates.json copy (2026.11.25.4) was NOT applied — the live catalog stays 2026.11.25.5. Desktop Chrome tops at 158 by documented design (159 was Early Stable on desktop as of 2026-11-24); desktop UA platform tokens are frozen/reduced per modern browser behaviour, with the selected OS release carried as output metadata — no compatibility matrix is claimed.

## Addendum — Kotlin UA Studio application (2026-11-25, Muse)

The ChatGPT handoff pack "NEXORA Kotlin UA Studio Update 2026-11-25" was audited and applied on the owner's approval ("Apply it with your fixes"). What went live:

- **New dedicated page `web/ua-studio/`** — a second, separate generator written in Kotlin/JS (`src/Main.kt`, compiled bundle `ua-studio.js` checked in; no build needed to deploy; `build.sh` + README document rebuilding). Own `index.html`/`styles.css` (cache token `20261125-kotlin1`), sidebar layout, all four platforms in one workspace, browser-version strategies incl. random-last-5 and manual entry, Android Chromium engine override, locale/Accept-Language metadata, dedup, result search/filter. Facebook/Instagram Android remain opt-in script candidates, labelled unverified.
- **Main site changed only by:** one sidebar link "Advanced UA Studio" → `./ua-studio/` (plus one CSS rule for it) and the cache-bust token raised to `20261125-10`. `app.js` untouched.
- **Fixes applied vs the pack:** (1) the pack's main `index.html` carried the regressed token `-8` — not taken; (2) the pack's `ua_templates.json` copies were the STALE `.4` (11 candidates) — the Studio's data copy is the live `.5` (12 records incl. 1 pending); (3) the pack's docs were NOT taken (its spec copy still had the superseded `.6` baseline line).
- **UPKEEP RULE:** `web/ua-studio/data/` holds its OWN copies of `devices.json`, `desktop-catalog.json` and `ua_templates.json`. Whenever any main catalog updates, refresh all three Studio copies in the same round or the Studio silently drifts stale.
- Report: `reports/kotlin-ua-studio-2026-11-25.md`; smoke test: `tests/ua-studio-smoke.js` (paths adapted `web-current/` → `web/`; passes, and reports the 12-record .5 inventory).

## Addendum — first VERIFIED template published (2026-11-26, Muse)

On the owner's explicit approval, `facebook-android-fb4a-observed-2026-v1` was promoted **pending → verified** and the template catalog published as **dataVersion 2026.11.25.6** (12 records: 11 candidate + 1 verified; validator 0 errors / 0 warnings; 15/15 pipeline tests pass).

- Verification gate met: TWO independent observed-UA sources agree token-for-token (byte-identical, 165 chars) — avidtrak request log 2026-11-24 and a Goodbox Symfony profiler log 2026-11-24 — plus a HackerNoon technical reference (dated by its publication date, 2026-11-25) confirming the current FB_IAB/FB4A no-`wv` format. The undated user-agents.net item was dropped from this record's evidence (preserved in findings/research files; no date invented). Pair 588.0.0.42.55 / 707615019 stands in catalog .7 history.
- The template is the reduced-UA variant: `Android 10; K` and `Chrome/158.0.0.0` are LITERAL observed tokens (only `{appVersion}` is dynamic) — do not "modernize" them to catalog Chrome 159 without a matching observation.
- The record's stale baseline-discrepancy review note was resolved in the published copy (spec baseline is .7, authoritative).
- Published to BOTH `web/ua_templates.json` and `web/ua-studio/data/ua_templates.json` (upkeep rule). Effect: the generator may now offer Facebook Android as a real **Verified template** in-app option; the other 11 records stay candidate/pending and locked. Website UI unchanged.

## Addendum — Kotlin UA Studio REMOVED (2026-11-26, owner order)

The owner reviewed the live Advanced UA Studio and ordered it removed ("look not good"). Removed: the whole `web/ua-studio/` directory, the "Advanced UA Studio" sidebar link, its one CSS rule, and `tests/ua-studio-smoke.js`. Main cache-bust token → `20261126-11`. The main generator and the template catalog (2026.11.25.6, incl. the verified Facebook Android record in `web/ua_templates.json`) are untouched. The Studio files remain in git history (added in commits 8db1e8a..111, 2026-11-25) and can be restored from there if the owner ever wants them back. The ua-studio data-copy upkeep rule is retired with the page.

## Alpha30 Wave 1 checkpoint (2026-11-27)

The Alpha29 app sync on main is cd7e69a. Wave 1 on fix/alpha30 closes A01, A07–A10, A24–A26, A28, A32 and B03. The test-infrastructure findings A45, A46 and A48 are also fixed. The [register and per-commit test results](reports/alpha30-findings.md) cover all 74 findings. At this checkpoint, 14 are fixed, five are upload artifacts and 55 are open, including 11 High and zero Critical.

On the final Wave 1 run, the debug APK assembled. Of 104 JVM tests, 102 passed and two failed (A22 and A47). Python had 76 passing tests, six passing subtests and one failure (C14). Desktop smoke passed. The published device and UA validators reported zero errors and warnings against web data. Web specs reported 110 warnings. The bundled catalog and strict specs still have outstanding failures recorded in the register. Live provider calls, production responses and physical-device behavior remain UNVERIFIED.

Wave 2 begins with the Android published-template reader. The release target is 0.4.0-alpha30, versionCode 34. The app is still on 0.4.0-alpha29, versionCode 33.

## Alpha30 Wave 2 checkpoint (2026-11-28)

A06, A16, A17, A18, A20, A22 and A36 are FIXED on `fix/alpha30`. Locale selection and explicit OS selection now survive generation. Android reads the published UA templates. Missing catalog hardware stays unknown. If the active catalog supplies no Safari version, the user can explicitly enter a version labelled manual. The relay starts independent requests promptly, applies a shared five-minute idle limit and a one-hour maximum, and direct browsing waits for WebView to confirm proxy clearance.

The register contains 74 findings, including 21 FIXED, 5 UPLOAD ARTIFACT and 48 OPEN. 9 High and 0 Critical findings are open. The final debug APK assembled. JVM ran 120 tests with 119 passing, 1 failing (A47) and 0 skipped. Python reported 1 failed, 76 passed, 1 warning, 6 subtests passed (C14). Desktop smoke passed. Published web devices and UA templates had zero errors and warnings. Web specs and the bundled device, spec and UA checks still have the failures recorded in the register. [Full suite results](https://github.com/parkerlovealex/NEXORA/actions/runs/46114824341) · [Findings register](reports/alpha30-findings.md)

Handset latency, installed WebView routing, manual-entry interaction on a phone and production behavior are UNVERIFIED. Wave 3 awaits owner approval. The version at this checkpoint is 0.4.0-alpha29, versionCode 33.
