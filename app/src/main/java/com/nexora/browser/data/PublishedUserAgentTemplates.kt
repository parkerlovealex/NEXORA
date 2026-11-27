package com.nexora.browser.data

import com.google.common.net.InternetDomainName
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.time.LocalDate
import java.util.Locale

/** Reads the public template schema and binds slots only to values in the active device catalog. */
internal object PublishedUserAgentTemplates {
    private val placeholder = Regex("""\{([A-Za-z][A-Za-z0-9_]*)\}""")
    private val revision = Regex("""\d{4}\.\d{2}\.\d{2}\.\d+""")
    private val primary = setOf("observed-ua", "official-doc")
    private val corroboratingKinds = primary + setOf("published-dataset-entry", "technical-reference")

    private fun rows(array: JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    private fun JSONObject.value(key: String): String? = when (val value = opt(key)) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toString()
        else -> null
    }

    fun version(text: String?): String? = runCatching {
        val root = JSONObject(text ?: return null)
        if (root.optInt("uaTemplatesSchema") != 1 || root.optJSONArray("records") == null) return null
        LocalDate.parse(root.getString("updatedAt"))
        if (!revision.matches(root.getString("deviceCatalogVersion"))) return null
        root.getString("dataVersion").takeIf(revision::matches)
    }.getOrNull()

    private data class Source(val id: String, val publisher: String, val kind: String, val evidence: JSONObject)

    private fun publisher(address: String?): String? = runCatching {
        val url = URI(address ?: return null)
        if (url.scheme != "https" || url.rawUserInfo != null || url.host == null) return null
        val domain = InternetDomainName.from(IDN.toASCII(url.host.trimEnd('.')))
        if (domain.isUnderPublicSuffix) domain.topPrivateDomain().toString() else null
    }.getOrNull()

    fun read(text: String?, deviceText: String): List<UserAgentTemplate> = runCatching {
        if (version(text) == null) return emptyList()
        val catalog = JSONObject(text!!)
        val devices = JSONObject(deviceText)
        val meta = devices.getJSONObject("meta")
        if (DeviceDataUpdater.compareVersions(catalog.getString("deviceCatalogVersion"), meta.getString("dataVersion")) > 0) return emptyList()
        val records = rows(catalog.getJSONArray("records"))
        val verified = records.filter { it.value("status") == "verified" }
        val ids = records.groupingBy { it.value("id") }.eachCount()
        val variants = verified.groupingBy { it.value("app") to it.value("platform") }.eachCount()
        val deviceRows = rows(devices.optJSONArray("android")) + rows(devices.optJSONArray("ios_emulation"))
        val deviceMap = deviceRows.associateBy { it.optString("id") }
        verified.mapNotNull { row ->
            if (ids[row.value("id")] != 1 || variants[row.value("app") to row.value("platform")] != 1) null
            else readRecord(row, meta, deviceMap)
        }
    }.getOrDefault(emptyList())

