package com.nexora.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class NexoraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Diagnostic: save any uncaught crash to a file so the next launch can show it.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").writeText("Thread: ${thread.name}\n$sw")
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, e)
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        // Native browser download alerts are created locally on this device.
        com.nexora.browser.util.BrowserNotifications.createChannel(this)

        // Optional daily catalog sync. If no API origin is configured, the
        // bundled database remains the only source and the app stays offline-ready.
        com.nexora.browser.data.DeviceDataUpdater.maybeAutoCheck(this)
    }

    companion object {
        const val PREFS_NAME = "nexora_prefs"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SEARCH_ENGINE = "search_engine"
        const val KEY_CATALOG_API_BASE = "catalog_api_base"
        const val KEY_DATA_LAST_CHECK = "data_last_check"
        const val KEY_SOUND_EFFECTS = "sound_effects"
        const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        const val KEY_DOWNLOAD_NOTIFICATIONS = "download_notifications"
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val SEARCH_DUCKDUCKGO = "duckduckgo"
        const val SEARCH_GOOGLE = "google"
        const val SEARCH_BING = "bing"
        const val SEARCH_BRAVE = "brave"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}
