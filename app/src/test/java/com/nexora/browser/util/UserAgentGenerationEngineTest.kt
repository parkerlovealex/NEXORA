package com.nexora.browser.util

import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.UserAgentTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentGenerationEngineTest {
    private val presets = listOf(
        device("pixel-9", "Google", "Pixel 9", "Pixel 9", false, listOf("14", "15")),
        device("galaxy-s24-ultra", "Samsung", "Galaxy S24 Ultra", "SM-S928B", false, listOf("14", "15")),
        device("iphone-16", "Apple", "iPhone 16", "iPhone", true, listOf("18")),
        device("iphone-15", "Apple", "iPhone 15", "iPhone", true, listOf("17", "18"))
    )
    private val nativeUa = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Test fun mixedOsGenerationReturnsIndependentRequestedQuantities() {
        val request = UserAgentGenerationRequest(androidCount = 3, iosCount = 2)
        val rows = UserAgentGenerationEngine.generate(request, presets, emptyList(), nativeUa, "2026.11.24.1", seed = 7)
        assertEquals(5, rows.size)
        assertEquals(3, rows.count { it.targetOs == "ANDROID" })
        assertEquals(2, rows.count { it.targetOs == "IOS" })
        assertTrue(rows.filter { it.targetOs == "ANDROID" }.all { it.userAgent.contains("Chrome/131") })
        assertTrue(rows.filter { it.targetOs == "IOS" }.all { it.userAgent.contains("iPhone OS 18") })
    }

    @Test fun selectedDevicePoolNeverEmitsUnselectedDevice() {
        val request = UserAgentGenerationRequest(
            androidCount = 8, iosCount = 0,
            androidDeviceMode = "SELECTED",
            selectedAndroidDeviceIds = setOf("galaxy-s24-ultra"),
            distributionMode = "RANDOM"
        )
        val rows = UserAgentGenerationEngine.generate(request, presets, emptyList(), nativeUa, "db", seed = 11)
        assertEquals(8, rows.size)
        assertTrue(rows.all { it.deviceId == "galaxy-s24-ultra" })
    }

    @Test fun balancedDistributionDiffersByAtMostOne() {
        val request = UserAgentGenerationRequest(androidCount = 5, iosCount = 0, distributionMode = "BALANCED")
        val rows = UserAgentGenerationEngine.generate(request, presets, emptyList(), nativeUa, "db", seed = 12)
        val counts = rows.groupingBy { it.deviceId }.eachCount().values
        assertEquals(2, counts.size)
        assertTrue(counts.maxOrNull()!! - counts.minOrNull()!! <= 1)
    }

    @Test fun customDistributionHonorsExactPerDeviceQuantities() {
        val request = UserAgentGenerationRequest(
            androidCount = 4, iosCount = 2, distributionMode = "CUSTOM",
            customDeviceCounts = mapOf("pixel-9" to 1, "galaxy-s24-ultra" to 3, "iphone-16" to 2)
        )
        val rows = UserAgentGenerationEngine.generate(request, presets, emptyList(), nativeUa, "db", seed = 13)
        assertEquals(1, rows.count { it.deviceId == "pixel-9" })
        assertEquals(3, rows.count { it.deviceId == "galaxy-s24-ultra" })
        assertEquals(2, rows.count { it.deviceId == "iphone-16" })
    }

    @Test fun customQuantitiesForAnInactiveOsAreRejected() {
        val request = UserAgentGenerationRequest(
            androidCount = 2, iosCount = 0, distributionMode = "CUSTOM",
            customDeviceCounts = mapOf("pixel-9" to 2, "iphone-16" to 1)
        )
        val errors = UserAgentGenerationEngine.validate(request, presets, emptyList(), nativeUa)
        assertTrue(errors.any { it.contains("Custom iOS quantities must be zero") })
    }

    @Test fun inAppTemplatesAreOptionalAndMustBeEvidenceBacked() {
        val base = UserAgentGenerationRequest(androidCount = 1, iosCount = 0)
        assertTrue(UserAgentGenerationEngine.validate(base, presets, emptyList(), nativeUa).isEmpty())
        val withApp = base.copy(inAppBrowserIds = setOf("instagram"))
        assertTrue(UserAgentGenerationEngine.validate(withApp, presets, emptyList(), nativeUa).any { it.contains("No verified Android in-app") })
        val template = UserAgentTemplate(
            id = "instagram-android", appId = "instagram", displayName = "Instagram",
            targetOs = "ANDROID", userAgent = "Mozilla/5.0 (Linux; Android 15) Instagram/verified",
            sourceUrl = "https://example.org/source", evidenceQuote = "Quoted evidence", verified = true
        )
        assertTrue(UserAgentGenerationEngine.validate(withApp, presets, listOf(template), nativeUa).isEmpty())
        val mixedVariants = UserAgentGenerationEngine.generate(
            withApp.copy(androidCount = 2), presets, listOf(template), nativeUa, "db", seed = 21
        )
        assertTrue(mixedVariants.any { it.browserFamily == "In-app WebView" && it.inAppBrowserId == "instagram" })
        assertTrue(mixedVariants.any { it.browserFamily == "Chrome" && it.inAppBrowserId.isBlank() })
    }

    @Test fun iosSafariTemplateUsesSelectedOsVersionAndDoesNotClaimModelIdentity() {
        val ua = UaBuilder.buildIosSafariTemplate("18.2")
        assertTrue(ua.contains("iPhone OS 18_2"))
        assertTrue(ua.contains("Version/18.2"))
        assertFalse(ua.contains("iPhone 16"))
    }

    @Test fun unsupportedBrowserFamilyFailsClosed() {
        val request = UserAgentGenerationRequest(androidCount = 1, iosCount = 0, androidBrowserFamilies = setOf("Firefox"))
        val errors = UserAgentGenerationEngine.validate(request, presets, emptyList(), nativeUa)
        assertFalse(errors.isEmpty())
    }

    private fun device(id: String, brand: String, model: String, uaModel: String, ios: Boolean, versions: List<String>) =
        DevicePreset(id, brand, model, uaModel, 1080, 2400, 3.0, listOf(8), listOf(128), 8, "Test GPU", versions, ios)
}
