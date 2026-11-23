package com.nexora.browser.util

import com.nexora.browser.data.DevicePreset

/**
 * App-mode user-agents: how a link opened inside a social / messaging app's
 * in-app browser identifies itself, built from the SAME device row as the
 * plain browser UA (coherence: model, OS, build, screen never mix devices).
 *
 * Facebook and Instagram formats are the real ones (from Karn's UA corpus).
 * The other apps send a stock WebView UA, plus their own token when the
 * device database carries one in meta.apps (filled by the weekly refresh
 * from verified sources only - no invented tokens).
 */
object AppUa {

    data class AppInfo(val id: String, val label: String)

    val APPS: List<AppInfo> = listOf(
        AppInfo("facebook", "Facebook"),
        AppInfo("instagram", "Instagram"),
        AppInfo("tiktok", "TikTok"),
        AppInfo("x", "X (Twitter)"),
        AppInfo("threads", "Threads"),
        AppInfo("linkedin", "LinkedIn"),
        AppInfo("pinterest", "Pinterest"),
        AppInfo("reddit", "Reddit"),
        AppInfo("snapchat", "Snapchat"),
        AppInfo("weibo", "Weibo"),
        AppInfo("telegram", "Telegram"),
        AppInfo("messenger", "Messenger"),
        AppInfo("wechat", "WeChat"),
        AppInfo("line", "LINE"),
        AppInfo("kik", "Kik"),
        AppInfo("discord", "Discord"),
        AppInfo("viber", "Viber")
    )

    private fun sdkFor(osVersion: String): Int = when (osVersion.substringBefore('.')) {
        "15" -> 35
        "14" -> 34
        "13" -> 33
        "12" -> 31
        "11" -> 30
        "10" -> 29
        else -> 34
    }

    /** Stock Android WebView UA for this device row. */
    fun webViewBase(preset: DevicePreset, osVersion: String, chromeVersion: String): String {
        val chrome = chromeVersion.ifBlank { UaBuilder.FALLBACK_CHROME }
        val build = preset.builds[osVersion]
        val buildPart = if (build.isNullOrBlank()) "" else " Build/$build"
        return "Mozilla/5.0 (Linux; Android $osVersion; ${preset.uaModel}$buildPart; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/$chrome Mobile Safari/537.36"
    }

    fun build(
        appId: String,
        preset: DevicePreset,
        osVersion: String,
        chromeVersion: String,
        appVersion: String,
        appToken: String
    ): String {
        if (preset.isIos) return buildIos(appId, preset, osVersion, appVersion)
        val base = webViewBase(preset, osVersion, chromeVersion)
        return when (appId) {
            "facebook" -> base + if (appVersion.isNotBlank())
                " [FB_IAB/FB4A;FBAV/$appVersion;IABMV/1;]" else " [FB_IAB/FB4A;IABMV/1;]"
            "messenger" -> base + if (appVersion.isNotBlank())
                " [FB_IAB/MESSENGER;FBAV/$appVersion;IABMV/1;]" else " [FB_IAB/MESSENGER;IABMV/1;]"
            "instagram" -> if (appVersion.isNotBlank()) {
                val dpi = Math.round(preset.dpr * 160).toInt()
                "Instagram $appVersion Android (${sdkFor(osVersion)}/$osVersion; ${dpi}dpi; " +
                    "${preset.widthPx}x${preset.heightPx}; ${preset.brand.lowercase()}; " +
                    "${preset.uaModel}; en_US)"
            } else base
            else -> if (appToken.isNotBlank()) "$base $appToken" else base
        }
    }

    private fun buildIos(
        appId: String,
        preset: DevicePreset,
        osVersion: String,
        appVersion: String
    ): String {
        val os = osVersion.replace('.', '_')
        val safari = "Mozilla/5.0 (iPhone; CPU iPhone OS $os like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        return when (appId) {
            "facebook" -> safari + if (appVersion.isNotBlank())
                " [FBAN/FBIOS;FBAV/$appVersion;FBDV/${preset.uaModel};FBMD/iPhone;]" else
                " [FBAN/FBIOS;FBDV/${preset.uaModel};FBMD/iPhone;]"
            "instagram" -> if (appVersion.isNotBlank()) {
                val scale = if (preset.dpr % 1.0 == 0.0) preset.dpr.toInt().toString() + ".00"
                    else preset.dpr.toString()
                "$safari Instagram $appVersion (${preset.uaModel}; iOS $os; en_US; " +
                    "scale=$scale; ${preset.widthPx}x${preset.heightPx}; IABMV/1)"
            } else safari
            else -> safari
        }
    }
}
