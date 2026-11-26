package com.nexora.browser.ui

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProxyEntry
import java.net.URI
import java.util.TimeZone

/**
 * Validation for the single-profile editor.
 *
 * Proxy support is provided by NEXORA's local relay. WebView receives only a
 * loopback HTTP proxy, while credentials remain encrypted or in memory.
 */
object ProfileEditorValidator {

    fun validate(
        profile: BrowserProfile,
        existingProfiles: List<BrowserProfile>,
        presets: List<DevicePreset>,
        proxyEntries: List<ProxyEntry> = emptyList()
    ): List<String> {
        val errors = mutableListOf<String>()
        val name = profile.name.trim()

        if (name.isBlank()) errors += "Enter a profile name."
        if (name.length > 60) errors += "Profile name must be 60 characters or fewer."
        if (existingProfiles.any {
                it.id != profile.id && it.name.trim().equals(name, ignoreCase = true)
            }) {
            errors += "A profile with this name already exists."
        }

        if (profile.groupName.length > 40) errors += "Profile group must be 40 characters or fewer."
        if (profile.remark.length > 500) errors += "Remark must be 500 characters or fewer."

        val preset = presets.firstOrNull { it.id == profile.deviceId }
        if (preset == null) {
            errors += "Choose a supported device preset."
        } else {
            if (profile.targetOs !in setOf("ANDROID", "IOS")) errors += "Choose a supported target OS."
            if (profile.targetOs == "ANDROID" && preset.isIos) errors += "Choose an Android device preset."
            if (profile.targetOs == "IOS" && !preset.isIos) errors += "Choose an iOS emulation preset."
            if (profile.osVersion !in preset.osVersions) errors += "Choose a valid OS version for this device."
            if (profile.ramGb !in preset.ramOptions) errors += "Choose a supported RAM option for this device."
            if (profile.storageGb !in preset.storageOptions) errors += "Choose a supported storage option for this device."
        }

        if (profile.userAgentMode !in setOf("AUTO", "CUSTOM")) {
            errors += "Choose a valid User-Agent mode."
        }
        if (!validUserAgent(profile.userAgent)) {
            errors += "The User-Agent is missing or invalid. Regenerate it or enter a valid Mozilla User-Agent."
        }

        if (profile.proxyEntryId.isNotBlank()) {
            val entry = proxyEntries.firstOrNull { it.id == profile.proxyEntryId }
            if (entry == null) {
                errors += "The selected saved proxy no longer exists. Choose another proxy or No Proxy."
            } else if (!entry.isSupportedByProxyRelay) {
                errors += "${entry.name}: ${entry.type} is not supported by the current NEXORA proxy relay."
            } else if (
                profile.proxyType != entry.type ||
                profile.proxyHost != entry.host ||
                profile.proxyPort != entry.port ||
                profile.proxyUser.isNotBlank() ||
                profile.proxyPass.isNotBlank()
            ) {
                errors += "Saved proxy details do not match the selected library entry. Re-select the saved proxy."
            }
        } else if (profile.proxyType !in setOf("NONE", "HTTP", "HTTPS", "SOCKS5")) {
            errors += "This proxy type is not supported by the current NEXORA proxy relay."
        } else if (profile.proxyType != "NONE") {
            if (profile.proxyHost.isBlank()) errors += "Enter the proxy host."
            if (profile.proxyHost.any { it.isWhitespace() || it in "/?#@" } ||
                profile.proxyHost.contains("://")
            ) {
                errors += "Enter a host or IP address only, without a scheme or path."
            }
            if (profile.proxyPort !in 1..65535) errors += "Enter a valid proxy port (1–65535)."
            if (profile.proxyUser.length > 255 || profile.proxyPass.length > 255) {
                errors += "Proxy username and password must each be 255 characters or fewer."
            }
        }

        if (profile.timezoneMode !in setOf("DEVICE", "CUSTOM")) {
            errors += "Choose Device or Custom timezone mode."
        }
        if (profile.timezoneMode == "CUSTOM" &&
            (profile.timezoneId.isBlank() || profile.timezoneId !in TimeZone.getAvailableIDs())
        ) {
            errors += "Enter a valid IANA timezone identifier."
        }
        if (profile.timezoneMode == "DEVICE" && profile.timezoneId.isNotBlank()) {
            errors += "Device timezone mode must not contain a custom timezone."
        }

        if (profile.languageMode !in setOf("DEVICE", "CUSTOM")) {
            errors += "Choose Device or Custom language mode."
        }
        if (profile.languageMode == "CUSTOM" &&
            !profile.language.matches(Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*$"))
        ) {
            errors += "Enter a valid language tag such as en-US."
        }

        if (profile.webrtcMode !in setOf("DISABLED", "REAL_IP")) {
            errors += "Choose a supported WebRTC mode."
        }
        if (profile.webrtcBlock != (profile.webrtcMode == "DISABLED")) {
            errors += "WebRTC mode and block setting do not match."
        }
        if (profile.mediaDevicesNoise || profile.clientRectsNoise || profile.speechSynthesisNoise) {
            errors += "Roadmap-only fingerprint controls cannot be saved as active protections in this release."
        }

        if (profile.dntMode !in setOf("DEFAULT", "ENABLE", "DISABLE")) {
            errors += "Choose a valid Do Not Track setting."
        }

        val startupUrls = profile.startupUrls.ifEmpty {
            listOf(profile.startupUrl).filter { it.isNotBlank() }
        }
        if (startupUrls.size > MAX_STARTUP_URLS) {
            errors += "A profile can open at most $MAX_STARTUP_URLS startup tabs."
        }
        startupUrls.forEachIndexed { index, value ->
            val url = value.trim()
            try {
                val uri = URI(url)
                if (url.length > 2048 ||
                    uri.scheme?.lowercase() !in setOf("http", "https") ||
                    uri.host.isNullOrBlank()
                ) {
                    errors += "Startup URL ${index + 1} must be a valid HTTP or HTTPS URL."
                }
            } catch (_: Exception) {
                errors += "Startup URL ${index + 1} is invalid."
            }
        }

        return errors.distinct()
    }

    fun parseStartupUrls(raw: String): List<String> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun validUserAgent(value: String): Boolean =
        value.trim().length in 20..2048 && value.contains("Mozilla/", ignoreCase = true)

    const val MAX_STARTUP_URLS = 20
}
