package com.nexora.browser.data

import org.json.JSONObject

/** One browsing identity. Serialized to profiles.json by [ProfileStore]. */
data class BrowserProfile(
    val id: String,
    var name: String,
    var deviceId: String,
    var osVersion: String,
    var ramGb: Int,
    var storageGb: Int,
    var userAgent: String,
    /** "NONE" | "HTTP" | "HTTPS" | "SOCKS5" */
    var proxyType: String = "NONE",
    var proxyHost: String = "",
    var proxyPort: Int = 0,
    var proxyUser: String = "",
    var proxyPass: String = "",
    /** Stable reference to an encrypted entry in ProxyLibraryStore; blank means manual settings. */
    var proxyEntryId: String = "",
    /** IANA zone id, "" = follow device. */
    var timezoneId: String = "",
    var language: String = "en-US",
    var webrtcBlock: Boolean = true,
    var canvasNoise: Boolean = true,
    var webglSpoof: Boolean = true,
    var audioNoise: Boolean = true,
    var startupUrl: String = "",
    /** Additional startup tabs; startupUrl remains as a backward-compatible first URL. */
    var startupUrls: List<String> = emptyList(),
    var groupName: String = "",
    var remark: String = "",
    var targetOs: String = "ANDROID",
    var userAgentMode: String = "AUTO",
    var timezoneMode: String = "DEVICE",
    var languageMode: String = "DEVICE",
    var webrtcMode: String = "DISABLED",
    var mediaDevicesNoise: Boolean = false,
    var clientRectsNoise: Boolean = false,
    var speechSynthesisNoise: Boolean = false,
    var dntMode: String = "DEFAULT",
    var hardwareAcceleration: Boolean = true,
    var blockImages: Boolean = false,
    /** Stable per-profile seed for the fingerprint noise (canvas/audio). */
    var seed: Int = 0,
    var createdAt: Long = 0L,
    var lastUsedAt: Long = 0L
) {
    /** Key used for the androidx.webkit per-profile data store. */
    val webViewProfileKey: String get() = "nx_" + id.take(8)

    val hasProxy: Boolean get() = proxyType != "NONE" && proxyHost.isNotEmpty() && proxyPort in 1..65535

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("deviceId", deviceId)
        o.put("osVersion", osVersion)
        o.put("ramGb", ramGb)
        o.put("storageGb", storageGb)
        o.put("userAgent", userAgent)
        o.put("proxyType", proxyType)
        o.put("proxyHost", proxyHost)
        o.put("proxyPort", proxyPort)
        o.put("proxyUser", proxyUser)
        o.put("proxyPass", proxyPass)
        o.put("proxyEntryId", proxyEntryId)
        o.put("timezoneId", timezoneId)
        o.put("language", language)
        o.put("webrtcBlock", webrtcBlock)
        o.put("canvasNoise", canvasNoise)
        o.put("webglSpoof", webglSpoof)
        o.put("audioNoise", audioNoise)
        o.put("startupUrl", startupUrl)
        val urls = org.json.JSONArray()
        startupUrls.forEach { urls.put(it) }
        o.put("startupUrls", urls)
        o.put("groupName", groupName)
        o.put("remark", remark)
        o.put("targetOs", targetOs)
        o.put("userAgentMode", userAgentMode)
        o.put("timezoneMode", timezoneMode)
        o.put("languageMode", languageMode)
        o.put("webrtcMode", webrtcMode)
        o.put("mediaDevicesNoise", mediaDevicesNoise)
        o.put("clientRectsNoise", clientRectsNoise)
        o.put("speechSynthesisNoise", speechSynthesisNoise)
        o.put("dntMode", dntMode)
        o.put("hardwareAcceleration", hardwareAcceleration)
        o.put("blockImages", blockImages)
        o.put("seed", seed)
        o.put("createdAt", createdAt)
        o.put("lastUsedAt", lastUsedAt)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): BrowserProfile = BrowserProfile(
            id = o.optString("id"),
            name = o.optString("name", "Profile"),
            deviceId = o.optString("deviceId"),
            osVersion = o.optString("osVersion"),
            ramGb = o.optInt("ramGb", 8),
            storageGb = o.optInt("storageGb", 128),
            userAgent = o.optString("userAgent"),
            proxyType = o.optString("proxyType", "NONE"),
            proxyHost = o.optString("proxyHost"),
            proxyPort = o.optInt("proxyPort", 0),
            proxyUser = o.optString("proxyUser"),
            proxyPass = o.optString("proxyPass"),
            proxyEntryId = o.optString("proxyEntryId"),
            timezoneId = o.optString("timezoneId"),
            language = o.optString("language", "en-US"),
            webrtcBlock = o.optBoolean("webrtcBlock", true),
            canvasNoise = o.optBoolean("canvasNoise", true),
            webglSpoof = o.optBoolean("webglSpoof", true),
            audioNoise = o.optBoolean("audioNoise", true),
            startupUrl = o.optString("startupUrl"),
            startupUrls = run {
                val array = o.optJSONArray("startupUrls")
                if (array == null) emptyList() else buildList {
                    for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            },
            groupName = o.optString("groupName"),
            remark = o.optString("remark"),
            targetOs = o.optString("targetOs", "ANDROID"),
            userAgentMode = o.optString("userAgentMode", "AUTO"),
            timezoneMode = o.optString("timezoneMode", if (o.optString("timezoneId").isBlank()) "DEVICE" else "CUSTOM"),
            languageMode = o.optString("languageMode", "DEVICE"),
            webrtcMode = o.optString("webrtcMode", if (o.optBoolean("webrtcBlock", true)) "DISABLED" else "REAL_IP"),
            mediaDevicesNoise = o.optBoolean("mediaDevicesNoise", false),
            clientRectsNoise = o.optBoolean("clientRectsNoise", false),
            speechSynthesisNoise = o.optBoolean("speechSynthesisNoise", false),
            dntMode = o.optString("dntMode", "DEFAULT"),
            hardwareAcceleration = o.optBoolean("hardwareAcceleration", true),
            blockImages = o.optBoolean("blockImages", false),
            seed = o.optInt("seed", 0),
            createdAt = o.optLong("createdAt", 0L),
            lastUsedAt = o.optLong("lastUsedAt", 0L)
        )
    }
}
