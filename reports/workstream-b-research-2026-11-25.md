# Workstream B research continuation — 2026-11-25

## Status

Research coverage was extended across all **18 app keys × Android/iOS (36 platform cells)**. Three new evidence-backed records were staged as `pending` in `data/ua_templates.findings.json`:

1. `facebook-android-fb4a-observed-2026-v1`
2. `facebook-ios-fban-fbios-observed-2026-v1`
3. `instagram-ios-observed-2026-v1`

The 11 original script/app-implementation candidates remain intact. `data/ua_templates.proposed.json` remains unchanged. `web/ua_templates.json` is not included in this handoff pack, so no runtime file was edited or revalidated. The project's last reported runtime state remains **11 candidate, 0 verified**; this research pass did not publish anything.

## New evidence

### Facebook Android — current no-`wv` variant

A public access-log row dated 2026-11-24 contains:

```text
Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/158.0.0.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/588.0.0.42.55;IABMV/1;]
```

A separate User-Agents.net entry carries the same app-specific block and app version on an Android 16 device. A 2026-11-25 technical article independently says current Facebook Android uses `FB_IAB/FB4A` and no longer sends `wv`.

The attached `devices-2026.11.25.7.json` contains the exact Facebook Android version/build pair `588.0.0.42.55 / 707615019`. The staged template uses only `appVersion -> meta.apps.facebook.version`; `Android 10; K` and `Chrome/158.0.0.0` remain literal tokens from the observed reduced-UA variant. The catalog's current `meta.versions.chrome` is `159.0.8154.33`, so it must not be substituted into this sample without a matching observation.

This is a separate variant from the older Motorola script that includes `; wv)` and the Samsung candidate with a device/build token. Those variants were not collapsed.

**Status: pending, not verified.** The pair match is against the attached `.7` file, the specification still says `.6` is the published baseline, and the official validator was not available in the handoff pack.

### Facebook iOS — `FBAN/FBIOS` variant

AvidTrak access logs dated 2026-11-24 and a separate User-Agents.net entry show the same current app version/build pair `588.1.0.66.71 / 1119078847` and the `FBAN/FBIOS` token order. DeviceAtlas also publishes a real-traffic Facebook iOS in-app sample and explicitly distinguishes it from Safari.

The supplied device catalog does **not** contain the observed iOS app version/build pair. Device/OS fields can map to the iOS emulation catalog, but `FBRV` varies and has no allowlisted path; `FBLC` is also not represented by an allowlisted locale field. The existing AppUa.kt candidate is a different format and remains separate.

**Status: pending; `template` remains null.** Do not borrow Messenger's `588.1.0.66.98 / 347617572` pair.

### Instagram iOS — observed app-token variant

AvidTrak's 2026-11-19 log contains an Instagram iOS sample with app version `456.0.0.36.62`, device `iPhone17,3`, iOS `27.0.1`, build `24A446`, DPR `3`, and resolution `1179x2556`. Those device/OS/display values match the supplied iPhone 16 emulation record. User-Agents.net independently shows the same `Instagram <version> (...; IABMV/1; <runtime-id>) Safari/604.1` ordering.

The app version is not in the supplied Instagram version history. The trailing numeric value is not proven to be an app build, and locale/language/runtime-ID fields do not map to the current allowlist. This is distinct from the existing AppUa.kt candidate, so the two were not merged.

**Status: pending; `template` remains null.**

## Coverage across all 18 apps

