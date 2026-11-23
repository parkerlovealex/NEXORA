package com.nexora.browser.util

import com.nexora.browser.data.DevicePreset

/** Builds the user-agent string a stock browser on the preset device would send. */
object UaBuilder {

    /** Used only when the device database carries no Chrome version. */
    const val FALLBACK_CHROME = "126.0.6478.122"

    fun build(preset: DevicePreset, osVersion: String, chromeVersion: String = ""): String {
        val chrome = chromeVersion.ifBlank { FALLBACK_CHROME }
        return if (preset.isIos) {
            val os = osVersion.replace('.', '_')
            "Mozilla/5.0 (iPhone; CPU iPhone OS $os like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Version/17.5 Mobile/15E148 Safari/604.1"
        } else {
            "Mozilla/5.0 (Linux; Android $osVersion; ${preset.uaModel}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chrome Mobile Safari/537.36"
        }
    }

    /** Desktop Chrome UA used by the browser's "Desktop site" toggle. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"
}
