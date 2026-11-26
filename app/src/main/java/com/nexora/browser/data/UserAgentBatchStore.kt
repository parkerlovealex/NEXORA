package com.nexora.browser.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/** Local-only reusable batches. No generated UA or profile data is uploaded. */
class UserAgentBatchStore(context: Context) {
    private val atomicFile = AtomicFile(File(context.filesDir, "user-agent-batches.json"))

    @Synchronized
    fun loadAll(): List<UserAgentBatch> {
        return try {
            if (!atomicFile.baseFile.exists()) emptyList()
            else {
                val array = atomicFile.openRead().bufferedReader().use { JSONArray(it.readText()) }
                buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val batch = UserAgentBatch.fromJson(obj)
                        if (batch.id.isNotBlank() && batch.records.isNotEmpty()) add(batch)
                    }
                }.sortedByDescending { it.createdAt }
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun find(id: String): UserAgentBatch? = loadAll().firstOrNull { it.id == id }

    @Synchronized
    fun save(batch: UserAgentBatch): Boolean {
        if (batch.id.isBlank() || batch.records.isEmpty() || batch.records.size > 500) return false
        val batches = loadAll().filterNot { it.id == batch.id }.toMutableList()
        batches.add(0, batch)
        val array = JSONArray()
        batches.take(MAX_BATCHES).forEach { array.put(it.toJson()) }
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) atomicFile.failWrite(stream)
            false
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val array = JSONArray()
        loadAll().filterNot { it.id == id }.forEach { array.put(it.toJson()) }
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) atomicFile.failWrite(stream)
            false
        }
    }

    companion object { private const val MAX_BATCHES = 20 }
}
