package com.nexora.browser.data

import org.json.JSONArray
import org.json.JSONObject

/** One generated User-Agent record. The UA is a template; metadata is not encoded in the string. */
data class GeneratedUserAgent(
    val id: String,
    val targetOs: String,
    val deviceId: String,
    val deviceName: String,
    val osVersion: String,
    val browserFamily: String,
    val inAppBrowserId: String = "",
    val inAppBrowserName: String = "",
    val locale: String,
    val userAgent: String,
    val databaseVersion: String,
    val runtimeNote: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("targetOs", targetOs)
        .put("deviceId", deviceId)
        .put("deviceName", deviceName)
        .put("osVersion", osVersion)
        .put("browserFamily", browserFamily)
        .put("inAppBrowserId", inAppBrowserId)
        .put("inAppBrowserName", inAppBrowserName)
        .put("locale", locale)
        .put("userAgent", userAgent)
        .put("databaseVersion", databaseVersion)
        .put("runtimeNote", runtimeNote)

    companion object {
        fun fromJson(o: JSONObject) = GeneratedUserAgent(
            id = o.optString("id"),
            targetOs = o.optString("targetOs"),
            deviceId = o.optString("deviceId"),
            deviceName = o.optString("deviceName"),
            osVersion = o.optString("osVersion"),
            browserFamily = o.optString("browserFamily"),
            inAppBrowserId = o.optString("inAppBrowserId"),
            inAppBrowserName = o.optString("inAppBrowserName"),
            locale = o.optString("locale", "en-US"),
            userAgent = o.optString("userAgent"),
            databaseVersion = o.optString("databaseVersion"),
            runtimeNote = o.optString("runtimeNote")
        )
    }
}

data class UserAgentBatch(
    val id: String,
    val name: String,
    val createdAt: Long,
    val databaseVersion: String,
    val records: List<GeneratedUserAgent>
) {
    fun toJson(): JSONObject {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("createdAt", createdAt)
            .put("databaseVersion", databaseVersion)
            .put("records", array)
    }

    companion object {
        fun fromJson(o: JSONObject): UserAgentBatch {
            val array = o.optJSONArray("records") ?: JSONArray()
            val records = buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(GeneratedUserAgent.fromJson(it)) }
                }
            }
            return UserAgentBatch(
                id = o.optString("id"),
                name = o.optString("name", "User-Agent batch"),
                createdAt = o.optLong("createdAt"),
                databaseVersion = o.optString("databaseVersion"),
                records = records
            )
        }
    }
}
