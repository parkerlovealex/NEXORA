package com.nexora.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BrowserPageRecord(
    val url: String,
    val title: String,
    val timestamp: Long
)

data class BrowserDownloadRecord(
    val id: Long,
    val fileName: String,
    val timestamp: Long
)

data class BrowserTabGroup(
    val id: String,
    val name: String,
    val createdAt: Long
)

data class BrowserSessionTab(
    val url: String,
    val title: String,
    val desktopMode: Boolean,
    val groupId: String?
)

data class BrowserSessionSnapshot(
    val tabs: List<BrowserSessionTab>,
    val activeIndex: Int
)

/**
 * Per-profile browser metadata. Web content storage itself remains in the
 * AndroidX WebKit profile data store; this class stores UI metadata only.
 */
class BrowserDataStore(context: Context, profileId: String) {
    private val prefs = context.getSharedPreferences(
        "nexora_browser_${profileId.hashCode()}",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun tabGroups(): List<BrowserTabGroup> {
        return try {
            val array = JSONArray(prefs.getString(KEY_TAB_GROUPS, "[]") ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name").trim()
                    if (id.isNotBlank() && name.isNotBlank()) {
                        add(BrowserTabGroup(id, name, item.optLong("createdAt", 0L)))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addTabGroup(rawName: String): BrowserTabGroup? {
        val name = rawName.trim().take(MAX_GROUP_NAME)
        val existing = tabGroups()
        if (name.isBlank() || existing.size >= MAX_TAB_GROUPS ||
            existing.any { it.name.equals(name, ignoreCase = true) }
        ) return null
        val group = BrowserTabGroup(java.util.UUID.randomUUID().toString(), name, System.currentTimeMillis())
        writeTabGroups(tabGroups() + group)
        return group
    }

    @Synchronized
    fun renameTabGroup(id: String, rawName: String): Boolean {
        val name = rawName.trim().take(MAX_GROUP_NAME)
        if (name.isBlank()) return false
        val groups = tabGroups()
        if (groups.any { it.id != id && it.name.equals(name, ignoreCase = true) }) return false
        if (groups.none { it.id == id }) return false
        writeTabGroups(groups.map { if (it.id == id) it.copy(name = name) else it })
        return true
    }

    @Synchronized
    fun removeTabGroup(id: String): Boolean {
        val groups = tabGroups()
        if (groups.none { it.id == id }) return false
        writeTabGroups(groups.filterNot { it.id == id })
        val snapshot = sessionSnapshot()
        if (snapshot.tabs.isNotEmpty()) {
            saveSession(
                snapshot.tabs.map { if (it.groupId == id) it.copy(groupId = null) else it },
                snapshot.activeIndex
            )
        }
        return true
    }

    @Synchronized
    fun saveSession(tabs: List<BrowserSessionTab>, activeIndex: Int) {
        val array = JSONArray()
        tabs.take(MAX_SESSION_TABS).forEach { tab ->
            val url = tab.url.take(MAX_SESSION_URL_LENGTH)
            if (url.isBlank()) return@forEach
            val item = JSONObject()
                .put("url", url)
                .put("title", tab.title.take(MAX_SESSION_TITLE_LENGTH))
                .put("desktopMode", tab.desktopMode)
            if (!tab.groupId.isNullOrBlank() && tabGroups().any { it.id == tab.groupId }) {
                item.put("groupId", tab.groupId)
            }
            array.put(item)
        }
        val safeIndex = activeIndex.coerceIn(0, (array.length() - 1).coerceAtLeast(0))
        prefs.edit()
            .putString(KEY_SESSION, array.toString())
            .putInt(KEY_SESSION_ACTIVE, safeIndex)
            .apply()
    }

    @Synchronized
    fun sessionSnapshot(): BrowserSessionSnapshot {
        val tabs = try {
            val array = JSONArray(prefs.getString(KEY_SESSION, "[]") ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        add(
                            BrowserSessionTab(
                                url = url,
                                title = item.optString("title", "New tab"),
                                desktopMode = item.optBoolean("desktopMode", false),
                                groupId = item.optString("groupId").takeIf { value ->
                                    value.isNotBlank() && tabGroups().any { it.id == value }
                                }
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        val limitedTabs = tabs.take(MAX_SESSION_TABS)
        val active = prefs.getInt(KEY_SESSION_ACTIVE, 0)
            .coerceIn(0, (limitedTabs.size - 1).coerceAtLeast(0))
        return BrowserSessionSnapshot(limitedTabs, active)
    }

    @Synchronized
    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_SESSION_ACTIVE).apply()
    }

    @Synchronized
    fun textZoom(): Int = prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM).coerceIn(50, 200)

    @Synchronized
    fun setTextZoom(value: Int) {
        prefs.edit().putInt(KEY_TEXT_ZOOM, value.coerceIn(50, 200)).apply()
    }

    private fun writeTabGroups(groups: List<BrowserTabGroup>) {
        val array = JSONArray()
        groups.take(MAX_TAB_GROUPS).forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt))
        }
        prefs.edit().putString(KEY_TAB_GROUPS, array.toString()).apply()
    }

    @Synchronized
    fun history(): List<BrowserPageRecord> = readPages(KEY_HISTORY)

    @Synchronized
    fun addHistory(url: String, title: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val items = readPages(KEY_HISTORY).toMutableList()
        items.removeAll { it.url == url }
        items.add(0, BrowserPageRecord(url, title.ifBlank { url }, System.currentTimeMillis()))
        writePages(KEY_HISTORY, items.take(MAX_HISTORY))
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    @Synchronized
    fun bookmarks(): List<BrowserPageRecord> = readPages(KEY_BOOKMARKS)

    @Synchronized
    fun addBookmark(url: String, title: String) {
        if (url.isBlank()) return
        val items = readPages(KEY_BOOKMARKS).toMutableList()
        items.removeAll { it.url == url }
        items.add(0, BrowserPageRecord(url, title.ifBlank { url }, System.currentTimeMillis()))
        writePages(KEY_BOOKMARKS, items)
    }

    @Synchronized
    fun removeBookmark(url: String) {
        writePages(KEY_BOOKMARKS, readPages(KEY_BOOKMARKS).filterNot { it.url == url })
    }

    @Synchronized
    fun recentlyClosed(): List<BrowserPageRecord> = readPages(KEY_CLOSED)

    @Synchronized
    fun addRecentlyClosed(url: String, title: String) {
        if (url.isBlank()) return
        val items = readPages(KEY_CLOSED).toMutableList()
        items.removeAll { it.url == url }
        items.add(0, BrowserPageRecord(url, title.ifBlank { url }, System.currentTimeMillis()))
        writePages(KEY_CLOSED, items.take(MAX_CLOSED))
    }

    @Synchronized
    fun removeRecentlyClosed(url: String) {
        writePages(KEY_CLOSED, readPages(KEY_CLOSED).filterNot { it.url == url })
    }

    @Synchronized
    fun siteUserAgent(host: String): String? {
        if (host.isBlank()) return null
        return try {
            JSONObject(prefs.getString(KEY_SITE_UA, "{}") ?: "{}")
                .optString(host).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun setSiteUserAgent(host: String, userAgent: String?) {
        if (host.isBlank()) return
        val values = try {
            JSONObject(prefs.getString(KEY_SITE_UA, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        if (userAgent.isNullOrBlank()) values.remove(host) else values.put(host, userAgent)
        prefs.edit().putString(KEY_SITE_UA, values.toString()).apply()
    }

    @Synchronized
    fun isSiteDark(host: String): Boolean {
        if (host.isBlank()) return false
        return prefs.getStringSet(KEY_DARK_HOSTS, emptySet())?.contains(host) == true
    }

    @Synchronized
    fun setSiteDark(host: String, enabled: Boolean) {
        if (host.isBlank()) return
        val hosts = prefs.getStringSet(KEY_DARK_HOSTS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        if (enabled) hosts.add(host) else hosts.remove(host)
        prefs.edit().putStringSet(KEY_DARK_HOSTS, hosts).apply()
    }

    @Synchronized
    fun redirectRules(): String = prefs.getString(KEY_REDIRECT_RULES, "").orEmpty()

    @Synchronized
    fun setRedirectRules(value: String) {
        prefs.edit().putString(KEY_REDIRECT_RULES, value.trim()).apply()
    }

    @Synchronized
    fun addDownload(id: Long, fileName: String) {
        val items = readDownloads().toMutableList()
        items.removeAll { it.id == id }
        items.add(0, BrowserDownloadRecord(id, fileName, System.currentTimeMillis()))
        val array = JSONArray()
        items.take(MAX_DOWNLOADS).forEach {
            array.put(JSONObject().put("id", it.id).put("fileName", it.fileName).put("timestamp", it.timestamp))
        }
        prefs.edit().putString(KEY_DOWNLOADS, array.toString()).apply()
    }

    @Synchronized
    fun downloads(): List<BrowserDownloadRecord> = readDownloads()

    private fun readDownloads(): List<BrowserDownloadRecord> {
        return try {
            val array = JSONArray(prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optLong("id", -1L)
                    if (id >= 0L) add(
                        BrowserDownloadRecord(
                            id,
                            item.optString("fileName", "Download"),
                            item.optLong("timestamp", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readPages(key: String): List<BrowserPageRecord> {
        return try {
            val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        add(
                            BrowserPageRecord(
                                url,
                                item.optString("title", url),
                                item.optLong("timestamp", 0L)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writePages(key: String, pages: List<BrowserPageRecord>) {
        val array = JSONArray()
        pages.forEach {
            array.put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("timestamp", it.timestamp)
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_CLOSED = "recently_closed"
        private const val KEY_SITE_UA = "site_user_agents"
        private const val KEY_DARK_HOSTS = "site_dark_hosts"
        private const val KEY_REDIRECT_RULES = "redirect_rules"
        private const val KEY_DOWNLOADS = "downloads"
        private const val KEY_TAB_GROUPS = "tab_groups"
        private const val KEY_SESSION = "session_tabs"
        private const val KEY_SESSION_ACTIVE = "session_active_index"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val MAX_HISTORY = 300
        private const val MAX_CLOSED = 30
        private const val MAX_DOWNLOADS = 100
        private const val MAX_TAB_GROUPS = 30
        private const val MAX_GROUP_NAME = 32
        private const val MAX_SESSION_TABS = 20
        private const val MAX_SESSION_URL_LENGTH = 4096
        private const val MAX_SESSION_TITLE_LENGTH = 160
        private const val DEFAULT_TEXT_ZOOM = 100
    }
}
