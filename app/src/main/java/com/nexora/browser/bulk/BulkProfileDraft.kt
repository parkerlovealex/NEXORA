package com.nexora.browser.bulk

import com.nexora.browser.data.ProxyEntry
import org.json.JSONArray
import org.json.JSONObject

/** In-memory configuration for one bulk-creation session. */

data class BulkProxyAssignmentSummary(
    val assignedProfiles: Int,
    val directProfiles: Int,
    val unsupportedAssignments: Int,
    val unusedSavedProxies: Int
)

data class PerProfileDraft(
    var name: String,
    var userAgentMode: String = "AUTO",
    var userAgent: String = "",
    /** GLOBAL, NONE, or a saved proxy ID. */
    var proxySelection: String = "GLOBAL"
)

class BulkProfileDraft {
    var count: Int = 5
    var namePrefix: String = "Profile"
    var targetOs: String = "ANDROID"
    var deviceId: String = ""
    var osVersion: String = ""
    var ramGb: Int = 8
    var storageGb: Int = 128
    var globalUserAgentMode: String = "AUTO"
    var globalUserAgent: String = ""
    var startupUrlsText: String = ""
    var groupName: String = ""
    var globalProxyId: String = ""
    var remark: String = ""

    var webrtcMode: String = "DISABLED"
    var timezoneMode: String = "DEVICE"
    var customTimezone: String = ""
    var languageMode: String = "DEVICE"
    var customLanguage: String = "en-US"
    var canvasNoise: Boolean = true
    var webglNoise: Boolean = true
    var audioNoise: Boolean = true
    // Persisted as planned preferences; current WebView engine does not apply these three.
    var mediaDevicesNoise: Boolean = false
    var clientRectsNoise: Boolean = false
    var speechSynthesisNoise: Boolean = false

    var dntMode: String = "DEFAULT"
    var hardwareAcceleration: Boolean = true
    var blockImages: Boolean = false

    val perProfiles: MutableList<PerProfileDraft> = mutableListOf()

    fun ensureProfiles() {
        val target = count.coerceIn(1, 50)
        while (perProfiles.size < target) {
            val index = perProfiles.size + 1
            perProfiles.add(PerProfileDraft("$namePrefix $index"))
        }
        while (perProfiles.size > target) perProfiles.removeAt(perProfiles.lastIndex)
    }

    /**
     * Assigns saved proxies in library order, one proxy per profile. Profiles beyond
     * the available proxy count are explicitly set to direct rather than inheriting
     * the same global proxy. Unsupported entries remain assigned but are reported;
     * the validator prevents creating profiles with proxies unsupported by the current NEXORA relay.
     */
    fun assignSavedProxiesInOrder(proxies: List<ProxyEntry>): BulkProxyAssignmentSummary {
        ensureProfiles()
        val target = count.coerceIn(1, 50)
        val assigned = minOf(target, proxies.size)
        for (index in 0 until target) {
            perProfiles[index].proxySelection = proxies.getOrNull(index)?.id ?: "NONE"
        }
        val selected = proxies.take(assigned)
        return BulkProxyAssignmentSummary(
            assignedProfiles = assigned,
            directProfiles = target - assigned,
            unsupportedAssignments = selected.count { !it.isSupportedByProxyRelay },
            unusedSavedProxies = (proxies.size - assigned).coerceAtLeast(0)
        )
    }

    /** Reset legacy values that were once visible but are not implemented by this WebView build. */
    fun normalizeUnavailableSelections(): List<String> {
        val reset = mutableListOf<String>()
        if (webrtcMode == "PROXY_IP") {
            webrtcMode = "DISABLED"
            reset += "WebRTC proxy routing"
        }
        if (timezoneMode == "IP") {
            timezoneMode = "DEVICE"
            reset += "IP-based timezone"
        }
        if (languageMode == "IP") {
            languageMode = "DEVICE"
            reset += "IP-based language"
        }
        if (mediaDevicesNoise) { mediaDevicesNoise = false; reset += "Media device protection" }
        if (clientRectsNoise) { clientRectsNoise = false; reset += "Client Rects protection" }
        if (speechSynthesisNoise) { speechSynthesisNoise = false; reset += "Speech synthesis protection" }
        return reset
    }

