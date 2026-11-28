package com.nexora.browser.util

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.GeneratedUserAgent
import com.nexora.browser.fp.FingerprintScript
import org.json.JSONObject
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

    @Test fun explicitOsVersionConstrainsRandomBalancedSelectedAndCustomPools() {
        val requested = UserAgentGenerationRequest(androidCount = 0, iosCount = 12, iosOsVersion = "17")
        for (mode in listOf("RANDOM", "BALANCED")) {
            val rows = UserAgentGenerationEngine.generate(requested.copy(distributionMode = mode), presets, emptyList(), nativeUa, "db", seed = 4)
            assertTrue(rows.all { it.deviceId == "iphone-15" && it.osVersion == "17" })
        }
        val incompatible = requested.copy(iosDeviceMode = "SELECTED", selectedIosDeviceIds = setOf("iphone-16"))
        assertTrue(UserAgentGenerationEngine.validate(incompatible, presets, emptyList(), nativeUa).isNotEmpty())
        val custom = requested.copy(distributionMode = "CUSTOM", customDeviceCounts = mapOf("iphone-16" to 12))
        assertTrue(UserAgentGenerationEngine.validate(custom, presets, emptyList(), nativeUa).isNotEmpty())
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
        val ua = UaBuilder.buildIosSafariTemplate("18.2", "17.6")
        assertTrue(ua.contains("iPhone OS 18_2"))
        assertTrue(ua.contains("Version/17.6"))
        assertFalse(ua.contains("Mobile/"))
        assertFalse(ua.contains("iPhone 16"))
    }

    @Test fun explicitSafariVersionIsIndependentAndSurvivesBatchAndProfileStorage() {
        val ios = presets.last().copy(safariVersion = "")
        val request = UserAgentGenerationRequest(0, 1, manualSafariVersion = " 16.6.1 ")
        val row = UserAgentGenerationEngine.generate(request, listOf(ios), emptyList(), "", "test").single()
        assertTrue(row.userAgent.contains("iPhone OS 18 "))
        assertTrue(row.userAgent.contains("Version/16.6.1 "))
        assertEquals("16.6.1", GeneratedUserAgent.fromJson(row.toJson()).manualSafariVersion)
        val profile = BrowserProfile.fromJson(JSONObject().put("manualSafariVersion", row.manualSafariVersion))
        assertEquals("16.6.1", BrowserProfile.fromJson(profile.toJson()).manualSafariVersion)
        val fromCatalog = UserAgentGenerationEngine.generate(request, listOf(ios.copy(safariVersion = "17.4")), emptyList(), "", "test").single()
        assertTrue(fromCatalog.userAgent.contains("Version/17.4 "))
        assertTrue(fromCatalog.manualSafariVersion.isEmpty())
        for (invalid in listOf("", "latest", "18..2", "18.2 extra")) {
            assertTrue(UserAgentGenerationEngine.validate(request.copy(manualSafariVersion = invalid), listOf(ios), emptyList(), "").isNotEmpty())
        }
        assertTrue(runCatching { UaBuilder.buildIosSafariTemplate("", "16.6.1") }.isFailure)
        assertTrue(UserAgentGenerationEngine.validate(request, listOf(ios.copy(osVersions = emptyList())), emptyList(), "").isNotEmpty())
    }

    @Test fun pastedUserAgentNeedsNoGeneratedSafariVersion() {
        val values = listOf("Mozilla/5.0 (iPhone) user supplied A", "Mozilla/5.0 (iPhone) user supplied B")
        val request = UserAgentGenerationRequest(0, 2, pastedUserAgents = mapOf("IOS" to values))
        val rows = UserAgentGenerationEngine.generate(request, listOf(presets.last().copy(safariVersion = "")), emptyList(), "", "test")
        assertEquals(values, rows.map { it.userAgent })
        assertTrue(rows.all { it.manualSafariVersion.isEmpty() && it.browserFamily == "Custom UA" })
    }

    @Test fun catalogReadsIndependentSafariVersionAndLeavesMissingHardwareUnknown() {
        val text = """{"meta":{"browserVersions":{"safariIos":[null,"invalid","17.3"]}},"ios_emulation":[
            {"id":"fixture","brand":"Apple","model":"Test","uaModel":"iPhone","display":{"w":100,"h":200,"dpr":2},
             "osVersions":["18.0"],"builds":{"18.0":"FixtureBuild1"}}]}"""
        val preset = DeviceDb.parseCatalog(text)!!.single()
        assertEquals("17.3", preset.safariVersion)
        val ua = UaBuilder.build(preset, "18.0", "")
        assertTrue(ua.contains("Version/17.3 Mobile/FixtureBuild1"))
        assertEquals(0, preset.cores)
        assertTrue(preset.ramOptions.isEmpty() && preset.storageOptions.isEmpty())
        val profile = BrowserProfile.fromJson(JSONObject())
        val script = FingerprintScript.build(profile, preset)
        assertFalse(script.contains("__nxDef(navigator, 'hardwareConcurrency'"))
        assertFalse(script.contains("__nxDef(navigator, 'deviceMemory'"))
    }

    @Test fun unsupportedBrowserFamilyFailsClosed() {
        val request = UserAgentGenerationRequest(androidCount = 1, iosCount = 0, androidBrowserFamilies = setOf("Firefox"))
        val errors = UserAgentGenerationEngine.validate(request, presets, emptyList(), nativeUa)
        assertFalse(errors.isEmpty())
    }

    private fun device(id: String, brand: String, model: String, uaModel: String, ios: Boolean, versions: List<String>) =
        DevicePreset(id, brand, model, uaModel, 1080, 2400, 3.0, listOf(8), listOf(128), 8, "Test GPU", versions, ios, safariVersion = if (ios) "17.4" else "")
}
