# Alpha30 — Handover Checkpoint

Written by Muse on 2026-11-28 after Codex's credit ran out (weekly limit,
resets Fri Dec 4, 2026, 10:09 AM +06). The campaign continues under Muse,
with step approvals from the owner's ChatGPT chat. This file replaces the
checkpoint Codex was asked to write but could not commit.

## State

- Branch: `fix/alpha30` at `e8f9a80954e9b35703831e2e91f8ecf33c710f5b`
- App: still 0.4.0-alpha29, versionCode 33. Target: 0.4.0-alpha30, versionCode 34.
- Register (`reports/alpha30-findings.md`): 21 FIXED, 5 UPLOAD ARTIFACT,
  48 OPEN (0 Critical, 9 High, 30 Medium, 9 Low).

## Done

- Step 0 re-baseline: A02, A03, A04, A05 closed as UPLOAD ARTIFACT with
  evidence; the restored Alpha29 app builds.
- Wave 1 (security + data loss): A01, A07, A08, A09, A10, A24, A26, A28,
  A32, B03 — all FIXED.
- Wave 2 (app correctness): A17 (15438f7), A06 (1f6ab8a), A16 (6012373),
  A18 (30c5bbb), A20 (4dbd5df), A22 (259bf7a), A36 (e8f9a80) — all FIXED,
  each with its own commit and a full-suite rerun recorded in the register.

## Remaining scope (in order, from CODEX-MASTER-FIX-PROMPT.md)

- Wave 3 — backend and data integrity: fresh-install seed failure
  (Phase 3 F01/F08) + A34; bundled catalog sync; A23, A31, A33, A35,
  A40 + A43, A29 + C09; validate_ua_templates AttributeError; the Python
  fixture failure (C14); B02; B04.
- Wave 4 — website truth (function only, UI frozen): B05, B06, then B01.
  **B01 is STOP-AND-ASK: only the owner decides whether candidate-labelled
  apps may generate. Do not implement either option without his choice.**
  Then re-run the Phase 3 website evidence pack.
- Wave 5 — make every PARTIAL feature whole (truth-table walk), plus
  A11–A15, A19, A21, A37, A38, the Lows A39 + A41 + A30 + A44, and A42.
  The 20 UNVERIFIED rows stay UNVERIFIED and close with a device-test list.
- Wave 6 — bump to versionCode 34 / 0.4.0-alpha30, CHANGELOG,
  PROJECT_CONTEXT corrections, all final gates green and shown, final
  report. Done when Critical and High are zero, or the owner accepts
  exceptions in writing.

## Known open failures (exact causes)

- A47 (JVM): startup-URL fixture — the only JVM failure at Wave 2 HEAD
  (119/120 passing at A36's commit).
- C14 (Python): expects a generated proposal fixture absent from a clean
  checkout (76 passed / 1 failed). Wave 3 fixes it by generating the
  fixture in test setup — never by deleting or skipping the test.
- Bundled-catalog validators at HEAD: devices 7 errors / 46 warnings and
  specs 73 / 44 against the bundled catalog, while the web catalog is 0/0.
- UA templates validator: a null-record exception on the Android baseline;
  validate_ua_templates also crashes with AttributeError on that input.

## Owner decisions on record

- Fix the Kotlin test compile error before A01 (done, test-only commit).
- A10: quarantine ambiguous migrated browsing data intact; the profile
  starts with empty metadata. Nothing destroyed.
- A26: officially attributed captures are acceptable evidence for AI
  refresh proposals, after checking every field against the captured text.
- A26 live Cerebras call: tested locally only; the live provider call
  stays UNVERIFIED. No credential is placed in a new environment for it.
- A32: manual admin Save verifies changed values server-side against the
  URL already in the form; an unverifiable change is rejected and the
  saved record kept.
- A18: a missing Safari version means explicit user entry, clearly
  labelled manual — never a guessed default.
- SDK licence approved for the audit environment.

## Verification method and its limits

- Per fix: Gradle `assembleDebug` + `testDebugUnitTest`, the Python
  suite, desktop smoke, and the validators — numbers recorded in the
  register in the same commit as the fix.
- Codex's own cloud executor failed to start; verification also used the
  temporary Actions branch `verify/alpha30-checks` (read-only workflow).
  Caution: that branch's first run (46059445247) recorded ZERO JVM tests
  (Maven HTTP 429, no APK) — results from that specific run prove nothing.
  The Wave 2 numbers in the register come from Codex's per-commit runs.
- Muse's sandbox cannot run Gradle. Muse verifies APK builds with its
  own manual pipeline (same debug signing certificate as every alpha) and
  runs suites through the Actions branch.
- Installed-app behaviour (e.g. A18 manual entry on a real device) is
  UNVERIFIED until the owner's phone test.

## Standing rules

- One finding = one fix = one commit; register row updated in the same
  commit; a fix that breaks anything is reverted and reported.
- Evidence or it didn't happen; UNVERIFIED stays UNVERIFIED.
- Never invent data — no default may masquerade as a fact.
- Website UI is frozen: function fixes only in `web/`.
- Package stays `com.nexora.browser`; versionCode only increases; signing
  setup untouched; stored data formats change only with a preserving
  migration.
