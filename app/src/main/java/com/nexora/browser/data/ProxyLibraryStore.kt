package com.nexora.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.nexora.browser.security.SecretVault
import com.nexora.browser.util.ParsedProxy
import com.nexora.browser.util.ProxyImportIdentity
import java.util.UUID

data class ProxyBackupRecord(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val createdAt: Long
)

data class ProxyBackupMergeResult(
    val success: Boolean,
    val idMapping: Map<String, String>,
    val added: Int
)

/** Local proxy library. User/password pairs are encrypted with an Android Keystore AES key. */
class ProxyLibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexora_proxy_library_v1", Context.MODE_PRIVATE)
    private val vault = SecretVault()

    internal fun <T> withLock(action: () -> T): T = synchronized(prefs, action)

    fun loadAll(): List<ProxyEntry> = withLock {
        try {
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

    /**
     * A39: every submitted valid row is accounted for — added,
     * duplicate, or skipped with its reason — so an import summary can
     * reconcile against the number of rows the user submitted.
     */
    data class ImportResult(
        val added: Int,
        val duplicates: Int,
        val skippedCapacity: Int,
        val skippedEncryption: Int
    )

    fun import(rows: List<ParsedProxy>): ImportResult = withLock {
        val entries = loadAll().toMutableList()
        var added = 0
        var duplicates = 0
        var skippedCapacity = 0
        var skippedEncryption = 0
        rows.filter { it.valid }.forEach { row ->
            if (entries.size >= MAX_ENTRIES) {
                skippedCapacity++
                return@forEach
            }
            val duplicate = entries.any { existing ->
                if (!ProxyImportIdentity.sameEndpoint(
                        existing.type, existing.host, existing.port,
                        row.type, row.host, row.port
                    )
                ) {
                    false
                } else {
                    val savedCredentials = if (existing.hasCredentials) credentials(existing) else "" to ""
                    ProxyImportIdentity.sameCredentials(
                        existing.hasCredentials,
                        savedCredentials.first,
                        savedCredentials.second,
                        row.hasCredentials,
                        row.username,
                        row.password
                    )
                }
            }
            if (duplicate) {
                duplicates++
            } else {
                val secret = if (row.hasCredentials) {
                    try {
                        vault.encrypt(JSONObject().put("username", row.username).put("password", row.password).toString())
                    } catch (_: Exception) {
                        skippedEncryption++
                        return@forEach
                    }
                } else ""
                val endpointOrdinal = entries.count {
                    ProxyImportIdentity.sameEndpoint(
                        it.type, it.host, it.port,
                        row.type, row.host, row.port
                    )
                } + 1
                val endpointName = "${row.type} ${row.host}:${row.port}"
                entries.add(
                    ProxyEntry(
                        id = UUID.randomUUID().toString(),
                        name = if (endpointOrdinal == 1) endpointName else "$endpointName · #$endpointOrdinal",
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
        if (!save(entries)) return@withLock ImportResult(0, duplicates, skippedCapacity, skippedEncryption)
        ImportResult(added, duplicates, skippedCapacity, skippedEncryption)
    }

    fun credentials(entry: ProxyEntry): Pair<String, String> {
        if (!entry.hasCredentials || entry.encryptedCredentials.isBlank()) return "" to ""
        return try {
            val obj = JSONObject(vault.decrypt(entry.encryptedCredentials))
            obj.optString("username") to obj.optString("password")
        } catch (_: Exception) {
            "" to ""
        }
    }

    fun rename(id: String, newName: String): Boolean = withLock {
        val cleanName = newName.trim()
        if (cleanName.isBlank() || cleanName.length > 60) return@withLock false
        val entries = loadAll().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false
        entries[index] = entries[index].copy(name = cleanName)
        save(entries)
    }

    fun delete(id: String): Boolean = deleteMany(setOf(id))

    /** Atomically removes a set of library entries in one preferences commit. */
    fun deleteMany(ids: Set<String>): Boolean = withLock {
        if (ids.isEmpty()) return@withLock true
        save(loadAll().filterNot { it.id in ids })
    }

    /**
     * Merge portable backup entries and re-encrypt credentials with this install's Keystore key.
     * Existing matching endpoints/credentials are reused; ID collisions are remapped safely.
     */
    fun mergePortable(records: List<ProxyBackupRecord>): ProxyBackupMergeResult = withLock {
        val entries = loadAll().toMutableList()
        val mapping = linkedMapOf<String, String>()
        var added = 0
        records.forEach { record ->
            val sameCredentials: (ProxyEntry) -> Boolean = { existing ->
                val saved = credentials(existing)
                val readable = !existing.hasCredentials || saved.first.isNotEmpty() || saved.second.isNotEmpty()
                readable && saved.first == record.username && saved.second == record.password
            }
            val exactId = entries.firstOrNull { it.id == record.id }
            val idMatch = exactId?.takeIf {
                it.type == record.type && it.host.equals(record.host, true) &&
                    it.port == record.port && sameCredentials(it)
            }
            val identityMatch = idMatch ?: entries.firstOrNull { existing ->
                existing.type == record.type && existing.host.equals(record.host, true) &&
                    existing.port == record.port && sameCredentials(existing)
            }
            if (identityMatch != null) {
                mapping[record.id] = identityMatch.id
                return@forEach
            }
            if (entries.size >= MAX_ENTRIES) return@withLock ProxyBackupMergeResult(false, emptyMap(), 0)
            val encrypted = if (record.username.isNotEmpty() || record.password.isNotEmpty()) {
                try {
                    vault.encrypt(
                        org.json.JSONObject()
                            .put("username", record.username)
                            .put("password", record.password)
                            .toString()
                    )
                } catch (_: Exception) {
                    return@withLock ProxyBackupMergeResult(false, emptyMap(), 0)
                }
            } else ""
            val newId = if (entries.any { it.id == record.id }) UUID.randomUUID().toString() else record.id
            entries += ProxyEntry(
                id = newId,
                name = record.name.trim().take(60),
                type = record.type,
                host = record.host,
                port = record.port,
                hasCredentials = record.username.isNotEmpty() || record.password.isNotEmpty(),
                encryptedCredentials = encrypted,
                createdAt = record.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            mapping[record.id] = newId
            added++
        }
        if (!save(entries)) return@withLock ProxyBackupMergeResult(false, emptyMap(), 0)
        ProxyBackupMergeResult(true, mapping, added)
    }

    fun save(entries: List<ProxyEntry>): Boolean = withLock {
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
        prefs.edit().putString(KEY_ENTRIES, array.toString()).commit()
    }

    fun find(id: String?): ProxyEntry? = if (id.isNullOrBlank()) null else loadAll().firstOrNull { it.id == id }

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 500
    }
}