| App key | Android evidence | iOS evidence | Current disposition |
|---|---|---|---|
| `discord` | `Discord/150.0` app-UA sample; no dedicated IAB proof | No distinct sample found | No template |
| `facebook` | Current `FB_IAB/FB4A`, no `wv`; exact pair in attached `.7` catalog | Current `FBAN/FBIOS`; app pair absent | Pending |
| `fb_lite` | `FBAN/EMA` samples, but versions are old | No sample found | Android pending research |
| `instagram` | Published examples plus existing candidate scripts; no current pair | Observed `Instagram 456.0.0.36.62`; unresolved app/runtime fields | Pending |
| `kik` | No IAB sample found | `Kik/... CFNetwork` native-request sample only | No template |
| `line` | `Line/.../IAB` examples, old versions | Older LINE app-token examples; docs confirm LIFF behavior, not exact UA | Pending research |
| `linkedin` | `[LinkedInApp]` samples; version mismatch | `[LinkedInApp]` samples; version mismatch | Pending research |
| `messenger` | Existing candidate and legacy Orca reference; no current raw template | Token lead only; no raw iOS sample | Candidate only |
| `pinterest` | No sample found | `Pinterest/11.0` published sample; old | Pending research |
| `reddit` | `2026.38.0 / 2638050` sample; pair absent from history | No sample found | Pending research |
| `snapchat` | No sample found | `Snapchat/14.25.0.48` sample; old | Pending research |
| `telegram` | Older Appy example `Telegram-Android/10.6.2` | `Telegram/... CFNetwork` native request only; detection reference says Telegram is client-side detectable, not UA-detectable | No iOS template |
| `threads` | `Barcelona 450.0.0.51.78`; version appears in history, but build is not in UA and source is a published dataset | Older `Barcelona` sample; pair mismatch | Pending research |
| `tiktok` | `musical_ly_...` sample; old build | `musical_ly_46.4.0` sample; old version | Pending research |
| `viber` | `Viber/12.8.0.19` WebView sample; old | `Viber/... CFNetwork` native request only | Android pending research |
| `wechat` | `MicroMessenger/8.0.76.3141`; pair exists in `.7` history, but XWEB/MMWEB/REV fields remain unresolved | `MicroMessenger/8.0.75` sample; no app build | Pending research |
| `weibo` | Latest surfaced sample is Downloader/Cronet native traffic, not a demonstrated IAB | No sample found | No template |
| `x` | `TwitterAndroid/12.28.0-alpha.01` sample; old pair | Inferred token lead only; no raw iOS observation | Android pending research |

The matrix is research coverage, not template status. Published dataset entries remain `published-dataset-entry`; native networking UAs are not treated as embedded-browser evidence; untested token rules are not treated as observed samples.

## Blocking issues

1. **Device baseline conflict:** `UA_TEMPLATES_SPEC.md` says the published device catalog is `2026.11.25.6`; `PROJECT_CONTEXT.md` and the attached device file identify `2026.11.25.7`. The three new records were checked against the attached `.7` file only.
2. **Official validator unavailable:** this uploaded handoff pack does not contain `tools/collect_ua_templates.py`, `tools/validate_ua_templates.py`, the tests, or `.github/workflows/ua-templates-collect.yml`, although `PROJECT_CONTEXT.md` says they are installed in the remote repository. No official collection or validation run was claimed.
3. **useragent.in remains uncollected:** `index.json` still returns HTTP 429 in the web retrieval attempt. No category filename was guessed and no raw rows were fabricated.
4. **iOS app build coverage:** current iOS Facebook/Instagram app-version/build pairs are not represented by the supplied `meta.apps` histories. Some additional tokens (for example `FBRV`, locale/language and Instagram's trailing runtime ID) have no allowlisted path.
5. **WebView version mismatch:** the Facebook Android observation uses `Chrome/158.0.0.0`; the supplied device catalog's current Chrome version is `159.0.8154.33`. The observed value remains literal; no substitution was made.

## Repository and publication state

- No remote commit, branch, pull request, or GitHub Action was changed or triggered.
- The public repository page was discoverable, but repository file/PR operations were not available in this session.
- `AGENTS.md` is unchanged.
- `data/ua_templates.proposed.json` is unchanged. `web/ua_templates.json` is not included in this handoff pack; no runtime file was edited or revalidated.
- No template was marked `verified`; no runtime catalog was published.

## Next actions

1. Reconcile whether `.6` or `.7` is the authoritative published device baseline.
2. In the full repository, run the official collector and validator against the updated findings file.
3. Resolve the remaining iOS app-version/build and unallowlisted-token gaps before promoting either iOS record.
4. Retry `useragent.in/assets/data/index.json` from the GitHub Actions runner and discover the exact category filename from the returned index; never guess it.
5. Keep runtime publication separately gated by owner approval.
