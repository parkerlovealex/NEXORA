package com.nexora.browser.data

import android.content.Context
import com.nexora.browser.NexoraApp
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread

/**
 * Optional sync client for the public NEXORA Next API.
 *
 * The bundled catalog always remains available offline. A server snapshot is
 * activated only after schema, required fields, duplicate IDs and build/OS
 * coherence checks pass. The previous snapshot is retained for rollback.
 */
object DeviceDataUpdater {

    private const val FILE_CURRENT = "devices-updated.json"
    private const val FILE_PREVIOUS = "devices-previous.json"
    private const val FILE_TMP = "devices-download.tmp"
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    enum class Result {
        UPDATED, ALREADY_CURRENT, NOT_CONFIGURED, OFFLINE_OR_ERROR, INVALID_DATA
    }

    fun updatedFile(context: Context): File = File(context.filesDir, FILE_CURRENT)

    fun hasPrevious(context: Context): Boolean =
        File(context.filesDir, FILE_PREVIOUS).let { it.exists() && it.length() > 0L }

    fun activeDataText(context: Context): String? {
        val file = updatedFile(context)
        val downloaded = try {
            if (file.exists() && file.length() > 0L) file.readText().takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        } ?: return null

        // A newly installed app may bundle a newer catalog than the last
        // downloaded snapshot. Never let an old snapshot shadow newer assets.
        val downloadedVersion = validateAndVersion(downloaded) ?: return null
        val bundledVersion = try {
            val bundled = context.assets.open("devices.json").bufferedReader().use { it.readText() }
            validateAndVersion(bundled)
        } catch (_: Exception) {
            null
        }
        if (bundledVersion != null && compareVersions(downloadedVersion, bundledVersion) < 0) return null
        return downloaded
    }

