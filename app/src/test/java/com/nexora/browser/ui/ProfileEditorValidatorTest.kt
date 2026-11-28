package com.nexora.browser.ui

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditorValidatorTest {

    private val preset = DevicePreset(
        id = "pixel-test",
        brand = "Google",
        model = "Pixel Test",
        uaModel = "PixelTest",
        widthPx = 1080,
        heightPx = 2400,
        dpr = 3.0,
        ramOptions = listOf(8, 12),
        storageOptions = listOf(128, 256),
        cores = 8,
        gpu = "Adreno Test",
        osVersions = listOf("Android 14", "Android 15"),
        isIos = false
    )

    private fun validProfile(): BrowserProfile = BrowserProfile(
        id = "profile-1",
        name = "Research",
        deviceId = preset.id,
        osVersion = "Android 15",
        ramGb = 8,
        storageGb = 128,
        userAgent = "Mozilla/5.0 (Linux; Android 15; PixelTest) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36",
        timezoneMode = "DEVICE",
        languageMode = "DEVICE",
        webrtcMode = "DISABLED"
    )

    @Test
    fun validProfileWithMultipleStartupTabsPasses() {
        val profile = validProfile().copy(
            startupUrl = "https://example.com",
            startupUrls = listOf("https://example.com", "https://example.org/path")
        )
        assertTrue(ProfileEditorValidator.validate(profile, emptyList(), listOf(preset)).isEmpty())
    }

    @Test
    fun duplicateNamesAreCaseInsensitiveButCurrentProfileIsIgnored() {
        val profile = validProfile()
        val existing = listOf(profile.copy(id = "other", name = "research"))
        assertTrue(ProfileEditorValidator.validate(profile, existing, listOf(preset)).contains(
            "A profile with this name already exists."
        ))
        assertFalse(ProfileEditorValidator.validate(profile, listOf(profile), listOf(preset)).any {
            it.contains("already exists")
        })
    }

    @Test
    fun invalidStartupUrlsAndMoreThanTwentyTabsAreRejected() {
        val tooMany = (1..21).map { "https://site$it.example" }
        val profile = validProfile().copy(startupUrls = tooMany + "javascript:alert(1)")
        val errors = ProfileEditorValidator.validate(profile, emptyList(), listOf(preset))
        assertTrue(errors.any { it.contains("at most 20") })
        assertTrue(errors.any { it.contains("HTTP or HTTPS") })
    }

    @Test
    fun authenticatedHttpProxyCanBeValidatedForTheLocalRelay() {
        val profile = validProfile().copy(
            proxyType = "HTTP",
            proxyHost = "proxy.example",
            proxyPort = 8080,
            proxyUser = "account",
            proxyPass = "secret"
        )
        assertTrue(ProfileEditorValidator.validate(profile, emptyList(), listOf(preset)).isEmpty())
    }

    @Test
    fun customTimezoneAndLanguageAreValidated() {
        val good = validProfile().copy(
            timezoneMode = "CUSTOM",
            timezoneId = "Asia/Dhaka",
            languageMode = "CUSTOM",
            language = "bn-BD"
        )
        assertTrue(ProfileEditorValidator.validate(good, emptyList(), listOf(preset)).isEmpty())

        val bad = good.copy(timezoneId = "Not/A_Timezone", language = "bad tag")
        val errors = ProfileEditorValidator.validate(bad, emptyList(), listOf(preset))
        assertTrue(errors.any { it.contains("IANA timezone") })
        assertTrue(errors.any { it.contains("language tag") })
    }

    @Test
    fun devicePresetAndUserAgentMustMatchSupportedOptions() {
        val bad = validProfile().copy(
            deviceId = "missing-device",
            userAgent = "not a browser"
        )
        val errors = ProfileEditorValidator.validate(bad, emptyList(), listOf(preset))
        assertTrue(errors.any { it.contains("device preset") })
        assertTrue(errors.any { it.contains("User-Agent") })
    }

    @Test
    fun proxyPortAndWebRtcModeAreValidated() {
        val bad = validProfile().copy(
            proxyType = "SOCKS5",
            proxyHost = "proxy.example",
            proxyPort = 70000,
            webrtcBlock = false,
            webrtcMode = "DISABLED"
        )
        val errors = ProfileEditorValidator.validate(bad, emptyList(), listOf(preset))
        assertTrue(errors.any { it.contains("proxy port") })
        assertTrue(errors.any { it.contains("WebRTC mode") })
    }

    @Test
    fun startupUrlParserTrimsAndSkipsBlankLines() {
        assertEquals(
            listOf("https://one.example", "http://two.example"),
            ProfileEditorValidator.parseStartupUrls("  https://one.example  \n\nhttp://two.example ")
        )
    }

    @Test
    fun legacyAuthenticatedProxyCanBeEditedAndItsCredentialsChanged() {
        val legacy = validProfile().copy(
            proxyType = "HTTP",
            proxyHost = "proxy.example",
            proxyPort = 8080,
            proxyUser = "account",
            proxyPass = "secret"
        )
        assertTrue(ProfileEditorValidator.validate(legacy, listOf(legacy), listOf(preset)).isEmpty())
        val changed = legacy.copy(proxyPass = "new-secret")
        assertTrue(ProfileEditorValidator.validate(changed, listOf(legacy), listOf(preset)).isEmpty())
    }

    @Test
    fun supportedSavedProxyCanBeAssignedByStableLibraryReference() {
        val entry = com.nexora.browser.data.ProxyEntry(
            id = "proxy-1",
            name = "Office HTTP",
            type = "HTTP",
            host = "proxy.example",
            port = 8080,
            hasCredentials = false,
            encryptedCredentials = "",
            createdAt = 1L
        )
        val profile = validProfile().copy(
            proxyEntryId = entry.id,
            proxyType = entry.type,
            proxyHost = entry.host,
            proxyPort = entry.port
        )
        assertTrue(ProfileEditorValidator.validate(
            profile, emptyList(), listOf(preset), listOf(entry)
        ).isEmpty())
    }

    @Test
    fun missingSavedProxyIsRejectedButAuthenticatedHttpEntryIsSupported() {
        val supported = com.nexora.browser.data.ProxyEntry(
            id = "proxy-2",
            name = "Auth HTTP",
            type = "HTTP",
            host = "proxy.example",
            port = 8080,
            hasCredentials = true,
            encryptedCredentials = "encrypted",
            createdAt = 1L
        )
        val profile = validProfile().copy(
            proxyEntryId = supported.id,
            proxyType = supported.type,
            proxyHost = supported.host,
            proxyPort = supported.port
        )
        val missingErrors = ProfileEditorValidator.validate(
            profile, emptyList(), listOf(preset), emptyList()
        )
        assertTrue(missingErrors.any { it.contains("no longer exists") })

        val supportedErrors = ProfileEditorValidator.validate(
            profile, emptyList(), listOf(preset), listOf(supported)
        )
        assertTrue(supportedErrors.isEmpty())
    }


    @Test
    fun socks4RemainsImportOnlyAndCannotBeAssigned() {
        val entry = com.nexora.browser.data.ProxyEntry(
            id = "proxy-socks4",
            name = "Legacy SOCKS4",
            type = "SOCKS4",
            host = "proxy.example",
            port = 1080,
            hasCredentials = false,
            encryptedCredentials = "",
            createdAt = 1L
        )
        val profile = validProfile().copy(
            proxyEntryId = entry.id,
            proxyType = entry.type,
            proxyHost = entry.host,
            proxyPort = entry.port
        )
        assertTrue(ProfileEditorValidator.validate(
            profile, emptyList(), listOf(preset), listOf(entry)
        ).any { it.contains("SOCKS4 is not supported") })
    }

    @Test
    fun savedProxyFieldsMustMatchTheReferencedLibraryEntry() {
        val entry = com.nexora.browser.data.ProxyEntry(
            id = "proxy-3",
            name = "Office HTTP",
            type = "HTTP",
            host = "proxy.example",
            port = 8080,
            hasCredentials = false,
            encryptedCredentials = "",
            createdAt = 1L
        )
        val tampered = validProfile().copy(
            proxyEntryId = entry.id,
            proxyType = "HTTP",
            proxyHost = "different.example",
            proxyPort = entry.port
        )
        assertTrue(ProfileEditorValidator.validate(
            tampered, emptyList(), listOf(preset), listOf(entry)
        ).any { it.contains("do not match") })
    }
    @Test
    fun roadmapOnlyFingerprintFlagsCannotBeSavedAsActive() {
        val bad = validProfile().copy(
            mediaDevicesNoise = true,
            clientRectsNoise = true,
            speechSynthesisNoise = true
        )
        assertTrue(ProfileEditorValidator.validate(bad, emptyList(), listOf(preset)).any {
            it.contains("Roadmap-only fingerprint controls")
        })
    }

}
