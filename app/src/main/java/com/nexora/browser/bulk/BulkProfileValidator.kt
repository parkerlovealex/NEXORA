package com.nexora.browser.bulk

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProxyEntry
import java.net.URI

object BulkProfileValidator {
    fun validate(
        draft: BulkProfileDraft,
        existingProfiles: List<BrowserProfile>,
        presets: List<DevicePreset>,
        proxies: List<ProxyEntry>
    ): List<String> {
        val errors = mutableListOf<String>()
        if (draft.count !in 1..50) errors += "Profile count must be between 1 and 50."
        val safeCount = draft.count.coerceIn(0, 50)
        val prefix = draft.namePrefix.trim()
        if (prefix.isBlank()) errors += "Enter a profile name prefix."
        if (prefix.length > 60) errors += "Profile name prefix must be 60 characters or fewer."
        if (draft.perProfiles.size != draft.count) errors += "Per-profile settings do not match the batch count."

        val names = draft.perProfiles.take(safeCount).map { it.name.trim() }
        if (names.any { it.isBlank() }) errors += "Every profile needs a name."
        if (names.any { it.length > 60 }) errors += "Profile names must be 60 characters or fewer."
        if (names.map { it.lowercase() }.toSet().size != names.size) errors += "Generated profile names must be unique."
        val existingNames = existingProfiles.map { it.name.trim().lowercase() }.toSet()
        if (names.any { it.lowercase() in existingNames }) errors += "A generated name already exists. Change the prefix or profile names."

        if (draft.targetOs !in setOf("ANDROID", "IOS")) errors += "Choose a supported target OS."
        if (draft.globalUserAgentMode !in setOf("AUTO", "CUSTOM")) errors += "Choose a valid global User-Agent mode."
        val preset = presets.firstOrNull { it.id == draft.deviceId }
        if (preset == null) errors += "Choose a supported device preset."
        else {
            if (draft.targetOs == "ANDROID" && preset.isIos) errors += "Choose an Android device preset for Android mode."
            if (draft.targetOs == "IOS" && !preset.isIos) errors += "Choose an iOS emulation preset for iOS mode."
            if (draft.osVersion !in preset.osVersions) errors += "Choose a valid OS version for the selected device."
        }

        val startupUrls = parseStartupUrls(draft.startupUrlsText)
        if (startupUrls.size > 20) errors += "A profile can open at most 20 startup tabs in this release."
        startupUrls.forEachIndexed { index, url ->
            try {
                val uri = URI(url)
                if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    errors += "Startup URL ${index + 1} must be a valid HTTP or HTTPS URL."
                }
            } catch (_: Exception) {
                errors += "Startup URL ${index + 1} is invalid."
            }
        }

        if (draft.globalUserAgentMode == "CUSTOM" && !validUa(draft.globalUserAgent)) {
            errors += "The global custom User-Agent is missing or invalid."
        }
        draft.perProfiles.take(safeCount).forEachIndexed { index, row ->
            if (row.userAgentMode !in setOf("AUTO", "CUSTOM")) errors += "${row.name.ifBlank { "Profile ${index + 1}" }} has an invalid User-Agent mode."
            if (row.userAgentMode == "CUSTOM" && !validUa(row.userAgent)) {
                errors += "${row.name.ifBlank { "Profile ${index + 1}" }} has an invalid custom User-Agent."
            }
        }

        val proxyById = proxies.associateBy { it.id }
        val selectedIds = buildSet {
            val usesGlobal = draft.perProfiles.take(safeCount).any { it.proxySelection == "GLOBAL" }
            if (usesGlobal && draft.globalProxyId.isNotBlank()) add(draft.globalProxyId)
            draft.perProfiles.take(safeCount).forEach { row ->
                if (row.proxySelection.isNotBlank() && row.proxySelection != "GLOBAL" && row.proxySelection != "NONE") add(row.proxySelection)
            }
        }
        selectedIds.forEach { id ->
            val proxy = proxyById[id]
            if (proxy == null) errors += "A selected proxy no longer exists in the proxy library."
            else if (!proxy.isSupportedByProxyRelay) {
                errors += "${proxy.name}: ${proxy.type} is imported but not supported by the current NEXORA proxy relay."
            }
        }

        if (draft.webrtcMode !in setOf("DISABLED", "REAL_IP")) errors += "Choose a supported WebRTC API mode."
        if (draft.timezoneMode !in setOf("DEVICE", "CUSTOM")) errors += "Choose Device or Custom timezone mode."
        if (draft.languageMode !in setOf("DEVICE", "CUSTOM")) errors += "Choose Device or Custom language mode."
        if (draft.mediaDevicesNoise || draft.clientRectsNoise || draft.speechSynthesisNoise) {
            errors += "Roadmap-only fingerprint controls cannot be saved as active protections in this release."
        }
        if (draft.dntMode !in setOf("DEFAULT", "ENABLE", "DISABLE")) errors += "Choose a valid Do Not Track setting."
        if (draft.timezoneMode == "CUSTOM" && draft.customTimezone !in java.util.TimeZone.getAvailableIDs()) {
            errors += "Enter a valid IANA timezone identifier."
        }
        if (draft.languageMode == "CUSTOM" && !draft.customLanguage.matches(Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*$"))) {
            errors += "Enter a valid language tag such as en-US."
        }
        return errors.distinct()
    }

    fun parseStartupUrls(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun validUa(value: String): Boolean =
        value.trim().length in 20..2048 && value.contains("Mozilla/", ignoreCase = true)
}
