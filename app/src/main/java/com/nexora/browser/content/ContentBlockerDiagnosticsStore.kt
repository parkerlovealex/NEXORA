package com.nexora.browser.content

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class BlockedRequestDiagnostic(
    val timestamp: Long,
    val requestHost: String,
    val pageHost: String,
    val ruleHost: String,
    val method: String
)

/**
 * Optional, profile-scoped diagnostics for content-blocker decisions.
 *
 * Privacy boundary: only hostnames, the matched rule hostname, HTTP method and
 * timestamp are persisted. URL paths, query strings, fragments, cookies,
 * headers, bodies and full URLs are deliberately never stored.
 */
class ContentBlockerDiagnosticsStore(context: Context, profileId: String) {
    private val prefs = context.getSharedPreferences(
        preferenceName(profileId),
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @Synchronized
    fun records(): List<BlockedRequestDiagnostic> {
        return try {
            val array = JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val requestHost = item.optString("requestHost")
                    val ruleHost = item.optString("ruleHost")
                    if (ContentBlockerEngine.normalizeHost(requestHost) == null ||
                        ContentBlockerEngine.normalizeHost(ruleHost) == null
                    ) continue

                    val pageHost = ContentBlockerEngine.normalizeHost(item.optString("pageHost")).orEmpty()
                    add(
                        BlockedRequestDiagnostic(
                            timestamp = item.optLong("timestamp", 0L),
                            requestHost = requestHost,
                            pageHost = pageHost,
                            ruleHost = ruleHost,
                            method = safeMethod(item.optString("method"))
                        )
                    )
                }
            }.take(MAX_RECORDS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun recordBlocked(
        resourceUrl: String,
        pageUrl: String?,
        ruleHost: String,
        method: String
    ) {
        if (!isEnabled()) return

        val requestHost = hostFromUrl(resourceUrl) ?: return
        val normalizedRuleHost = ContentBlockerEngine.normalizeHost(ruleHost) ?: return
        val pageHost = hostFromUrl(pageUrl.orEmpty()).orEmpty()
        val record = BlockedRequestDiagnostic(
            timestamp = System.currentTimeMillis(),
            requestHost = requestHost,
            pageHost = pageHost,
            ruleHost = normalizedRuleHost,
            method = safeMethod(method)
        )

        val updated = (listOf(record) + records()).take(MAX_RECORDS)
        val array = JSONArray()
        updated.forEach { item ->
            array.put(
                JSONObject()
                    .put("timestamp", item.timestamp)
                    .put("requestHost", item.requestHost)
                    .put("pageHost", item.pageHost)
                    .put("ruleHost", item.ruleHost)
                    .put("method", item.method)
            )
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_RECORDS).apply()
    }

    private fun hostFromUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val host = runCatching { URI(raw).host }.getOrNull()
        return ContentBlockerEngine.normalizeHost(host)
    }

    private fun safeMethod(raw: String): String {
        val method = raw.trim().uppercase()
        return if (method in ALLOWED_METHODS) method else "OTHER"
    }

    companion object {
        private const val KEY_ENABLED = "diagnostics_enabled"
        private const val KEY_RECORDS = "blocked_request_records"
        private const val MAX_RECORDS = 100
        private val ALLOWED_METHODS = setOf(
            "GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH"
        )

        /** A11: remove a deleted profile's diagnostics namespace. */
        fun clearProfile(context: Context, profileId: String) {
            try {
                context.applicationContext.getSharedPreferences(
                    preferenceName(profileId), Context.MODE_PRIVATE
                ).edit().clear().apply()
            } catch (_: Exception) {
                // Best effort; nothing else owns this namespace.
            }
        }

        private fun preferenceName(profileId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(profileId.toByteArray(Charsets.UTF_8))
            val suffix = digest.take(12).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            return "nexora_content_diagnostics_$suffix"
        }
    }
}
