# Desktop User-Agent generator extension — 2026-11-25

## Scope implemented

This change adds desktop User-Agent generation to the supplied `web-current/` source snapshot while preserving Android/iOS generation. The handoff does not include the configured Vercel `web/` output directory, so the change is not yet integrated into the actual deployment source.

- Windows 11: latest five release trains — 26H2, 26H1, 25H2, 24H2 and 23H2.
- Windows 10: last three feature releases — 22H2, 21H2 and 21H1.
- macOS: latest ten major releases — 27 Golden Gate, 26 Tahoe, 15 Sequoia, 14 Sonoma, 13 Ventura, 12 Monterey, 11 Big Sur, 10.15 Catalina, 10.14 Mojave and 10.13 High Sierra.
- Browser families: Chrome, Firefox, Microsoft Edge and Opera on Windows and macOS; Safari on macOS only.
- Each supported browser/platform combination has five stable release entries, newest first.

## Desktop OS catalog

### Windows

| Family | Release | Base OS build | Latest build field | Notes |
|---|---:|---:|---:|---|
| Windows 11 | 26H2 | 26300 | 26300.10075 | Current release |
| Windows 11 | 26H1 | 28000 | 28000.3620 | Special release; not a normal in-place upgrade from 24H2/25H2 |
| Windows 11 | 25H2 | 26200 | 26200.10075 | Supported |
| Windows 11 | 24H2 | 26100 | 26100.9974 | Home/Pro end of updates 2026-10-13; edition matters |
| Windows 11 | 23H2 | 22631 | 22631.7711 | Home/Pro end of updates 2026-11-10 |
| Windows 10 | 22H2 | 19045 | 19045.7850 | Final Windows 10 Home/Pro feature release; Home/Pro support ended 2025-10-14 |
| Windows 10 | 21H2 | 19044 | 19044.7850 | LTSC 2021 lifecycle; edition-specific support |
| Windows 10 | 21H1 | 19043 | 19043.2364 | Historical, end of updates |

Windows 11 26H2, 26H1 and 25H2 latest-build values include the November 19 optional preview (`2026-11 D`); the catalog also retains their November 10 security builds separately. Windows 10 22H2/21H2 latest-build values are the November 13 out-of-band security builds. Microsoft identifies Windows 10 22H2 as the final Windows 10 Home/Pro release. The November 13, 2026 out-of-band update brings both 22H2 and Enterprise LTSC 21H2 to build 19045.7850 / 19044.7850. Version 21H2 is listed as LTSC 2021, while 21H1 is a previous general release. The OS picker labels those distinctions rather than implying all three are currently supported.

### macOS

| Major version | Release name | Latest version recorded |
|---:|---|---:|
| 27 | Golden Gate | 27.2.1 |
| 26 | Tahoe | 26.7.3 |
| 15 | Sequoia | 15.8.3 |
| 14 | Sonoma | 14.8.9 |
| 13 | Ventura | 13.7.8 |
| 12 | Monterey | 12.7.6 |
| 11 | Big Sur | 11.7.11 |
| 10.15 | Catalina | 10.15.8 |
| 10.14 | Mojave | 10.14.6 |
| 10.13 | High Sierra | 10.13.6 |

The macOS latest-version values are from Apple's support and security-release pages as of 2026-11-25.

## Browser version catalog

The version lists below are the five latest stable release trains per browser. Chromium-family UA strings are reduced to major.0.0.0; the browser's full release build is retained as metadata where sourced.

| Browser | Windows | macOS | UA token form |
|---|---|---|---|
| Chrome | 158, 157, 156, 155, 154 | Same | `Chrome/major.0.0.0` |
| Firefox | 161.0, 160.0.1, 159.0.1, 158.0.1, 157.0.1 | Same | `rv:version` and `Firefox/version` |
| Edge | 158, 157, 156, 155, 154 | Same | `Edg/major.0.0.0` |
| Opera | 140, 139, 138, 137, 136 | Same | `OPR/major.0.0.0` plus Chromium engine token |
| Safari | Not supported | 27.2, 27.1, 27.0, 26.6, 26.5 | `Version/version` and `Safari/605.1.15` |

