package com.nexora.browser.util

import com.nexora.browser.data.BrowserProfile

/** Detects legacy named-WebView-profile key collisions before any page is loaded. */
object ProfileIsolationGuard {
    fun collidingProfiles(profile: BrowserProfile, allProfiles: Collection<BrowserProfile>): List<BrowserProfile> =
        allProfiles.filter { it.webViewProfileKey == profile.webViewProfileKey }
            .distinctBy { it.id }
}
