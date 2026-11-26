package com.nexora.browser.automation

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream

/** Local, credential-free audit trail for user-confirmed workflow runs. */
data class WorkflowAuditRecord(
    val timestamp: Long,
    val workflowId: String,
    val status: String,
    val affectedCount: Int,
    val summary: String
)

class WorkflowAuditStore(context: Context) {
    private val file = AtomicFile(java.io.File(context.filesDir, "workflow_audit.json"))

    @Synchronized
    fun load(): List<WorkflowAuditRecord> {
        if (!file.baseFile.exists()) return emptyList()
        return try {
            val array = file.openRead().bufferedReader().use { JSONArray(it.readText()) }
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val workflowId = item.optString("workflowId")
                    if (workflowId.isNotBlank()) {
                        add(
                            WorkflowAuditRecord(
                                timestamp = item.optLong("timestamp", 0L),
                                workflowId = workflowId,
                                status = item.optString("status", "UNKNOWN"),
                                affectedCount = item.optInt("affectedCount", 0).coerceAtLeast(0),
                                summary = item.optString("summary").take(240)
                            )
                        )
                    }
                }
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun append(record: WorkflowAuditRecord): Boolean {
        val array = JSONArray()
        (listOf(record) + load()).distinctBy {
            "${it.timestamp}:${it.workflowId}:${it.status}"
        }.take(MAX_RECORDS).forEach {
            array.put(
                JSONObject()
                    .put("timestamp", it.timestamp)
                    .put("workflowId", it.workflowId)
                    .put("status", it.status)
                    .put("affectedCount", it.affectedCount)
                    .put("summary", it.summary.take(240))
            )
        }
        var stream: FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
            false
        }
    }

    companion object {
        private const val MAX_RECORDS = 100
    }
}