    fun toJson(): JSONObject {
        val profiles = JSONArray()
        perProfiles.forEach { row ->
            profiles.put(
                JSONObject()
                    .put("name", row.name)
                    .put("userAgentMode", row.userAgentMode)
                    .put("userAgent", row.userAgent)
                    .put("proxySelection", row.proxySelection)
            )
        }
        return JSONObject()
            .put("count", count).put("namePrefix", namePrefix).put("targetOs", targetOs)
            .put("deviceId", deviceId).put("osVersion", osVersion).put("ramGb", ramGb).put("storageGb", storageGb)
            .put("globalUserAgentMode", globalUserAgentMode).put("globalUserAgent", globalUserAgent)
            .put("startupUrlsText", startupUrlsText).put("groupName", groupName).put("globalProxyId", globalProxyId)
            .put("remark", remark).put("webrtcMode", webrtcMode).put("timezoneMode", timezoneMode)
            .put("customTimezone", customTimezone).put("languageMode", languageMode).put("customLanguage", customLanguage)
            .put("canvasNoise", canvasNoise).put("webglNoise", webglNoise).put("audioNoise", audioNoise)
            .put("mediaDevicesNoise", mediaDevicesNoise).put("clientRectsNoise", clientRectsNoise)
            .put("speechSynthesisNoise", speechSynthesisNoise).put("dntMode", dntMode)
            .put("hardwareAcceleration", hardwareAcceleration).put("blockImages", blockImages)
            .put("perProfiles", profiles)
    }

    companion object {
        fun fromJson(o: JSONObject): BulkProfileDraft {
            val draft = BulkProfileDraft()
            draft.count = o.optInt("count", 5).coerceIn(1, 50)
            draft.namePrefix = o.optString("namePrefix", "Profile")
            draft.targetOs = o.optString("targetOs", "ANDROID")
            draft.deviceId = o.optString("deviceId")
            draft.osVersion = o.optString("osVersion")
            draft.ramGb = o.optInt("ramGb", 8)
            draft.storageGb = o.optInt("storageGb", 128)
            draft.globalUserAgentMode = o.optString("globalUserAgentMode", "AUTO")
            draft.globalUserAgent = o.optString("globalUserAgent")
            draft.startupUrlsText = o.optString("startupUrlsText")
            draft.groupName = o.optString("groupName")
            draft.globalProxyId = o.optString("globalProxyId")
            draft.remark = o.optString("remark")
            draft.webrtcMode = o.optString("webrtcMode", "DISABLED")
            draft.timezoneMode = o.optString("timezoneMode", "DEVICE")
            draft.customTimezone = o.optString("customTimezone")
            draft.languageMode = o.optString("languageMode", "DEVICE")
            draft.customLanguage = o.optString("customLanguage", "en-US")
            draft.canvasNoise = o.optBoolean("canvasNoise", true)
            draft.webglNoise = o.optBoolean("webglNoise", true)
            draft.audioNoise = o.optBoolean("audioNoise", true)
            draft.mediaDevicesNoise = o.optBoolean("mediaDevicesNoise", false)
            draft.clientRectsNoise = o.optBoolean("clientRectsNoise", false)
            draft.speechSynthesisNoise = o.optBoolean("speechSynthesisNoise", false)
            draft.dntMode = o.optString("dntMode", "DEFAULT")
            draft.hardwareAcceleration = o.optBoolean("hardwareAcceleration", true)
            draft.blockImages = o.optBoolean("blockImages", false)
            val rows = o.optJSONArray("perProfiles") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                draft.perProfiles.add(
                    PerProfileDraft(
                        name = row.optString("name"),
                        userAgentMode = row.optString("userAgentMode", "AUTO"),
                        userAgent = row.optString("userAgent"),
                        proxySelection = row.optString("proxySelection", "GLOBAL")
                    )
                )
            }
            draft.ensureProfiles()
            return draft
        }
    }
}
