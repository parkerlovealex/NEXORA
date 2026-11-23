package com.nexora.browser.util

import com.nexora.browser.data.BrowserProfile
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

/**
 * Tests the configured proxy with a direct JVM connection, not WebView.
 * The JVM Authenticator is process-wide, so tests are serialized and the
 * previous authenticator is restored immediately after each test.
 */
object ProxyTester {

    private val authLock = Any()

    fun test(profile: BrowserProfile, callback: (String) -> Unit) {
        if (profile.proxyType == "NONE") {
            callback("No proxy set")
            return
        }
        if (profile.proxyHost.isBlank() || profile.proxyPort !in 1..65535) {
            callback("Failed: host or port is missing/invalid")
            return
        }
        Thread {
            val start = System.currentTimeMillis()
            val result = synchronized(authLock) {
                // Android has no Authenticator.getDefault() before API 35; the app never
                // installs a global authenticator outside this test, so restore = clear.
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

                    val type = when (profile.proxyType) {
                        "SOCKS5" -> Proxy.Type.SOCKS
                        "HTTP" -> Proxy.Type.HTTP
                        else -> throw IllegalArgumentException("Unsupported proxy type")
                    }
                    val proxy = Proxy(type, InetSocketAddress(profile.proxyHost, profile.proxyPort))
                    connection = URL("https://api.ipify.org").openConnection(proxy) as HttpURLConnection
                    connection!!.connectTimeout = 8000
                    connection!!.readTimeout = 8000
                    connection!!.requestMethod = "GET"
                    connection!!.setRequestProperty("User-Agent", "NEXORA-ProxyTest")
                    val ip = connection!!.inputStream.bufferedReader().use { it.readText() }.trim()
                    val elapsed = System.currentTimeMillis() - start
                    if (ip.isBlank()) "Failed: empty response from proxy"
                    else "OK — exit IP $ip (${elapsed} ms)"
                } catch (e: Exception) {
                    "Failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    try { connection?.disconnect() } catch (_: Exception) {}
                    try { Authenticator.setDefault(null) } catch (_: Exception) {}
                }
            }
            callback(result)
        }.apply {
            name = "NEXORA-Proxy-Test"
            isDaemon = true
        }.start()
    }
}
