package com.nexora.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads the real-device preset database (cached after first load).
 * Source order: the auto-updated copy in filesDir (see DeviceDataUpdater),
 * then the bundled assets/devices.json as the offline fallback.
 */
object DeviceDb {

    @Volatile
    private var cached: List<DevicePreset>? = null

    @Volatile
    private var cachedDataVersion: String = ""

    @Volatile
    private var cachedChromeVersion: String = ""

    /** appId -> (version, uaToken) from meta.apps; either may be blank. */
    @Volatile
    private var cachedApps: Map<String, Pair<String, String>> = emptyMap()

    fun invalidateCache() {
        cached = null
    }

    fun appVersion(context: Context, appId: String): String {
        load(context)
        return cachedApps[appId]?.first ?: ""
    }

    fun appToken(context: Context, appId: String): String {
        load(context)
        return cachedApps[appId]?.second ?: ""
    }

    private fun parseMeta(root: JSONObject) {
        val meta = root.optJSONObject("meta") ?: return
        cachedDataVersion = meta.optString("dataVersion")
        cachedChromeVersion = meta.optJSONObject("versions")?.optString("chrome") ?: ""
        val apps = meta.optJSONObject("apps") ?: return
        val map = mutableMapOf<String, Pair<String, String>>()
        val keys = apps.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = apps.opt(k)
            map[k] = when (v) {
                is JSONObject -> (v.optString("version") to v.optString("token"))
                is String -> (v to "")
                else -> ("" to "")
            }
        }
        cachedApps = map
    }

    /** dataVersion of the active database ("" until loaded, then e.g. "2026.11.23.1"). */
    fun dataVersion(context: Context): String {
        load(context)
        return cachedDataVersion
    }

    /** Chrome version from the active database meta, or "" when unknown. */
    fun chromeVersion(context: Context): String {
        load(context)
        return cachedChromeVersion
    }

    fun load(context: Context): List<DevicePreset> {
        cached?.let { return it }
        val result = mutableListOf<DevicePreset>()
        try {
            val text = DeviceDataUpdater.activeDataText(context)
                ?: context.assets.open("devices.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            parseMeta(root)
            parseArray(root.optJSONArray("android"), isIos = false, out = result)
            parseArray(root.optJSONArray("ios_emulation"), isIos = true, out = result)
        } catch (e: Exception) {
            // A broken DB must not crash the app; callers handle an empty list.
        }
        if (result.isEmpty()) {
            // The downloaded copy was unusable: fall back to the bundled copy once.
            try {
                val text = context.assets.open("devices.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                parseMeta(root)
                parseArray(root.optJSONArray("android"), isIos = false, out = result)
                parseArray(root.optJSONArray("ios_emulation"), isIos = true, out = result)
            } catch (e: Exception) {
                // Bundled copy broken too; callers handle an empty list.
            }
        }
        cached = result
        return result
    }

    fun find(context: Context, deviceId: String): DevicePreset? =
        load(context).firstOrNull { it.id == deviceId }

    private fun parseArray(array: JSONArray?, isIos: Boolean, out: MutableList<DevicePreset>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val display = o.optJSONObject("display") ?: continue
            out.add(
                DevicePreset(
                    id = o.optString("id"),
                    brand = o.optString("brand"),
                    model = o.optString("model"),
                    uaModel = o.optString("uaModel"),
                    widthPx = display.optInt("w"),
                    heightPx = display.optInt("h"),
                    dpr = display.optDouble("dpr", 1.0),
                    ramOptions = intList(o.optJSONArray("ramGb")),
                    storageOptions = intList(o.optJSONArray("storageGb")),
                    cores = o.optInt("cores", 8),
                    gpu = o.optString("gpu"),
                    osVersions = stringList(o.optJSONArray("osVersions")),
                    isIos = isIos,
                    builds = stringMap(o.optJSONObject("builds"))
                )
            )
        }
    }

    private fun intList(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val list = mutableListOf<Int>()
        for (i in 0 until array.length()) list.add(array.optInt(i))
        return list
    }

    private fun stringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k)
        }
        return map
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) list.add(array.optString(i))
        return list
    }
}