    private fun readRecord(row: JSONObject, meta: JSONObject, devices: Map<String, JSONObject>): UserAgentTemplate? {
        val id = row.value("id") ?: return null
        val app = row.value("app") ?: return null
        val platform = row.value("platform")?.takeIf { it in setOf("android", "ios") } ?: return null
        val template = row.value("template") ?: return null
        if (row.optInt("schemaVersion", 1) != 1 || (row.optJSONArray("unresolved_slots")?.length() ?: 0) > 0) return null
        if (platform == "android" && ("iPhone; CPU iPhone OS" in template || "iPad; CPU OS" in template)) return null
        if (platform == "ios" && "Linux; Android" in template) return null
        val release = meta.optJSONObject("apps")?.optJSONObject(app) ?: return null
        val releases = listOf(release) + rows(release.optJSONArray("versions"))
        val declared = row.optJSONObject("slots") ?: return null
        val slots = declared.keys().asSequence().associateWith { declared.optString(it) }
        if (placeholder.findAll(template).map { it.groupValues[1] }.toSet() != slots.keys) return null
        val evidence = row.optJSONArray("evidence") ?: return null
        val matches = mutableListOf<Source>()
        var matchedRelease: JSONObject? = null
        for (index in 0 until evidence.length()) {
            val item = evidence.optJSONObject(index) ?: return null
            val kind = item.value("kind") ?: return null
            val sourceId = item.value("source_id")?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (item.value("quoted_text") == null || !dated(item, "observed_at") || !dated(item, "fetched_at")) return null
            val site = publisher(item.value("source_url"))
            if (kind == "candidate-script" && site == null && item.value("source_ref") != null) continue
            if (site == null || kind !in corroboratingKinds + "candidate-script") return null
            val version = item.value("app_version")
            val build = item.value("app_build")
            val pair = releases.firstOrNull { it.value("version") == version && it.value("build") == build }
            if (version != null && build != null && pair == null) return null
            if (kind in corroboratingKinds && agrees(template, slots, app, item)) {
                matches += Source(sourceId, site, kind, item)
                if (version != null && build != null && pair != null) matchedRelease = matchedRelease ?: pair
            }
        }
        val pair = matchedRelease ?: return null
        val first = matches.firstOrNull { a ->
            a.kind in primary && matches.any { b -> a.id != b.id && a.publisher != b.publisher }
        } ?: return null

        fun render(device: JSONObject?, os: String): String? {
            var result = template
            for ((name, path) in slots) {
                val value = when (path) {
                    "meta.apps.$app.version" -> release.value("version")
                    "meta.apps.$app.build" -> release.value("build")
                    "meta.apps.$app.versions[].version" -> pair.value("version")
                    "meta.apps.$app.versions[].build" -> pair.value("build")
                    "meta.versions.chrome" -> meta.optJSONObject("versions")?.value("chrome")
                    "device.osVersions[]" -> os.takeIf { v -> device?.optJSONArray("osVersions")?.let { a -> (0 until a.length()).any { a.optString(it) == v } } == true }
                    "device.builds[osVersion]" -> device?.optJSONObject("builds")?.value(os)
                    "device.display.w", "device.display.h", "device.display.dpr", "device.display.ppi" ->
                        device?.optJSONObject("display")?.value(path.substringAfterLast('.'))
                    "device.brand", "device.model", "device.uaModel", "device.id", "device.codename", "device.soc" ->
                        device?.value(path.substringAfter('.'))
                    else -> null
                } ?: return null
                result = result.replace("{$name}", value)
            }
            return result.takeIf { it.isNotBlank() }
        }
        // Hide a record whose declared paths have no usable values in this catalog.
        val usable = devices.values.any { device ->
            val os = device.optJSONArray("osVersions") ?: return@any false
            (0 until os.length()).any { render(device, os.optString(it)) != null }
        }
        if (!usable) return null
        return UserAgentTemplate(id, app, app.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
            platform.uppercase(Locale.ROOT), template, first.evidence.getString("source_url"),
            first.evidence.getString("quoted_text"), true) { deviceId, os -> render(devices[deviceId], os) }
    }

    private fun dated(item: JSONObject, key: String) =
        runCatching { LocalDate.parse(item.getString(key)) }.isSuccess

    private fun agrees(template: String, slots: Map<String, String>, app: String, item: JSONObject): Boolean {
        var expected = template
        for ((name, path) in slots) {
            for (field in listOf("version", "build")) {
                if (path == "meta.apps.$app.$field" || path == "meta.apps.$app.versions[].$field") {
                    item.value("app_$field")?.let { expected = expected.replace("{$name}", it) }
                }
            }
        }
        val observed = item.value("observed")
        if (observed == null) return item.value("kind") == "official-doc" &&
            listOf(template, expected).any { it in item.optString("quoted_text") }
        val pattern = buildString {
            var start = 0
            for (slot in placeholder.findAll(expected)) {
                append(Regex.escape(expected.substring(start, slot.range.first)))
                append("""[^;()\[\]]+?""")
                start = slot.range.last + 1
            }
            append(Regex.escape(expected.substring(start)))
        }
        return Regex(pattern).matches(observed)
    }
}
