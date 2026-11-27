package com.nexora.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyTest {
    @Test
    fun secureWebViewDefaultsAreRestrictive() {
        assertTrue(PrivacyPolicy.SAFE_BROWSING_ENABLED)
        assertTrue(PrivacyPolicy.BLOCK_MIXED_CONTENT)
        assertFalse(PrivacyPolicy.FILE_ACCESS_ALLOWED)
        assertFalse(PrivacyPolicy.CONTENT_ACCESS_ALLOWED)
        assertFalse(PrivacyPolicy.THIRD_PARTY_COOKIES_ALLOWED)
        assertFalse(PrivacyPolicy.JAVASCRIPT_POPUPS_ALLOWED)
        assertTrue(PrivacyPolicy.MEDIA_PLAYBACK_REQUIRES_GESTURE)
    }

    @Test
    fun webPermissionsAndGeolocationAreDeniedByDefault() {
        assertFalse(PrivacyPolicy.grantWebContentPermission(listOf("android.webkit.resource.VIDEO_CAPTURE")))
        assertFalse(PrivacyPolicy.grantWebContentPermission(emptyList()))
        assertFalse(PrivacyPolicy.grantGeolocation("https://example.com"))
        assertFalse(PrivacyPolicy.grantGeolocation(null))
    }

    @Test
    fun diagnosticsNeverReportNetworkLeakTestingAsPassed() {
        val rows = PrivacyPolicy.controls(multiProfileSupported = true, documentStartSupported = true)
        val network = rows.first { it.title == "DNS, IP and WebRTC leak testing" }
        assertEquals("NOT TESTED", network.status)
        assertTrue(network.detail.contains("No live network leak test"))
    }

    @Test
    fun unavailableWebViewCapabilitiesAreNotPresentedAsActive() {
        val rows = PrivacyPolicy.controls(multiProfileSupported = false, documentStartSupported = false)
        assertEquals("UNAVAILABLE", rows.first { it.title == "Named profile storage" }.status)
        assertEquals("BEST-EFFORT ONLY", rows.first { it.title == "Document-start injection" }.status)
    }
}
