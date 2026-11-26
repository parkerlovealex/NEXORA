package com.nexora.browser.util

import com.nexora.browser.data.BrowserProfile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Explicit proxy test. Traffic is sent through NEXORA's loopback relay so HTTP,
 * HTTPS and authenticated SOCKS5/HTTP proxy sessions use the same transport path
 * as BrowserActivity. No process-wide Authenticator is installed.
 */
object ProxyTester {

    fun test(profile: BrowserProfile, callback: (String) -> Unit) {
        if (profile.proxyType == "NONE") {
            callback("No proxy set")
            return
        }
        if (!profile.hasProxy) {
            callback("Failed: host or port is missing/invalid")
            return
        }

        Thread({
            val start = System.currentTimeMillis()
            var relay: ProxyRelayServer? = null
            var connection: HttpURLConnection? = null
            val result = try {
                val config = ProxyRelayConfig(
                    type = profile.proxyType,
                    host = profile.proxyHost,
                    port = profile.proxyPort,
                    username = profile.proxyUser,
                    password = profile.proxyPass
                )
                config.validate()?.let { throw IllegalArgumentException(it) }
                relay = ProxyRelayServer(config)
                val localPort = relay.start()
                val localProxy = Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress("127.0.0.1", localPort)
                )
                val opened = URI.create(TEST_URL).toURL().openConnection(localProxy) as HttpURLConnection
                connection = opened
                opened.connectTimeout = CONNECT_TIMEOUT_MS
                opened.readTimeout = READ_TIMEOUT_MS
                opened.requestMethod = "GET"
                opened.useCaches = false
                opened.instanceFollowRedirects = false
                opened.setRequestProperty("User-Agent", "NEXORA-ProxyTest")
                val ip = opened.inputStream.bufferedReader().use { it.readText() }.trim()
                val elapsed = System.currentTimeMillis() - start
                if (ip.isBlank()) "Failed: empty response from proxy"
                else "OK — exit IP $ip (${elapsed} ms)"
            } catch (error: Exception) {
                "Failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
                try { relay?.close() } catch (_: Exception) {}
            }
            callback(result)
        }, "NEXORA-Proxy-Test").apply {
            isDaemon = true
            start()
        }
    }

    private const val TEST_URL = "https://api.ipify.org"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
}
