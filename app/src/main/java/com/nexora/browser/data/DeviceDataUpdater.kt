package com.nexora.browser.data

import android.content.Context
import com.nexora.browser.NexoraApp
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * In-app auto-update for the device database (devices.json, schema v2).
 *
 * The canonical file lives in the NEXORA GitHub repo; the weekly refresh
 * workflow keeps it current there. The app fetches that same file, validates
 * it, and swaps it in atomically. The bundled assets copy is the offline
 * fallback, and the previous downloaded copy is kept for rollback.
 * Existing profiles are never touched: they keep the UA they were made with.
 */
object DeviceDataUpdater {

    const val DATA_URL =
        "https://raw.githubusercontent.com/parkerlovealex/NEXORA/main/app/src/main/assets/devices.json"

    private const val FILE_CURRENT = "devices-updated.json"
    private const val FILE_PREVIOUS = "devices-previous.json"
    private const val FILE_TMP = "devices-download.tmp"
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    enum class Result { UPDATED, ALREADY_CURRENT, OFFLINE_OR_ERROR, INVALID_DATA }

    fun updatedFile(context: Context): File = File(context.filesDir, FILE_CURRENT)

    /** Raw text of the active database: downloaded copy when present, else null (use assets). */
    fun activeDataText(context: Context): String? {
        val f = updatedFile(context)
        if (!f.exists()) return null
        return try {
            f.readText().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Called from Application.onCreate; checks at most once per day, off the main thread. */
    fun maybeAutoCheck(context: Context) {
        val prefs = context.getSharedPreferences(NexoraApp.PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(NexoraApp.KEY_DATA_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        thread(name = "device-data-autocheck") { checkNow(context.applicationContext) }
    }

    /** Synchronous check; call from a background thread. */
    fun checkNow(context: Context): Result {
        val prefs = context.getSharedPreferences(NexoraApp.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(NexoraApp.KEY_DATA_LAST_CHECK, System.currentTimeMillis()).apply()

        val text = try {
            download(DATA_URL)
        } catch (e: Exception) {
            return Result.OFFLINE_OR_ERROR
        } ?: return Result.OFFLINE_OR_ERROR

        val newVersion = validateAndVersion(text) ?: return Result.INVALID_DATA
        val currentVersion = DeviceDb.dataVersion(context)
        if (compareVersions(newVersion, currentVersion) <= 0) return Result.ALREADY_CURRENT

        // Atomic swap: write tmp, keep current as previous, rename tmp into place.
        val tmp = File(context.filesDir, FILE_TMP)
        val current = updatedFile(context)
        val previous = File(context.filesDir, FILE_PREVIOUS)
        try {
            tmp.writeText(text)
            if (current.exists()) current.copyTo(previous, overwrite = true)
            if (!tmp.renameTo(current)) {
                tmp.copyTo(current, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            return Result.OFFLINE_OR_ERROR
        }
        DeviceDb.invalidateCache()
        return Result.UPDATED
    }

    /** Restore the previous downloaded database, if one exists. */
    fun rollback(context: Context): Boolean {
        val previous = File(context.filesDir, FILE_PREVIOUS)
        if (!previous.exists()) return false
        return try {
            previous.copyTo(updatedFile(context), overwrite = true)
            DeviceDb.invalidateCache()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun download(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "NEXORA-DataUpdater")
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Returns the dataVersion when the payload passes the in-app coherence checks, else null. */
    internal fun validateAndVersion(text: String): String? {
        return try {
            val root = JSONObject(text)
            val meta = root.optJSONObject("meta") ?: return null
            if (meta.optInt("schemaVersion") != 2) return null
            val version = meta.optString("dataVersion")
            if (!version.matches(Regex("""\d{4}\.\d{2}\.\d{2}\.\d+"""))) return null
            val chrome = meta.optJSONObject("versions")?.optString("chrome").orEmpty()
            if (!chrome.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) return null
            val android = root.optJSONArray("android") ?: return null
            if (android.length() == 0) return null
            for (i in 0 until android.length()) {
                val row = android.optJSONObject(i) ?: return null
                if (row.optString("id").isBlank() || row.optString("uaModel").isBlank()) return null
                val disp = row.optJSONObject("display") ?: return null
                if (disp.optInt("w") <= 0 || disp.optInt("h") <= 0) return null
                val osVersions = row.optJSONArray("osVersions") ?: return null
                if (osVersions.length() == 0) return null
                val builds = row.optJSONObject("builds")
                if (builds != null) {
                    val keys = builds.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        var known = false
                        for (j in 0 until osVersions.length()) {
                            if (osVersions.optString(j) == k) { known = true; break }
                        }
                        if (!known) return null
                    }
                }
            }
            version
        } catch (e: Exception) {
            null
        }
    }

    /** Numeric compare of YYYY.MM.DD.N versions; non-matching input sorts lowest. */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: -1 }
        val pb = b.split('.').map { it.toIntOrNull() ?: -1 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
