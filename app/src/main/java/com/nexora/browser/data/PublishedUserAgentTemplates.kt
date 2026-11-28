package com.nexora.browser.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate
import java.util.Locale

/**
 * Reads the canonical UA template catalog (web/ua_templates.json, bundled as the
 * generated asset ua_templates.json) and binds slots only to values in the generated
 * slot context (ua_slot_context.json, produced from the canonical devices catalog by
 * tools/build_android_catalog_assets.py).
 *
 * Ported from the fix/alpha30 campaign's reader and aligned with the canonical
 * validator (tools/validate_ua_templates.py) and the Phase 4.2B decisions:
 * exact catalogReleaseId + deviceCatalogVersion matching against the slot context,
 * catalog-global slot paths only (device-dependent slots stay fail-closed),
 * verified records only, and the complete evidence array preserved in the model.
 * Any failed check excludes the record; nothing is guessed.
 */
internal object PublishedUserAgentTemplates {
    private val placeholder = Regex("""\{([A-Za-z][A-Za-z0-9_]*)\}""")
    private val revision = Regex("""\d{4}\.\d{2}\.\d{2}\.\d+""")
    private val slotPath = Regex("""^meta\.apps\.([a-z0-9_]+)\.(version|build)$""")
    private val primary = setOf("observed-ua", "official-doc")
    private val corroboratingKinds = primary + setOf("published-dataset-entry", "technical-reference")
    private val knownKinds = corroboratingKinds + "candidate-script"
    private val engineFamilies = setOf("chromium", "gecko", "webkit")

