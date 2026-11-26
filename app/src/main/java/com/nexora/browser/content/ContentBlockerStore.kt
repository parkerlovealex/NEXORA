package com.nexora.browser.content

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest

data class ContentFilterList(
    val id: String,
    val name: String,
    val rawRules: String,
    val enabled: Boolean,
    val updatedAt: Long
)

/**
 * Per-NEXORA-profile content-filter settings and locally imported filter lists.
 * Imported lists are stored as local snapshots; remote auto-refresh is not
 * implemented in this milestone.
 */
class ContentBlockerStore(context: Context, profileId: String) {
    private val prefs = context.getSharedPreferences(
        preferenceName(profileId),
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @Synchronized
    fun isBuiltInEnabled(): Boolean = prefs.getBoolean(KEY_BUILT_IN_ENABLED, true)

    @Synchronized
    fun setBuiltInEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUILT_IN_ENABLED, enabled).apply()
    }

    @Synchronized
    fun allowedSites(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_SITES, emptySet())?.toSet().orEmpty()

    @Synchronized
    fun isSiteAllowed(host: String): Boolean {
        val normalized = ContentBlockerEngine.normalizeHost(host) ?: return false
        return allowedSites().any { allowed ->
            normalized == allowed || normalized.endsWith(".$allowed")
        }
    }

    @Synchronized
    fun setSiteAllowed(host: String, allowed: Boolean): Boolean {
        val normalized = ContentBlockerEngine.normalizeHost(host) ?: return false
        val sites = allowedSites().toMutableSet()
        if (allowed) sites.add(normalized) else sites.remove(normalized)
        prefs.edit().putStringSet(KEY_ALLOWED_SITES, sites).apply()
        return true
    }

    @Synchronized
    fun customLists(): List<ContentFilterList> {
        return try {
            val array = JSONArray(prefs.getString(KEY_CUSTOM_LISTS, "[]") ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name").trim()
                    val rules = item.optString("rawRules")
                    if (id.isNotBlank() && name.isNotBlank() && rules.isNotBlank()) {
                        add(
                            ContentFilterList(
                                id = id,
                                name = name.take(MAX_NAME_LENGTH),
                                rawRules = rules.take(MAX_LIST_LENGTH),
                                enabled = item.optBoolean("enabled", true),
                                updatedAt = item.optLong("updatedAt", 0L)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addCustomList(name: String, rawRules: String): String? {
        val safeName = name.trim().take(MAX_NAME_LENGTH)
        val safeRules = rawRules.trim()
        if (safeName.isBlank() || safeRules.length > MAX_LIST_LENGTH) return null
        val existing = customLists()
        if (existing.size >= MAX_CUSTOM_LISTS) return null
        if (existing.any { it.name.equals(safeName, ignoreCase = true) }) return null
        if (existing.sumOf { it.rawRules.length } + safeRules.length > MAX_TOTAL_LIST_LENGTH) return null
        if (ContentBlockerEngine.parseRules(safeRules.lines()).supportedLines == 0) return null

        val id = UUID.randomUUID().toString()
        writeCustomLists(
            customLists() + ContentFilterList(
                id = id,
                name = safeName,
                rawRules = safeRules,
                enabled = true,
                updatedAt = System.currentTimeMillis()
            )
        )
        return id
    }

    @Synchronized
    fun updateCustomList(id: String, name: String, rawRules: String): Boolean {
        val safeName = name.trim().take(MAX_NAME_LENGTH)
        val safeRules = rawRules.trim()
        if (safeName.isBlank() || safeRules.length > MAX_LIST_LENGTH ||
            ContentBlockerEngine.parseRules(safeRules.lines()).supportedLines == 0
        ) return false

        val lists = customLists()
        val previous = lists.firstOrNull { it.id == id } ?: return false
        if (lists.any { it.id != id && it.name.equals(safeName, ignoreCase = true) }) return false
        if (lists.sumOf { it.rawRules.length } - previous.rawRules.length + safeRules.length > MAX_TOTAL_LIST_LENGTH) return false

        writeCustomLists(
            lists.map {
                if (it.id == id) it.copy(
                    name = safeName,
                    rawRules = safeRules,
                    updatedAt = System.currentTimeMillis()
                ) else it
            }
        )
        return true
    }

    @Synchronized
    fun setCustomListEnabled(id: String, enabled: Boolean): Boolean {
        val lists = customLists()
        if (lists.none { it.id == id }) return false
        writeCustomLists(lists.map { if (it.id == id) it.copy(enabled = enabled) else it })
        return true
    }

    @Synchronized
    fun removeCustomList(id: String): Boolean {
        val lists = customLists()
        if (lists.none { it.id == id }) return false
        writeCustomLists(lists.filterNot { it.id == id })
        return true
    }

    @Synchronized
    fun snapshot(): ContentBlockerSnapshot {
        val rules = buildList {
            if (isBuiltInEnabled()) addAll(NEXORA_STARTER_RULES.lines())
            customLists().filter { it.enabled }.forEach { addAll(it.rawRules.lines()) }
        }
        return ContentBlockerSnapshot(
            enabled = isEnabled(),
            allowedSites = allowedSites(),
            rawRules = rules
        )
    }

    private fun writeCustomLists(lists: List<ContentFilterList>) {
        val array = JSONArray()
        lists.take(MAX_CUSTOM_LISTS).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("rawRules", item.rawRules)
                    .put("enabled", item.enabled)
                    .put("updatedAt", item.updatedAt)
            )
        }
        prefs.edit().putString(KEY_CUSTOM_LISTS, array.toString()).apply()
    }

    companion object {
        const val STARTER_LIST_NAME = "NEXORA Starter Ads"

        // A deliberately small starter set, not a full EasyList replacement.
        private val NEXORA_STARTER_RULES = """
            ! NEXORA Starter Ads — curated host rules
            ||doubleclick.net^
            ||googlesyndication.com^
            ||googleadservices.com^
            ||adservice.google.com^
            ||adnxs.com^
            ||adsrvr.org^
            ||adform.net^
            ||taboola.com^
            ||outbrain.com^
            ||criteo.com^
            ||rubiconproject.com^
            ||pubmatic.com^
            ||openx.net^
            ||casalemedia.com^
            ||sharethrough.com^
            ||smartadserver.com^
            ||media.net^
            ||amazon-adsystem.com^
            ||scorecardresearch.com^
            ||quantserve.com^
            ||serving-sys.com^
            ||adroll.com^
            ||lijit.com^
            ||indexww.com^
        """.trimIndent()

        private const val KEY_ENABLED = "enabled"
        private const val KEY_BUILT_IN_ENABLED = "built_in_enabled"
        private const val KEY_ALLOWED_SITES = "allowed_sites"
        private const val KEY_CUSTOM_LISTS = "custom_lists"
        private const val MAX_CUSTOM_LISTS = 20
        private const val MAX_NAME_LENGTH = 48
        private const val MAX_LIST_LENGTH = 300_000
        private const val MAX_TOTAL_LIST_LENGTH = 512_000

        private fun preferenceName(profileId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(profileId.toByteArray(Charsets.UTF_8))
            val suffix = digest.take(12).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "nexora_content_filter_$suffix"
        }
    }
}
