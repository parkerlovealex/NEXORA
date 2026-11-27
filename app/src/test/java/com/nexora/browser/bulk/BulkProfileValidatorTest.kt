package com.nexora.browser.bulk

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProxyEntry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BulkProfileValidatorTest {
    private fun draft() = BulkProfileDraft().apply {
        count = 2
        namePrefix = "Test"
        targetOs = "ANDROID"
        deviceId = "pixel"
        osVersion = "14"
        perProfiles.clear()
        perProfiles += PerProfileDraft("Test 1")
        perProfiles += PerProfileDraft("Test 2")
        startupUrlsText = "https://example.com"
    }

    private val presets = listOf(
        DevicePreset(
            id = "pixel", brand = "Google", model = "Pixel", uaModel = "Pixel",
            widthPx = 1080, heightPx = 2400, dpr = 3.0,
            ramOptions = listOf(8), storageOptions = listOf(128), cores = 8,
            gpu = "Adreno", osVersions = listOf("14", "15"), isIos = false
        )
    )

    @Test
    fun acceptsValidBatch() {
        assertTrue(BulkProfileValidator.validate(draft(), emptyList(), presets, emptyList()).isEmpty())
    }

    @Test
    fun rejectsDuplicateNamesAndNonHttpStartupUrl() {
        val draft = draft()
        draft.perProfiles[1].name = "Test 1"
        draft.startupUrlsText = "javascript:alert(1)"
        val errors = BulkProfileValidator.validate(draft, emptyList(), presets, emptyList())
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("unique") })
        assertTrue(errors.any { it.contains("HTTP or HTTPS") })
    }

    @Test
    fun acceptsAuthenticatedHttpProxyThroughTheLocalRelay() {
        val draft = draft()
        draft.globalProxyId = "proxy-1"
        val proxy = ProxyEntry("proxy-1", "HTTP proxy", "HTTP", "proxy.example", 8080, true, "encrypted", 1L)
        val errors = BulkProfileValidator.validate(draft, emptyList(), presets, listOf(proxy))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun rejectsImportOnlySocks4ProxyAssignment() {
        val draft = draft()
        draft.globalProxyId = "proxy-1"
        val proxy = ProxyEntry("proxy-1", "SOCKS4 proxy", "SOCKS4", "proxy.example", 1080, false, "", 1L)
        val errors = BulkProfileValidator.validate(draft, emptyList(), presets, listOf(proxy))
        assertTrue(errors.any { it.contains("SOCKS4 is imported but not supported") })
    }
    @Test
    fun rejectsUnsupportedNetworkModesAndRoadmapOnlyProtections() {
        val proxyWebRtc = draft().apply { webrtcMode = "PROXY_IP" }
        assertTrue(BulkProfileValidator.validate(proxyWebRtc, emptyList(), presets, emptyList()).any {
            it.contains("supported WebRTC API mode")
        })

        val ipTimezone = draft().apply { timezoneMode = "IP" }
        assertTrue(BulkProfileValidator.validate(ipTimezone, emptyList(), presets, emptyList()).any {
            it.contains("Device or Custom timezone")
        })

        val ipLanguage = draft().apply { languageMode = "IP" }
        assertTrue(BulkProfileValidator.validate(ipLanguage, emptyList(), presets, emptyList()).any {
            it.contains("Device or Custom language")
        })

        val pendingProtection = draft().apply { mediaDevicesNoise = true }
        assertTrue(BulkProfileValidator.validate(pendingProtection, emptyList(), presets, emptyList()).any {
            it.contains("Roadmap-only fingerprint controls")
        })
    }

    @Test
    fun normalizesLegacyUnavailableSelectionsToSupportedDefaults() {
        val draft = draft().apply {
            webrtcMode = "PROXY_IP"
            timezoneMode = "IP"
            languageMode = "IP"
            mediaDevicesNoise = true
            clientRectsNoise = true
            speechSynthesisNoise = true
        }
        val reset = draft.normalizeUnavailableSelections()
        assertEquals("DISABLED", draft.webrtcMode)
        assertEquals("DEVICE", draft.timezoneMode)
        assertEquals("DEVICE", draft.languageMode)
        assertFalse(draft.mediaDevicesNoise)
        assertFalse(draft.clientRectsNoise)
        assertFalse(draft.speechSynthesisNoise)
        assertEquals(6, reset.size)
    }

}
