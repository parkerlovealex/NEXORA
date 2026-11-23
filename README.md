# NEXORA

Private browsing. Total control.

Android anti-detect browser (WebView stage). Profile manager with per-profile
device identity, user-agent, proxy, and isolated storage.

## Device data system

The device database lives in one file: `app/src/main/assets/devices.json`
(schema v2). It is the single source for the bundled copy inside the APK and
for the in-app auto-update, which fetches the same file from this repo.

- `tools/validate_devices.py` checks every row for completeness and
  coherence (model, OS, build ID, GPU, screen always from one device row).
- `.github/workflows/build.yml` builds the APK on every push to `main`.
- `.github/workflows/refresh.yml` runs weekly: pulls fresh device / build /
  app-version data from source sites, uses the Cerebras AI API to normalize
  new rows, validates them, and opens a pull request with the diff.
  Nothing merges without review.

## Build

CI builds on GitHub Actions (Gradle 8.7, JDK 17). The debug APK is uploaded
as the `nexora-debug-apk` artifact on each run.