    /** Called on app start; only checks when an administrator has configured an HTTPS API origin. */
    fun maybeAutoCheck(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(NexoraApp.PREFS_NAME, Context.MODE_PRIVATE)
        val apiBase = prefs.getString(NexoraApp.KEY_CATALOG_API_BASE, "").orEmpty().trim()
        if (apiBase.isBlank()) return
        val last = prefs.getLong(NexoraApp.KEY_DATA_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        thread(name = "nexora-catalog-autocheck") {
            checkNow(appContext, apiBase)
        }
    }

    /** Synchronous network operation. Call only from a background thread. */
    fun checkNow(context: Context, apiBase: String): Result {
        val normalizedBase = apiBase.trim().trimEnd('/')
        if (normalizedBase.isBlank()) return Result.NOT_CONFIGURED

        val prefs = context.getSharedPreferences(NexoraApp.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(NexoraApp.KEY_CATALOG_API_BASE, normalizedBase).apply()

        val result = performCheck(context, normalizedBase)
        // A35: only a completed check consumes the auto-check interval. A
        // failed fetch must not delay the next automatic retry by 24 hours.
        if (result != Result.OFFLINE_OR_ERROR) {
            prefs.edit().putLong(NexoraApp.KEY_DATA_LAST_CHECK, System.currentTimeMillis()).apply()
        }
        return result
    }

    private fun performCheck(context: Context, normalizedBase: String): Result {
        val active = activeDataText(context) ?: DeviceDb.readBundledText(context) ?: return Result.INVALID_DATA
        val (result, payload) = fetchUpdate(normalizedBase, active, DeviceDb.readBundledText(context, "ua_templates.json"), ::download)
        if (payload == null) return result

        val temp = File(context.filesDir, FILE_TMP)
        val current = updatedFile(context)
        val previous = File(context.filesDir, FILE_PREVIOUS)
        return try {
            temp.writeText(payload)
            if (current.exists()) {
                current.copyTo(previous, overwrite = true)
                current.delete()
            }
            if (!temp.renameTo(current)) {
                temp.copyTo(current, overwrite = true)
                temp.delete()
            }
            DeviceDb.invalidateCache()
            Result.UPDATED
        } catch (_: Exception) {
            temp.delete()
            // If activation failed after moving the current snapshot, restore it.
            if (!current.exists() && previous.exists()) {
                try { previous.copyTo(current, overwrite = true) } catch (_: Exception) { }
            }
            Result.OFFLINE_OR_ERROR
        }
    }

    fun rollback(context: Context): Boolean {
        val previous = File(context.filesDir, FILE_PREVIOUS)
        if (!previous.exists() || previous.length() == 0L) return false
        return try {
            val current = updatedFile(context)
            val swap = File(context.filesDir, FILE_TMP)
            if (current.exists()) current.copyTo(swap, overwrite = true)
            previous.copyTo(current, overwrite = true)
            if (swap.exists()) {
                swap.copyTo(previous, overwrite = true)
                swap.delete()
            }
            DeviceDb.invalidateCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun activeTemplates(devices: String, bundled: String?): String? {
        val saved = runCatching { JSONObject(devices).optJSONObject("publishedUaTemplates")?.toString() }.getOrNull()
        val savedVersion = PublishedUserAgentTemplates.version(saved) ?: return bundled
        val bundledVersion = PublishedUserAgentTemplates.version(bundled) ?: return saved
        return if (compareVersions(savedVersion, bundledVersion) >= 0) saved else bundled
    }

    /** Both catalogs travel in the same snapshot, even when only the template revision advances. */
    internal fun fetchUpdate(
        base: String, active: String, bundledTemplates: String?, fetch: (URL) -> String?
    ): Pair<Result, String?> {
        val devices: String
        val templates: String
        try {
            devices = fetch(catalogUrl(base, "/api/v1/data")) ?: return Result.OFFLINE_OR_ERROR to null
            templates = fetch(catalogUrl(base, "/ua_templates.json")) ?: return Result.OFFLINE_OR_ERROR to null
        } catch (_: Exception) {
            return Result.OFFLINE_OR_ERROR to null
        }
        val remoteVersion = validateAndVersion(devices) ?: return Result.INVALID_DATA to null
        val newTemplateVersion = PublishedUserAgentTemplates.version(templates) ?: return Result.INVALID_DATA to null
        val oldVersion = validateAndVersion(active).orEmpty()
        val oldTemplates = activeTemplates(active, bundledTemplates)
        val oldTemplateVersion = PublishedUserAgentTemplates.version(oldTemplates).orEmpty()
        val newerDevices = compareVersions(remoteVersion, oldVersion) > 0
        val newerTemplates = compareVersions(newTemplateVersion, oldTemplateVersion) > 0
        if (!newerDevices && !newerTemplates) return Result.ALREADY_CURRENT to null

        val deviceText = if (newerDevices) devices else active
        val selected = JSONObject(if (newerTemplates) templates else oldTemplates ?: templates)
        val rows = selected.getJSONArray("records")
        val verified = (0 until rows.length()).count { rows.optJSONObject(it)?.optString("status") == "verified" }
        val deviceVersion = if (newerDevices) remoteVersion else oldVersion
        if (compareVersions(selected.getString("deviceCatalogVersion"), deviceVersion) > 0 ||
            PublishedUserAgentTemplates.read(selected.toString(), deviceText).size != verified) return Result.INVALID_DATA to null
        return Result.UPDATED to JSONObject(deviceText).put("publishedUaTemplates", selected).toString()
    }

    private fun catalogUrl(base: String, path: String): URL {
        val uri = URI(base)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
            (uri.path.isNotBlank() && uri.path != "/") || uri.query != null || uri.fragment != null
        ) {
            throw IllegalArgumentException("Enter an HTTPS API origin, for example https://nexora.example.com")
        }
        return URL("${base.trimEnd('/')}$path")
    }

    private fun download(url: URL): String? {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "NEXORA-Android-CatalogSync/1.0")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Catalog response exceeded the 2 MB limit")
                    output.write(buffer, 0, count)
                }
            }
            String(output.toByteArray(), Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** Returns the catalog version only when the complete payload is coherent. */
    internal fun validateAndVersion(text: String): String? {
        return try {
            val root = JSONObject(text)
            val meta = root.optJSONObject("meta") ?: return null
            if (meta.optInt("schemaVersion") != 2) return null
            val version = meta.optString("dataVersion")
            if (!version.matches(Regex("""\d{4}\.\d{2}\.\d{2}\.\d+"""))) return null
            val android = root.optJSONArray("android") ?: return null
            if (android.length() == 0) return null

            val seen = mutableSetOf<String>()
            for (section in listOf("android", "ios_emulation")) {
                val rows = root.optJSONArray(section) ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: return null
                    val id = row.optString("id")
                    if (id.isBlank() || !seen.add(id)) return null
                    for (field in listOf("brand", "model", "uaModel", "soc", "gpu")) {
                        if (row.optString(field).isBlank()) return null
                    }
                    if (row.optInt("cores") <= 0) return null
                    val display = row.optJSONObject("display") ?: return null
                    if (display.optInt("w") <= 0 || display.optInt("h") <= 0 ||
                        display.optDouble("dpr", 0.0) <= 0.0
                    ) return null
                    val ram = row.optJSONArray("ramGb") ?: return null
                    val storage = row.optJSONArray("storageGb") ?: return null
                    val osVersions = row.optJSONArray("osVersions") ?: return null
                    if (ram.length() == 0 || storage.length() == 0 || osVersions.length() == 0) return null
                    for (j in 0 until ram.length()) if (ram.optInt(j) <= 0) return null
                    for (j in 0 until storage.length()) if (storage.optInt(j) <= 0) return null
                    val osSet = (0 until osVersions.length()).map { osVersions.optString(it) }.toSet()
                    if (osSet.any { it.isBlank() }) return null
                    val builds = row.optJSONObject("builds")
                    if (builds != null) {
                        val keys = builds.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key !in osSet || !builds.optString(key).matches(Regex("""[A-Za-z0-9._/-]+"""))) return null
                        }
                    }
                }
            }
            version
        } catch (_: Exception) {
            null
        }
    }

    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: -1 }
        val right = b.split('.').map { it.toIntOrNull() ?: -1 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
