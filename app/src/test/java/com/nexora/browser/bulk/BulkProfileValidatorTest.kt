package com.nexora.browser.bulk

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProxyEntry
import org.junit.Assert.assertTrue
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
    fun rejectsUnsupportedAuthenticatedProxy() {
        val draft = draft()
        draft.globalProxyId = "proxy-1"
        val proxy = ProxyEntry("proxy-1", "HTTP proxy", "HTTP", "proxy.example", 8080, true, "encrypted", 1L)
        val errors = BulkProfileValidator.validate(draft, emptyList(), presets, listOf(proxy))
        assertTrue(errors.any { it.contains("authenticated proxies") })
    }
}