    /** Second-level public suffixes, mirroring evidence_domain() in the canonical validator. */
    private val secondLevelSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz",
        "com.br", "com.cn", "com.tw", "co.jp", "co.kr", "com.mx", "com.tr", "co.za",
        "com.sg", "com.hk", "co.in", "com.co", "com.pe", "com.ve", "com.ar", "com.ec"
    )

    /** App display labels, mirroring APP_NAMES in web/app.js. */
    private val appLabels = mapOf(
        "facebook" to "Facebook", "fblite" to "Facebook Lite", "fb_lite" to "Facebook Lite",
        "instagram" to "Instagram", "tiktok" to "TikTok", "x" to "X", "threads" to "Threads",
        "linkedin" to "LinkedIn", "pinterest" to "Pinterest", "reddit" to "Reddit",
        "snapchat" to "Snapchat", "weibo" to "Weibo", "telegram" to "Telegram",
        "messenger" to "Messenger", "wechat" to "WeChat", "line" to "LINE", "kik" to "Kik",
        "discord" to "Discord", "viber" to "Viber"
    )

    fun displayName(appId: String): String = appLabels[appId] ?: appId

    private fun rows(array: JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf { value -> value.isNotBlank() } }

    private fun JSONObject.value(key: String): String? = when (val value = opt(key)) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toString()
        else -> null
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private fun dated(item: JSONObject, key: String): Boolean =
        runCatching { LocalDate.parse(item.getString(key)) }.isSuccess

    /** Registrable domain of an https URL, or null when the URL is not a valid https address. */
    private fun publisher(address: String?): String? = runCatching {
        val url = URI(address ?: return null)
        if (url.scheme != "https" || url.rawUserInfo != null || url.host == null) return null
        val labels = url.host.lowercase(Locale.ROOT).trimEnd('.').split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return null
        val lastTwo = labels.takeLast(2).joinToString(".")
        if (lastTwo in secondLevelSuffixes && labels.size >= 3) labels.takeLast(3).joinToString(".") else lastTwo
    }.getOrNull()

    fun read(templatesText: String?, slotContextText: String?): List<UserAgentTemplate> = runCatching {
        val catalog = JSONObject(templatesText ?: return emptyList())
        val context = JSONObject(slotContextText ?: return emptyList())
        if (catalog.optInt("uaTemplatesSchema") != 1) return emptyList()
        LocalDate.parse(catalog.getString("updatedAt"))
        if (!revision.matches(catalog.getString("dataVersion"))) return emptyList()
        if (!revision.matches(catalog.getString("deviceCatalogVersion"))) return emptyList()
        // Exact version matching: both documents must belong to the same catalog release.
        if (catalog.getString("deviceCatalogVersion") != context.getString("deviceCatalogVersion")) return emptyList()
        if (catalog.getString("catalogReleaseId") != context.getString("catalogReleaseId")) return emptyList()
        val apps = context.getJSONObject("apps")
        val records = rows(catalog.getJSONArray("records"))
        val verified = records.filter { it.value("status") == "verified" }
        val ids = records.groupingBy { it.value("id") }.eachCount()
        val variants = verified.groupingBy { it.value("app") to it.value("platform") }.eachCount()
        verified.mapNotNull { row ->
            if (ids[row.value("id")] != 1 || variants[row.value("app") to row.value("platform")] != 1) null
            else readRecord(row, apps, catalog)
        }
    }.getOrDefault(emptyList())

    private fun readRecord(row: JSONObject, apps: JSONObject, catalog: JSONObject): UserAgentTemplate? {
        val id = row.value("id") ?: return null
        val app = row.value("app") ?: return null
        val platform = row.value("platform")?.takeIf { it in setOf("android", "ios") } ?: return null
        val template = row.value("template") ?: return null
        if (row.optInt("schemaVersion", 1) != 1) return null
        if ((row.optJSONArray("unresolved_slots")?.length() ?: 0) > 0) return null
        if (platform == "android" && ("iPhone; CPU iPhone OS" in template || "iPad; CPU OS" in template)) return null
        if (platform == "ios" && "Linux; Android" in template) return null

        // Catalog-global slots only, resolved from the slot context for this record's own app.
        val appContext = apps.optJSONObject(app) ?: return null
        val declared = row.optJSONObject("slots") ?: return null
        val slots = declared.keys().asSequence().associateWith { declared.optString(it) }
        if (placeholder.findAll(template).map { it.groupValues[1] }.toSet() != slots.keys) return null
        var resolved = template
        for ((name, path) in slots) {
            val match = slotPath.matchEntire(path) ?: return null
            if (match.groupValues[1] != app) return null
            val value = appContext.value(match.groupValues[2]) ?: return null
            resolved = resolved.replace("{$name}", value)
        }
        if (placeholder.containsMatchIn(resolved)) return null

        val evidence = parseEvidence(row, appContext) ?: return null
        val primaryEntry = evidence.firstOrNull {
            it.kind in primary && !it.sourceUrl.isNullOrBlank()
        } ?: return null

        // Optional evidence-backed engine metadata (Phase 4.3a schema). Parsed
        // defensively: any malformed shape fails closed to "no engine metadata"
        // (compatibility unknown), never to an invented value.
        var engineFamily: String? = null
        var engineMajor: Int? = null
        var engineBasis: String? = null
        var engineEvidenceRefs: List<String> = emptyList()
        val engine = row.optJSONObject("engine")
        if (engine != null) {
            val family = engine.optString("family").trim().lowercase(Locale.ROOT)
            val major = engine.optInt("major", -1)
            val refs = strings(engine.optJSONArray("evidenceRefs"))
            val knownIds = evidence.map { it.sourceId.trim().lowercase(Locale.ROOT) }.toSet()
            if (family in engineFamilies && major >= 1 &&
                engine.optString("basis") == "evidence" && refs.isNotEmpty() &&
                refs.all { it.trim().lowercase(Locale.ROOT) in knownIds }
            ) {
                engineFamily = family
                engineMajor = major
                engineBasis = "evidence"
                engineEvidenceRefs = refs
            }
        }

        return UserAgentTemplate(
            id = id,
            appId = app,
            displayName = displayName(app),
            targetOs = platform.uppercase(Locale.ROOT),
            userAgent = resolved,
            sourceUrl = primaryEntry.sourceUrl.orEmpty(),
            evidenceQuote = primaryEntry.quotedText,
            verified = true,
            status = "verified",
            catalogReleaseId = catalog.optString("catalogReleaseId"),
            catalogDataVersion = catalog.optString("dataVersion"),
            evidence = evidence,
            engineFamily = engineFamily,
            engineMajor = engineMajor,
            engineBasis = engineBasis,
            engineEvidenceRefs = engineEvidenceRefs
        )
    }

    /**
     * Validates the record's evidence with the same rules as the canonical validator and
     * returns the complete parsed list, or null when the record must be excluded:
     * every entry needs a known kind, a quoted text and a usable date; observed kinds need
     * an observation date; a stated app version/build pair must exist in the slot context;
     * at least one primary entry's observation must agree with the template; and the
     * entries must span at least two source ids and two publisher domains.
     */
    private fun parseEvidence(row: JSONObject, appContext: JSONObject): List<UserAgentEvidence>? {
        val array = row.optJSONArray("evidence") ?: return null
        if (array.length() == 0) return null
        val template = row.value("template") ?: return null
        val app = row.value("app") ?: return null
        val slots = row.optJSONObject("slots")?.let { declared ->
            declared.keys().asSequence().associateWith { declared.optString(it) }
        } ?: emptyMap()

        val entries = mutableListOf<UserAgentEvidence>()
        val sourceIds = mutableSetOf<String>()
        val publishers = mutableSetOf<String>()
        var agreement = false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            val kind = item.value("kind") ?: return null
            if (kind !in knownKinds) return null
            val quoted = item.value("quoted_text") ?: return null
            if (!dated(item, "fetched_at") && !dated(item, "observed_at")) return null
            if (kind in primary && !dated(item, "observed_at")) return null
            val site = publisher(item.value("source_url"))
            if (item.value("source_url") != null && site == null) return null
            val version = item.value("app_version")
            val build = item.value("app_build")
            if (version != null && build != null && !pairKnown(appContext, version, build)) return null
            if (kind in primary && agrees(template, slots, app, item)) agreement = true
            item.value("source_id")?.trim()?.lowercase(Locale.ROOT)?.let { sourceIds += it }
            site?.let { publishers += it }
            entries += UserAgentEvidence(
                sourceId = item.optString("source_id"),
                kind = kind,
                sourceUrl = item.nullableString("source_url"),
                sourceRef = item.nullableString("source_ref"),
                quotedText = quoted,
                observed = item.nullableString("observed"),
                appVersion = item.nullableString("app_version"),
                appBuild = item.nullableString("app_build"),
                observedAt = item.nullableString("observed_at"),
                fetchedAt = item.nullableString("fetched_at")
            )
        }
        if (!agreement) return null
        if (sourceIds.size < 2 || publishers.size < 2) return null
        return entries
    }

    /** The stated pair must be the app's current release or one of its catalogued versions. */
    private fun pairKnown(appContext: JSONObject, version: String, build: String): Boolean {
        val pairs = mutableSetOf<Pair<String, String>>()
        val currentVersion = appContext.value("version")
        val currentBuild = appContext.value("build")
        if (currentVersion != null && currentBuild != null) pairs += currentVersion to currentBuild
        for (entry in rows(appContext.optJSONArray("versions"))) {
            val v = entry.value("version")
            val b = entry.value("build")
            if (v != null && b != null) pairs += v to b
        }
        return (version to build) in pairs
    }

    private fun agrees(template: String, slots: Map<String, String>, app: String, item: JSONObject): Boolean {
        var expected = template
        for ((name, path) in slots) {
            for (field in listOf("version", "build")) {
                if (path == "meta.apps.$app.$field") {
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
