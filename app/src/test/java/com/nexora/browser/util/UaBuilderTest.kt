package com.nexora.browser.util

import com.nexora.browser.data.DevicePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UaBuilderTest {
    private val androidPreset = DevicePreset(
        id = "samsung-a55",
        brand = "Samsung",
        model = "Galaxy A55",
        uaModel = "SM-A556B",
        widthPx = 1080,
        heightPx = 2340,
        dpr = 3.0,
        ramOptions = listOf(8, 12),
        storageOptions = listOf(128, 256),
        cores = 8,
        gpu = "Mali-G715",
        osVersions = listOf("14", "15"),
        isIos = false
    )

    @Test
    fun androidAutoUaUsesInstalledWebViewChromiumVersion() {
        val native = "Mozilla/5.0 (Linux; Android 15; SM-A556B Build/ABC) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/158.0.8278.79 Mobile Safari/537.36"
        val ua = UaBuilder.build(androidPreset, "15", native)
        assertTrue(ua.contains("Android 15; SM-A556B"))
        assertTrue(ua.contains("Chrome/158.0.8278.79"))
        assertFalse(ua.contains("Chrome/126."))
    }

    @Test
    fun desktopUaUsesInstalledWebViewChromiumVersion() {
        val native = "Mozilla/5.0 (Linux; Android 15; SM-A556B) AppleWebKit/537.36 Chrome/158.0.8278.79 Mobile Safari/537.36"
        val ua = UaBuilder.desktopUserAgent(native)
        assertTrue(ua.contains("X11; Linux x86_64"))
        assertTrue(ua.contains("Chrome/158.0.8278.79"))
        assertFalse(ua.contains("Mobile Safari"))
    }

    @Test
    fun missingEngineVersionFallsBackToUnmodifiedNativeUa() {
        val native = "Mozilla/5.0 (Android) WebView"
        assertEquals(native, UaBuilder.build(androidPreset, "15", native))
        assertEquals(native, UaBuilder.desktopUserAgent(native))
    }

    @Test
    fun recognizesOlderGeneratedAndroidUaAsAutoMode() {
        val old = "Mozilla/5.0 (Linux; Android 14; SM-A556B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        assertTrue(UaBuilder.isGeneratedFor(androidPreset, "14", old))
        assertFalse(UaBuilder.isGeneratedFor(androidPreset, "15", old))
        assertFalse(UaBuilder.isGeneratedFor(androidPreset, "14", old.replace("SM-A556B", "Other")))
    }

    @Test
    fun recognizesLabeledIosEmulationUa() {
        val ios = androidPreset.copy(isIos = true)
        val ua = UaBuilder.build(ios, "17.5", "ignored native UA")
        assertTrue(UaBuilder.isGeneratedFor(ios, "17.5", ua))
    }
}
