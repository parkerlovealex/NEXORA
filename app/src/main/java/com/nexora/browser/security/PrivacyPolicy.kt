package com.nexora.browser.security

/**
 * Security defaults that BrowserActivity applies to every profile WebView.
 * These are configuration guarantees, not proof against every web attack or leak.
 */
object PrivacyPolicy {
    const val SAFE_BROWSING_ENABLED = true
    const val BLOCK_MIXED_CONTENT = true
    const val FILE_ACCESS_ALLOWED = false
    const val CONTENT_ACCESS_ALLOWED = false
    const val THIRD_PARTY_COOKIES_ALLOWED = false
    const val JAVASCRIPT_POPUPS_ALLOWED = false
    const val MEDIA_PLAYBACK_REQUIRES_GESTURE = true

    @Suppress("UNUSED_PARAMETER")
    fun grantWebContentPermission(resources: Collection<String>): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun grantGeolocation(origin: String?): Boolean = false

    data class Control(
        val title: String,
        val status: String,
        val detail: String
    )

    fun controls(
        multiProfileSupported: Boolean,
        documentStartSupported: Boolean
    ): List<Control> = listOf(
        Control(
            "Safe Browsing",
            "ON",
            "WebView Safe Browsing is enabled for browser tabs. Provider behavior still depends on the installed WebView version."
        ),
        Control(
            "Mixed content",
            "BLOCKED",
            "HTTPS pages are configured not to load insecure HTTP mixed content."
        ),
        Control(
            "Local file access",
            "DISABLED",
            "Web pages cannot read local files through WebView file access."
        ),
        Control(
            "Local content access",
            "DISABLED",
            "Web pages cannot use WebView content-URI access."
        ),
        Control(
            "Third-party cookies",
            "BLOCKED",
            "Third-party cookies are disabled per WebView. First-party cookies remain available."
        ),
        Control(
            "Camera, microphone and other web permissions",
            "DENY BY DEFAULT",
            "Web content permission requests are denied. No browser permission prompt is implemented in this build."
        ),
        Control(
            "Website geolocation",
            "DENY BY DEFAULT",
            "Web geolocation prompts are denied by the browser."
        ),
        Control(
            "JavaScript pop-up windows",
            "DISABLED",
            "Automatic JavaScript windows and multi-window support are disabled."
        ),
        Control(
            "Media autoplay",
            "GESTURE REQUIRED",
            "Media playback requires a user gesture."
        ),
        Control(
            "Named profile storage",
            if (multiProfileSupported) "API AVAILABLE" else "UNAVAILABLE",
            if (multiProfileSupported) {
                "The installed WebView reports multi-profile support. Profile isolation still requires device verification."
            } else {
                "The installed WebView does not report multi-profile support; NEXORA blocks profile browsing rather than silently sharing storage."
            }
        ),
        Control(
            "Document-start injection",
            if (documentStartSupported) "API AVAILABLE" else "BEST-EFFORT ONLY",
            if (documentStartSupported) {
                "The provider exposes document-start script registration. This does not make JavaScript fingerprint changes native engine protections."
            } else {
                "The provider lacks document-start registration; any fingerprint script fallback is best-effort and may run after page scripts."
            }
        ),
        Control(
            "DNS, IP and WebRTC leak testing",
            "NOT TESTED",
            "No live network leak test is run by this dashboard. A configured proxy or JavaScript WebRTC restriction is not proof of leak prevention."
        ),
        Control(
            "Hardware MAC spoofing",
            "NOT AVAILABLE",
            "NEXORA does not generate or claim a per-profile hardware MAC address."
        )
    )
}
