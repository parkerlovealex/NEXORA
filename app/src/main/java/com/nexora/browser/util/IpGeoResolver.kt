package com.nexora.browser.util

import com.nexora.browser.data.BrowserProfile
import org.json.JSONObject
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

/**
 * Resolves timezone + language from the IP a profile actually browses with.
 *
 * The lookup goes THROUGH the profile's proxy (same JVM Proxy pattern as
 * ProxyTester), so the answer describes the proxy's exit IP, never the
 * device's real connection - except for proxy-less profiles, where the
 * device's own IP is the browsing IP and a direct lookup is correct.
 *
 * Source: ipapi.co (no key). Returns null on any failure; callers fall back
 * to device timezone/language for that session and say so.
 */
object IpGeoResolver {

    data class IpGeo(val timezone: String, val language: String, val countryCode: String)

    private val authLock = Any()

    /** Identity of the connection whose geo was resolved; stored on the profile. */
    fun connectionKey(profile: BrowserProfile): String =
        if (profile.hasProxy) "${profile.proxyType}://${profile.proxyHost}:${profile.proxyPort}"
        else "DIRECT"

    fun resolve(profile: BrowserProfile): IpGeo? {
        return synchronized(authLock) {
            var connection: HttpURLConnection? = null
            try {
                if (profile.proxyUser.isNotEmpty()) {
                    val user = profile.proxyUser
                    val pass = profile.proxyPass
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication =
                            PasswordAuthentication(user, pass.toCharArray())
                    })
                } else {
                    Authenticator.setDefault(null)
                }
                val url = URL("https://ipapi.co/json/")
                connection = if (profile.hasProxy) {
                    val type = when (profile.proxyType) {
                        "SOCKS5" -> Proxy.Type.SOCKS
                        "HTTP" -> Proxy.Type.HTTP
                        else -> return null
                    }
                    val proxy = Proxy(type, InetSocketAddress(profile.proxyHost, profile.proxyPort))
                    url.openConnection(proxy) as HttpURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "NEXORA-IpGeo")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } catch (e: Exception) {
                null
            } finally {
                try {
                    Authenticator.setDefault(null)
                } catch (_: Exception) {
                }
                connection?.disconnect()
            }
        }
    }

    internal fun parse(body: String): IpGeo? {
        return try {
            val o = JSONObject(body)
            if (o.optBoolean("error")) return null
            val timezone = o.optString("timezone")
            if (timezone !in java.util.TimeZone.getAvailableIDs()) return null
            // ipapi.co "languages" is a comma list, most-preferred first ("en-US,es").
            val language = o.optString("languages").split(',').firstOrNull()?.trim().orEmpty()
            if (!language.matches(Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*$"))) return null
            IpGeo(timezone, language, o.optString("country_code"))
        } catch (e: Exception) {
            null
        }
    }
}