Chrome 156 and Edge 156 are represented by their latest managed Extended Stable servicing builds (Chrome 156.0.8078.114; Edge 156.0.4314.84); the other listed Edge/Chrome entries are Stable-channel release trains. The list preserves the UA token's reduced major version, not the full patch build.

Chrome 159 was intentionally not selected as the current stable default: on 2026-11-24 it was an Early Stable update offered to a small percentage of users. Chrome 158 was the current desktop Stable channel version on 2026-11-23. The five-entry catalog therefore begins with 158.

Opera's desktop OPR version and Chromium engine version are separate: Opera 140 uses Chromium 157; Opera 139 uses Chromium 155; Opera 138 uses Chromium 154; Opera 137 uses Chromium 153; Opera 136 uses Chromium 152. The generated UA includes both tokens.

## UA reduction and metadata behavior

The selected OS release is retained in the output record as `osVersion`, `osReleaseName`, `osReleaseId`, `osFamily` and `osBuild` where available. It is not inserted into a UA token that browsers intentionally freeze.

Examples:

```text
Windows Chrome 158:
Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36

Windows Firefox 161:
Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:161.0) Gecko/20100101 Firefox/161.0

Windows Edge 158:
Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36 Edg/158.0.0.0

macOS Firefox 161:
Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:161.0) Gecko/20100101 Firefox/161.0

macOS Opera 140:
Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36 OPR/140.0.0.0

macOS Safari 27.2:
Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15
```

Modern reduced desktop UA strings use `Windows NT 10.0; Win64; x64` for both Windows 10 and Windows 11. Chrome-family and Safari macOS UAs use `Macintosh; Intel Mac OS X 10_15_7`; Firefox caps the reported macOS version at `10.15`. Therefore, selecting Windows 10 22H2 versus Windows 11 26H2 changes the output metadata, not the standard browser UA platform token. The same applies to the selected macOS release.

These are compatibility UA templates. They do not prove that every latest browser version can actually be installed on every historical OS release. No compatibility matrix was fabricated.

## UI and generator changes

- Added platform modes: Android, iOS, Both, Windows, macOS, Desktop and All.
- Added Windows and macOS quantity cards; the combined batch limit remains 500.
- Added Windows/macOS OS release selectors.
- Added Windows browser choices: Chrome, Firefox, Edge and Opera.
- Added macOS browser choices: Chrome, Firefox, Edge, Opera and Safari.
- Added desktop version datalists and five-version random mode.
- Added desktop result counters and result filters.
- Desktop batches use virtual desktop records and do not consume mobile device rows.
- App-specific in-app template generation remains Android/iOS-only. `UA_TEMPLATES_SPEC.md`, Workstream B findings and runtime/proposal files were not changed by this extension.

## Files changed

- `web-current/index.html`
- `web-current/app.js`
- `web-current/styles.css`
- `web-current/desktop-catalog.json` (staged asset; port to `web/desktop-catalog.json`)
- `data/desktop-platforms-2026-11-25.json`
- `data/browser-versions-2026-11-25.json` (desktop catalog pointer only; mobile version lists preserved)
- `docs/WEBSITE_GENERATOR_REDESIGN.md`
- `docs/WEBSITE_UX_REDESIGN_V2.md`
- `PROJECT_CONTEXT.md`
- `00_START_HERE.md`
- `tests/desktop-ua-smoke.js`

No changes were made to `AGENTS.md`, `data/devices-2026.11.25.7.json`, Workstream B findings, the proposed app-template catalog or the mobile app-template/runtime catalog.

## Validation

