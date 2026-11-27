package com.nexora.browser.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Local-only reusable batches. No generated UA or profile data is uploaded. */
class UserAgentBatchStore internal constructor(private val file: LocalStoreFile) {
    constructor(context: Context) : this(LocalStoreFile.android(File(context.filesDir, "user-agent-batches.json")))

    @Synchronized
    fun loadAll(): List<UserAgentBatch> = file.readRows { obj ->
        require((obj.opt("id") as? String)?.isNotBlank() == true) { "Missing batch ID" }
        val records = obj.getJSONArray("records")
        require(records.length() > 0) { "Empty saved batch" }
        for (i in 0 until records.length()) records.getJSONObject(i)
        UserAgentBatch.fromJson(obj)
    }.sortedByDescending { it.createdAt }

    @Synchronized
    fun find(id: String): UserAgentBatch? = loadAll().firstOrNull { it.id == id }

    private fun edit(change: (List<UserAgentBatch>) -> List<UserAgentBatch>): Boolean {
        val batches = try {
            loadAll()
        } catch (_: LocalStoreReadException) {
            return false
        }
        val rows = JSONArray()
        change(batches).forEach { rows.put(it.toJson()) }
        return file.write(rows)
    }

    @Synchronized
    fun save(batch: UserAgentBatch): Boolean {
        if (batch.id.isBlank() || batch.records.isEmpty() || batch.records.size > 500) return false
        return edit { batches -> (listOf(batch) + batches.filterNot { it.id == batch.id }).take(MAX_BATCHES) }
    }

    @Synchronized
    fun delete(id: String): Boolean = edit { batches -> batches.filterNot { it.id == id } }

    companion object { private const val MAX_BATCHES = 20 }
}
