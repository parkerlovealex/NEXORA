package com.nexora.browser.data

/**
 * One real-device preset from assets/devices.json.
 * widthPx/heightPx are physical pixels; the browser-visible CSS size is px / dpr.
 */
data class DevicePreset(
    val id: String,
    val brand: String,
    val model: String,
    val uaModel: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpr: Double,
    val ramOptions: List<Int>,
    val storageOptions: List<Int>,
    val cores: Int,
    val gpu: String,
    val osVersions: List<String>,
    val isIos: Boolean,
    /** OS version -> firmware build ID, from the same device row (coherence). */
    val builds: Map<String, String> = emptyMap()
) {
    /** "Samsung Galaxy S24" */
    val displayName: String get() = "$brand $model"

    /** CSS viewport width the stock browser would report. */
    val cssWidth: Int get() = Math.round(widthPx / dpr).toInt()

    /** CSS viewport height the stock browser would report. */
    val cssHeight: Int get() = Math.round(heightPx / dpr).toInt()
}