- `node --check web-current/app.js` — passed.
- Desktop catalog structural checks — passed: 5 Windows 11 + 3 Windows 10 releases; 10 macOS releases; five version entries per supported desktop browser; Safari omitted from Windows.
- `node tests/desktop-ua-smoke.js` — passed: Windows Chrome/Firefox/Edge and macOS Chrome/Firefox/Opera/Safari UA forms; Windows 10/11 reduced token equivalence; Desktop/All/Both batch quantity modes; required UI IDs and uniqueness.
- The source package's `vercel.json` sets `outputDirectory: web`, but no `web/` directory is included. The `web-current/` snapshot is not safe to copy over the live deployment directory wholesale because the project context records newer `web/icons/` and `LOCAL_ICONS` repairs. Merge the changed UI/JS/CSS into the full repository's current `web/` files and copy the desktop catalog asset there.
- Visual browser interaction testing, API-connected deployment, production deployment and real-world OS/browser compatibility testing were not performed in this environment.

## Source references

- Microsoft Windows supported client versions: https://learn.microsoft.com/en-us/windows/release-health/supported-versions-windows-client
- Microsoft Windows 10 release information: https://learn.microsoft.com/en-us/windows/release-health/release-information
- Microsoft Windows 10 November 2026 update (19045.7850 / 19044.7850): https://support.microsoft.com/en-us/servicing/os/windows-10/2026/11/kb5136493-windows-10-21h2-22h2-update
- Microsoft Windows 11 November 2026 preview update (26300.10075 / 26200.10075): https://support.microsoft.com/en-us/servicing/os/windows-11/2026/11/kb5132057-windows-11-25h2-26h2-update
- Microsoft Windows 11 26H1 November security update (28000.3501): https://support.microsoft.com/en-us/servicing/os/windows-11/2026/11/kb5132059-windows-11-26h1-security-update
- Microsoft Windows 11 26H1 November preview update (28000.3620): https://support.microsoft.com/en-us/servicing/os/windows-11/2026/11/kb5132265-windows-11-26h1-update
- Apple macOS latest versions: https://support.apple.com/en-us/109033
- Apple security releases: https://support.apple.com/en-us/100100
- Google Chrome desktop Stable releases: https://chromereleases.googleblog.com/
- Chrome 158 desktop Stable (23 Nov 2026): https://chromereleases.googleblog.com/2026/11/stable-channel-update-for-desktop_01734282054.html
- Chrome 157 desktop Stable (11 Nov 2026): https://chromereleases.googleblog.com/2026/11/stable-channel-update-for-desktop_01744469280.html
- Chrome 156 desktop Extended Stable (23 Nov 2026): https://chromereleases.googleblog.com/2026/11/extended-stable-update-for-desktop_01590513949.html
- Chrome 159 Early Stable (24 Nov 2026; small rollout, excluded from default stable list): https://chromereleases.googleblog.com/2026/11/
- Mozilla Firefox release list: https://www.firefox.com/en-US/releases/
- Microsoft Edge release schedule: https://learn.microsoft.com/en-us/deployedge/microsoft-edge-release-schedule
- Microsoft Edge Stable release notes: https://learn.microsoft.com/en-us/deployedge/microsoft-edge-relnote-stable-channel
- Opera Desktop stable releases: https://blogs.opera.com/desktop/
- Opera 140 Stable: https://blogs.opera.com/desktop/2026/11/opera-140-stable/
- Opera 139 Stable: https://blogs.opera.com/desktop/2026/10/opera-139-stable/
- Opera 138 Stable: https://blogs.opera.com/desktop/2026/10/opera-138-stable/
- Opera 137 Stable: https://blogs.opera.com/desktop/2026/10/opera-137-stable/
- Opera 136 Stable: https://blogs.opera.com/desktop/2026/09/opera-136-stable/
- Apple Safari release notes: https://developer.apple.com/documentation/safari-release-notes/
- MDN User-Agent reduction guide: https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/User-agent_reduction
- MDN Firefox User-Agent reference: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/User-Agent/Firefox
