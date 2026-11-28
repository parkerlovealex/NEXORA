package com.nexora.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Loads the last validated remote catalog, with the bundled asset as offline fallback. */
object DeviceDb {

    @Volatile
    private var cached: List<DevicePreset>? = null

    fun load(context: Context): List<DevicePreset> {
        cached?.let { return it }
        val downloaded = DeviceDataUpdater.activeDataText(context)
        val result = downloaded?.let { parseCatalog(it) }
            ?: readBundledText(context)?.let { parseCatalog(it) }
            ?: emptyList()
        cached = result
        return result
    }

    fun find(context: Context, deviceId: String): DevicePreset? =
        load(context).firstOrNull { it.id == deviceId }

    /**
     * Only verified templates from the canonical catalog are exposed to the generator.
     * The bundled generated assets (tools/build_android_catalog_assets.py) are the
     * source: ua_templates.json + ua_slot_context.json. Missing or invalid assets
     * yield an empty list.
     */
    fun loadVerifiedUserAgentTemplates(context: Context): List<UserAgentTemplate> {
        val templates = readAssetText(context, "ua_templates.json") ?: return emptyList()
        val slotContext = readAssetText(context, "ua_slot_context.json") ?: return emptyList()
        return PublishedUserAgentTemplates.read(templates, slotContext)
    }

    private fun readAssetText(context: Context, name: String): String? {
        return try {
            context.assets.open(name).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    fun invalidateCache() {
        cached = null
    }

    fun dataVersion(context: Context): String = readMeta(context).optString("dataVersion", "")

    fun chromeVersion(context: Context): String =
        readMeta(context).optJSONObject("versions")?.optString("chrome").orEmpty()

    private fun readMeta(context: Context): JSONObject {
        val downloaded = DeviceDataUpdater.activeDataText(context)
        val candidates = listOfNotNull(downloaded, readBundledText(context))
        for (text in candidates) {
            try {
                val meta = JSONObject(text).optJSONObject("meta")
                if (meta != null) return meta
            } catch (_: Exception) {
                // Try the bundled copy if a local snapshot is damaged.
            }
        }
        return JSONObject()
    }

    private fun readBundledText(context: Context): String? {
        return try {
            context.assets.open("devices.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCatalog(text: String): List<DevicePreset>? {
        return try {
            val root = JSONObject(text)
            val result = mutableListOf<DevicePreset>()
            parseArray(root.optJSONArray("android"), isIos = false, out = result)
            parseArray(root.optJSONArray("ios_emulation"), isIos = true, out = result)
            result.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArray(array: JSONArray?, isIos: Boolean, out: MutableList<DevicePreset>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val display = o.optJSONObject("display") ?: continue
            val id = o.optString("id")
            val brand = o.optString("brand")
            val model = o.optString("model")
            val uaModel = o.optString("uaModel")
            val width = display.optInt("w")
            val height = display.optInt("h")
            val dpr = display.optDouble("dpr", 1.0)
            if (id.isBlank() || brand.isBlank() || model.isBlank() || uaModel.isBlank() ||
                width <= 0 || height <= 0 || dpr <= 0.0
            ) continue
            out.add(
                DevicePreset(
                    id = id,
                    brand = brand,
                    model = model,
                    uaModel = uaModel,
                    widthPx = width,
                    heightPx = height,
                    dpr = dpr,
                    ramOptions = intList(o.optJSONArray("ramGb")),
                    storageOptions = intList(o.optJSONArray("storageGb")),
                    cores = o.optInt("cores", 8),
                    gpu = o.optString("gpu"),
                    osVersions = stringList(o.optJSONArray("osVersions")),
                    isIos = isIos
                )
            )
        }
    }

    private fun intList(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val list = mutableListOf<Int>()
        for (i in 0 until array.length()) {
            val value = array.optInt(i)
            if (value > 0) list.add(value)
        }
        return list
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty()) list.add(value)
        }
        return list
    }
}
