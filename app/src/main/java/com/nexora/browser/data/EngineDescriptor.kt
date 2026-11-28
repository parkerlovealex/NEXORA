package com.nexora.browser.data

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * The browser engine actually installed on this device, established from the
 * Android WebView provider — never from the catalog and never from a selected
 * or generated User-Agent. [fullVersion] is set only when the WebView package
 * itself reports it; a version parsed from the native UA proves the major
 * only, because a reduced token such as Chrome/158.0.0.0 is not a full build.
 */
data class EngineDescriptor(
    val family: String?,
    val major: Int?,
    val fullVersion: String?,
    val fullVersionTrust: String,
    val platform: String
) {
    companion object {
        const val TRUST_PACKAGE = "webview-package"
        const val TRUST_UNKNOWN = "unknown"

        private val PACKAGE_VERSION = Regex("""^(\d+)(?:\.\d+){1,3}$""")
        private val UA_TOKEN = Regex("""(?:Chrome|CriOS)/([0-9]+(?:\.[0-9]+){1,3})""", RegexOption.IGNORE_CASE)

        /**
         * Pure descriptor builder. The WebView package version wins when it is
         * a well-formed version; otherwise the native UA token is the fallback;
         * otherwise the engine stays unknown. Nothing is ever substituted from
         * the catalog.
         */
        fun fromRuntime(packageVersion: String?, nativeUserAgent: String?): EngineDescriptor {
            val pkg = packageVersion?.trim()?.takeIf { it.isNotEmpty() }
            if (pkg != null && PACKAGE_VERSION.matches(pkg)) {
                val major = pkg.substringBefore('.').toIntOrNull()
                if (major != null && major > 0) {
                    return EngineDescriptor("chromium", major, pkg, TRUST_PACKAGE, "android")
                }
            }
            val token = nativeUserAgent?.let { UA_TOKEN.find(it)?.groupValues?.getOrNull(1) }
            if (token != null) {
                val major = token.substringBefore('.').toIntOrNull()
                if (major != null && major > 0) {
                    return EngineDescriptor("chromium", major, null, TRUST_UNKNOWN, "android")
                }
            }
            return EngineDescriptor(null, null, null, TRUST_UNKNOWN, "android")
        }

        /** Reads the installed WebView provider (API 26+) and the default UA. */
        fun detect(context: Context): EngineDescriptor {
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    WebView.getCurrentWebViewPackage()?.versionName
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            val ua = try {
                WebSettings.getDefaultUserAgent(context)
            } catch (e: Exception) {
                null
            }
            return fromRuntime(pkg, ua)
        }
    }
}
