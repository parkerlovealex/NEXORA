package com.nexora.browser.features

enum class FeatureStatus(val label: String) {
    AVAILABLE("AVAILABLE"),
    PARTIAL("PARTIAL"),
    ROADMAP("ROADMAP")
}

enum class FeatureAction {
    CREATE_PROFILE,
    BULK_CREATE,
    PROXY_MANAGER,
    OPEN_BROWSER,
    PROFILE_EDITOR,
    COMPATIBILITY_LAB,
    PRIVACY_CENTER,
    AUTOMATION_CENTER,
    SETTINGS,
    NONE
}

data class FeatureModule(
    val id: String,
    val title: String,
    val category: String,
    val status: FeatureStatus,
    val summary: String,
    val workingNow: List<String>,
    val limitations: List<String>,
    val nextStep: String,
    val action: FeatureAction = FeatureAction.NONE,
    val actionLabel: String = ""
)

/**
 * Single source of truth for the in-app feature map.
 *
 * Status labels describe the current Android WebView build, not an aspirational
 * Chromium build. A roadmap item must never be presented as an active control.
 */
object FeatureCatalog {
    val modules: List<FeatureModule> = listOf(
        FeatureModule(
            id = "profile-studio",
            title = "Profile Studio",
            category = "IDENTITY MANAGEMENT",
            status = FeatureStatus.PARTIAL,
            summary = "Create and maintain separate browser profile records with device presets and per-profile settings.",
            workingNow = listOf(
                "Create, edit, duplicate and delete profile records",
                "Bulk profile creation with per-profile review",
                "Import User-Agent and proxy text lists",
                "Assign startup URLs, group labels and profile notes",
                "Export selected profile records"
            ),
            limitations = listOf(
                "Website-data isolation depends on Android WebView MULTI_PROFILE support",
                "Device presets change selected browser-visible values; they do not turn Android into another operating system",
                "A profile record is not proof that every configured signal is natively isolated"
            ),
            nextStep = "Complete profile lifecycle tests, safe import/restore and per-profile storage verification.",
            action = FeatureAction.CREATE_PROFILE,
            actionLabel = "Create a profile"
        ),
        FeatureModule(
            id = "bulk-profile-creation",
            title = "Bulk Profile Creation",
            category = "PROFILE OPERATIONS",
            status = FeatureStatus.PARTIAL,
            summary = "Create a batch of profile records from a shared template with per-profile review before saving.",
            workingNow = listOf(
                "Batch size and naming-prefix configuration",
                "Shared Android device preset and profile defaults",
                "Per-profile review before creation",
                "Bulk User-Agent and proxy text import"
            ),
            limitations = listOf(
                "This is a profile creation wizard, not a general browser automation engine",
                "Bulk creation does not launch profiles or perform website actions automatically",
                "Unsupported engine and operating-system combinations remain unavailable"
            ),
            nextStep = "Add batch validation, safe rollback and large-batch performance tests.",
            action = FeatureAction.BULK_CREATE,
            actionLabel = "Open bulk profile creation"
        ),
        FeatureModule(
            id = "fingerprint-engine",
            title = "Fingerprint Engine",
            category = "BROWSER IDENTITY",
            status = FeatureStatus.PARTIAL,
            summary = "A profile-scoped JavaScript compatibility layer adjusts selected browser-visible identity signals.",
            workingNow = listOf(
                "Canvas readback noise for supported readback paths",
                "WebGL vendor and renderer parameter overrides",
                "AudioBuffer readback noise",
                "Selected navigator, screen, language and hardware-concurrency values",
                "Profile-specific User-Agent selection"
            ),
            limitations = listOf(
                "These are JavaScript-level changes, not native Blink or Chromium patches",
                "Worker contexts are not automatically covered by document-start injection",
                "Native OS, GPU driver, network stack and all browser APIs are not replaced",
                "Compatibility Lab availability does not prove protection effectiveness"
            ),
            nextStep = "Build a repeatable fingerprint regression corpus and document each signal's actual coverage.",
            action = FeatureAction.PROFILE_EDITOR,
            actionLabel = "Configure a profile"
        ),
        FeatureModule(
            id = "proxy-center",
            title = "Proxy Center",
            category = "NETWORK IDENTITY",
            status = FeatureStatus.PARTIAL,
            summary = "Store, import, test and assign proxy endpoints to browser profiles.",
            workingNow = listOf(
                "Local proxy library with import, rename and delete",
                "Encrypted storage for saved proxy credentials",
                "HTTP, HTTPS and SOCKS5 relay with upstream authentication",
                "Proxy tests use the same relay as browsing",
                "Profile-level proxy assignment and consent-gated proxy locale suggestions"
            ),
            limitations = listOf(
                "HTTP, HTTPS and SOCKS5 are routed through a loopback relay with in-memory upstream authentication",
                "SOCKS4 remains import-only; WebView's app-wide proxy setting is protected by a single-profile session lease",
                "A successful connection test is not proof that every browser request uses the proxy",
                "DNS and WebRTC routing require separate leak verification"
            ),
            nextStep = "Verify proxy application, clearing, DNS behavior and failure recovery on real devices.",
            action = FeatureAction.PROXY_MANAGER,
            actionLabel = "Open Proxy Center"
        ),
        FeatureModule(
            id = "browser-workspace",
            title = "Browser Workspace",
            category = "DAILY BROWSING",
            status = FeatureStatus.PARTIAL,
            summary = "A multi-tab Android WebView browser with profile-scoped browsing data and everyday browser tools.",
            workingNow = listOf(
                "Multiple tabs and tab groups",
                "Bookmarks, history and recently closed tabs",
                "Profile-aware HTTP(S) downloads through the active proxy relay",
                "Bookmarks, history, session restore, find-in-page and text zoom",
                "Desktop-site toggle, per-site User-Agent and dark theme",
                "Redirect rules, local privacy diagnostics and a 20-tab live cap"
            ),
            limitations = listOf(
                "The rendering engine is the installed Android System WebView",
                "Extension runtime and desktop-class browser APIs are not included",
                "Downloads currently support HTTP(S) GET only; resume and process-death continuation are not implemented",
                "FileProvider open flow, live proxy providers and low-memory lifecycle still require device testing"
            ),
            nextStep = "Stress-test process recreation, proxy downloads, FileProvider handoff and low-memory recovery on devices.",
            action = FeatureAction.OPEN_BROWSER,
            actionLabel = "Open a browser profile"
        ),
        FeatureModule(
            id = "privacy-center",
            title = "Privacy Center",
            category = "DIAGNOSTICS & SAFETY",
            status = FeatureStatus.PARTIAL,
            summary = "A profile-scoped security dashboard for WebView defaults, permission handling and browsing-data cleanup, with honest network-test status.",
            workingNow = listOf(
                "Local compatibility and fingerprint capability report",
                "Safe Browsing enabled on each browser WebView",
                "Mixed-content blocking and disabled local file/content access",
                "Third-party cookies disabled per WebView",
                "Web permission and geolocation requests denied by default",
                "JavaScript pop-up windows disabled and media playback requires a gesture",
                "Opt-in, profile-scoped redacted blocked-request diagnostics",
                "A limited host-rule content blocker"
            ),
            limitations = listOf(
                "Local API availability is not proof that a protection defeats real-world fingerprinting",
                "The content blocker does not implement the complete EasyList/Adblock Plus rule language",
                "The dashboard does not run live IP, DNS or WebRTC leak tests",
                "This is not a full Chromium network-stack privacy implementation"
            ),
            nextStep = "Add device-backed permission, cookie, DNS, WebRTC and proxy leak regression tests before upgrading any privacy status.",
            action = FeatureAction.PRIVACY_CENTER,
            actionLabel = "Open Privacy Center"
        ),
        FeatureModule(
            id = "automation-center",
            title = "Automation Center",
            category = "WORKFLOWS",
            status = FeatureStatus.PARTIAL,
            summary = "A local workflow workspace for repeatable profile checks and explicitly confirmed profile operations.",
            workingNow = listOf(
                "Read-only profile configuration audit with a persistent local audit log",
                "Preview and cancel one-to-one assignment of saved HTTP, HTTPS and SOCKS5 proxies to unassigned profiles",
                "Profile changes are committed only after explicit confirmation"
            ),
            limitations = listOf(
                "No general browser macro engine or website interaction automation is implemented",
                "No scheduled or background workflow runner is available",
                "Proxy assignment does not test connectivity or prove traffic routing"
            ),
            nextStep = "Add device-verified workflow recovery and extend only to operations with clear permissions and rollback.",
            action = FeatureAction.AUTOMATION_CENTER,
            actionLabel = "Open Automation Center"
        ),
        FeatureModule(
            id = "extension-center",
            title = "Extension Center",
            category = "EXTENSIBILITY",
            status = FeatureStatus.ROADMAP,
            summary = "A planned extension-management experience, dependent on a compatible browser engine.",
            workingNow = emptyList(),
            limitations = listOf(
                "There is no extension installation or execution runtime in the current WebView build",
                "A menu or extension catalogue alone would not provide real extension support"
            ),
            nextStep = "Evaluate a supported engine architecture before designing extension installation and permissions.",
            action = FeatureAction.NONE
        ),
        FeatureModule(
            id = "chromium-engine",
            title = "Chromium Engine",
            category = "ENGINE ARCHITECTURE",
            status = FeatureStatus.ROADMAP,
            summary = "The current app uses the Android System WebView supplied by the device. A bundled, modified Chromium engine is a separate engineering project.",
            workingNow = listOf(
                "AndroidX WebKit integration with provider feature detection",
                "Profile storage isolation when the installed provider supports MULTI_PROFILE"
            ),
            limitations = listOf(
                "NEXORA does not currently bundle its own Chromium/Blink engine",
                "Native fingerprint patches, full engine-level proxy routing and extension APIs are not present",
                "The Android SDK and JDK alone cannot compile the complete Chromium source tree"
            ),
            nextStep = "Complete a separate Chromium feasibility and reproducible build-environment milestone before promising native engine features.",
            action = FeatureAction.NONE
        ),
        FeatureModule(
            id = "settings-system",
            title = "Settings & System",
            category = "APP CONFIGURATION",
            status = FeatureStatus.PARTIAL,
            summary = "Configure the app appearance, search provider and optional public device-catalog synchronization.",
            workingNow = listOf(
                "System, light and dark appearance modes",
                "Selectable search engine",
                "Optional HTTPS device-catalog update check",
                "Rollback to the previous downloaded catalog",
                "Password-encrypted portable backup and additive restore for profile configuration and saved proxy entries"
            ),
            limitations = listOf(
                "There is no NEXORA account or cloud profile synchronization",
                "Catalog synchronization does not upload browser profiles or browsing history",
                "Backups exclude cookies, website logins, history, bookmarks, tabs, downloads, downloaded files and WebView cache"
            ),
            nextStep = "Add optional browser-metadata backup only after per-profile data export and restore can be validated safely.",
            action = FeatureAction.SETTINGS,
            actionLabel = "Open Settings"
        )
    )

    fun find(id: String?): FeatureModule? =
        modules.firstOrNull { it.id == id }
}
