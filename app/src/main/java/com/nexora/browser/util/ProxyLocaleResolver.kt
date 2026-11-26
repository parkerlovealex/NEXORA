package com.nexora.browser.util

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.HttpURLConnection
import java.util.TimeZone

data class ProxyLocaleResult(
    val exitIp: String,
    val timezoneId: String?,
    val languageTags: List<String>
)

/**
 * User-initiated IP-locale lookup routed through the same local proxy relay used
 * by BrowserActivity. No direct-network fallback is attempted.
 */
object ProxyLocaleResolver {

    fun lookup(config: ProxyRelayConfig, callback: (Result<ProxyLocaleResult>) -> Unit) {
        Thread({
            var relay: ProxyRelayServer? = null
            var connection: HttpURLConnection? = null
            val result = try {
                config.validate()?.let { throw IllegalArgumentException(it) }
                relay = ProxyRelayServer(config)
                val port = relay.start()
                val localProxy = Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress("127.0.0.1", port)
                )
                val opened = URI.create(LOOKUP_URL).toURL().openConnection(localProxy) as HttpURLConnection
                connection = opened
                opened.connectTimeout = CONNECT_TIMEOUT_MS
                opened.readTimeout = READ_TIMEOUT_MS
                opened.requestMethod = "GET"
                opened.useCaches = false
                opened.instanceFollowRedirects = false
                opened.setRequestProperty("Accept", "application/json")
                opened.setRequestProperty("User-Agent", "NEXORA-ProxyLocale")
                val status = opened.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("IP locale provider returned HTTP $status")
                }
                val body = opened.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(2048)
                    var total = 0
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_CHARS) {
                            throw IllegalStateException("IP locale response exceeded the size limit")
                        }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }
                val json = JSONObject(body)
                if (json.optBoolean("error", false)) {
                    throw IllegalStateException(
                        json.optString("reason").ifBlank { "IP locale provider rejected the request" }
                    )
                }
                val timezone = json.optString("timezone").takeIf {
                    it.isNotBlank() && it in TimeZone.getAvailableIDs()
                }
                val languages = json.optString("languages")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.matches(LANGUAGE_TAG) }
                    .distinct()
                val ip = json.optString("ip").take(64)
                if (timezone == null && languages.isEmpty()) {
                    throw IllegalStateException("Provider returned no usable timezone or language suggestions")
                }
                Result.success(ProxyLocaleResult(ip, timezone, languages))
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
                try { relay?.close() } catch (_: Exception) {}
            }
            callback(result)
        }, "NEXORA-ProxyLocale").apply {
            isDaemon = true
            start()
        }
    }

    private val LANGUAGE_TAG = Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*$")
    private const val LOOKUP_URL = "https://ipapi.co/json/"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_CHARS = 32 * 1024
}
