package com.nexora.browser.data

import android.content.Context
import org.json.JSONArray

/** Local library of user-imported User-Agent strings. */
class UserAgentLibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexora_user_agents_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun loadAll(): List<String> = try {
        val array = JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun import(values: List<String>): Pair<Int, Int> {
        val entries = loadAll().toMutableList()
        var added = 0
        var duplicates = 0
        values.forEach { value ->
            if (entries.any { it.equals(value, true) }) duplicates++
            else if (entries.size < MAX_ENTRIES) { entries.add(value); added++ }
        }
        val array = JSONArray()
        entries.forEach { array.put(it) }
        if (!prefs.edit().putString(KEY_ENTRIES, array.toString()).commit()) return 0 to duplicates
        return added to duplicates
    }

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 500
    }
}
