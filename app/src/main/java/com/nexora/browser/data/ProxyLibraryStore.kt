package com.nexora.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.nexora.browser.security.SecretVault
import com.nexora.browser.util.ParsedProxy
import java.util.UUID

/** Local proxy library. User/password pairs are encrypted with an Android Keystore AES key. */
class ProxyLibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexora_proxy_library_v1", Context.MODE_PRIVATE)
    private val vault = SecretVault()

    @Synchronized
    fun loadAll(): List<ProxyEntry> {
        return try {
            val array = JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val entry = ProxyEntry(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        type = item.optString("type").uppercase(),
                        host = item.optString("host"),
                        port = item.optInt("port"),
                        hasCredentials = item.optBoolean("hasCredentials", false),
                        encryptedCredentials = item.optString("encryptedCredentials"),
                        createdAt = item.optLong("createdAt")
                    )
                    if (entry.id.isNotBlank() && entry.isValid) add(entry)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun import(rows: List<ParsedProxy>): Pair<Int, Int> {
        val entries = loadAll().toMutableList()
        var added = 0
        var duplicates = 0
        rows.filter { it.valid }.forEach { row ->
            if (entries.size >= MAX_ENTRIES) return@forEach
            val duplicate = entries.any {
                it.type == row.type && it.host.equals(row.host, true) && it.port == row.port
            }
            if (duplicate) {
                duplicates++
            } else {
                val secret = if (row.hasCredentials) {
                    try {
                        vault.encrypt(JSONObject().put("username", row.username).put("password", row.password).toString())
                    } catch (_: Exception) {
                        return@forEach
                    }
                } else ""
                entries.add(
                    ProxyEntry(
                        id = UUID.randomUUID().toString(),
                        name = "${row.type} ${row.host}:${row.port}",
                        type = row.type,
                        host = row.host,
                        port = row.port,
                        hasCredentials = row.hasCredentials,
                        encryptedCredentials = secret,
                        createdAt = System.currentTimeMillis()
                    )
                )
                added++
            }
        }
        if (!save(entries)) return 0 to duplicates
        return added to duplicates
    }

    @Synchronized
    fun credentials(entry: ProxyEntry): Pair<String, String> {
        if (!entry.hasCredentials || entry.encryptedCredentials.isBlank()) return "" to ""
        return try {
            val obj = JSONObject(vault.decrypt(entry.encryptedCredentials))
            obj.optString("username") to obj.optString("password")
        } catch (_: Exception) {
            "" to ""
        }
    }

    @Synchronized
    fun delete(id: String): Boolean = save(loadAll().filterNot { it.id == id })

    @Synchronized
    fun save(entries: List<ProxyEntry>): Boolean {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("type", entry.type)
                    .put("host", entry.host)
                    .put("port", entry.port)
                    .put("hasCredentials", entry.hasCredentials)
                    .put("encryptedCredentials", entry.encryptedCredentials)
                    .put("createdAt", entry.createdAt)
            )
        }
        return prefs.edit().putString(KEY_ENTRIES, array.toString()).commit()
    }

    fun find(id: String?): ProxyEntry? = if (id.isNullOrBlank()) null else loadAll().firstOrNull { it.id == id }

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 500
    }
}
