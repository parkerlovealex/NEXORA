package com.nexora.browser.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRelayServerTest {

    @Test
    fun configRedactsCredentialsAndRejectsInvalidEndpoints() {
        val config = ProxyRelayConfig("HTTP", "proxy.example", 8080, "user", "very-secret")
        assertFalse(config.toString().contains("very-secret"))
        assertNull(config.validate())
        assertNotNull(ProxyRelayConfig("HTTP", "proxy.example/path", 8080).validate())
        assertNotNull(ProxyRelayConfig("SOCKS4", "proxy.example", 1080).validate())
    }

    @Test
    fun authenticatedHttpProxyReceivesRelayCredentialsNotClientCredentials() {
        val upstream = ServerSocket(0)
        val received = AtomicReference("")
        val fake = Thread {
            upstream.accept().use { socket ->
                socket.soTimeout = 5_000
                received.set(readHeader(socket.getInputStream()))
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                        .toByteArray()
                )
                socket.getOutputStream().flush()
            }
            upstream.close()
        }.apply { isDaemon = true; start() }

        val relay = ProxyRelayServer(
            ProxyRelayConfig("HTTP", "127.0.0.1", upstream.localPort, "relay-user", "relay-pass")
        )
        val port = relay.start()
        val response = Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(
                ("GET http://example.test/path HTTP/1.1\r\n" +
                    "Host: example.test\r\n" +
                    "Proxy-Authorization: Basic d3Jvbmc=\r\n\r\n").toByteArray()
            )
            client.getOutputStream().flush()
            client.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        fake.join(5_000)
        relay.close()

        assertTrue(received.get().startsWith("GET http://example.test/path HTTP/1.1"))
        assertTrue(received.get().contains("Proxy-Authorization: Basic cmVsYXktdXNlcjpyZWxheS1wYXNz"))
        assertFalse(received.get().contains("d3Jvbmc="))
        assertTrue(response.contains("200 OK"))
        assertTrue(response.endsWith("OK"))
    }

    @Test
    fun authenticatedSocks5UsesRemoteHostnameAndOriginFormRequest() {
        val upstream = ServerSocket(0)
        val greeting = AtomicReference<List<Int>>(emptyList())
        val auth = AtomicReference("")
        val connectHost = AtomicReference("")
        val request = AtomicReference("")
        val fake = Thread {
            upstream.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val hello = readExact(input, 2)
                val methods = readExact(input, hello[1].toInt() and 0xff)
                greeting.set(hello.map { it.toInt() and 0xff } + methods.map { it.toInt() and 0xff })
                output.write(byteArrayOf(5, 2))
                output.flush()

                val authHeader = readExact(input, 2)
                val username = readExact(input, authHeader[1].toInt() and 0xff)
                val passwordLength = readExact(input, 1)[0].toInt() and 0xff
                val password = readExact(input, passwordLength)
                auth.set(String(username) + ":" + String(password))
                output.write(byteArrayOf(1, 0))
                output.flush()

                val connectHeader = readExact(input, 5)
                val hostLength = connectHeader[4].toInt() and 0xff
                connectHost.set(String(readExact(input, hostLength)))
                readExact(input, 2)
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                output.flush()

                request.set(readHeader(input))
                output.write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                        .toByteArray()
                )
                output.flush()
            }
            upstream.close()
        }.apply { isDaemon = true; start() }

        val relay = ProxyRelayServer(
            ProxyRelayConfig("SOCKS5", "127.0.0.1", upstream.localPort, "socks-user", "socks-pass")
        )
        val port = relay.start()
        val response = Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(
                "GET http://example.test/path HTTP/1.1\r\nHost: example.test\r\n\r\n".toByteArray()
            )
            client.getOutputStream().flush()
            client.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        fake.join(5_000)
        relay.close()

        assertEquals(listOf(5, 2, 0, 2), greeting.get())
        assertEquals("socks-user:socks-pass", auth.get())
        assertEquals("example.test", connectHost.get())
        assertTrue(request.get().startsWith("GET /path HTTP/1.1"))
        assertFalse(request.get().contains("Proxy-Authorization"))
        assertTrue(response.contains("200 OK"))
        assertTrue(response.endsWith("OK"))
    }


    @Test
    fun authenticatedHttpProxyCarriesConnectTunnelWithoutExposingCredentialsLocally() {
        val upstream = ServerSocket(0)
        val received = java.util.concurrent.atomic.AtomicReference("")
        val payload = java.util.concurrent.atomic.AtomicReference("")
        val fake = Thread {
            upstream.accept().use { socket ->
                socket.soTimeout = 5_000
                received.set(readHeader(socket.getInputStream()))
                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                payload.set(String(readExact(socket.getInputStream(), 5)))
                socket.getOutputStream().write("WORLD".toByteArray())
                socket.getOutputStream().flush()
            }
            upstream.close()
        }.apply { isDaemon = true; start() }

        val relay = ProxyRelayServer(
            ProxyRelayConfig("HTTP", "127.0.0.1", upstream.localPort, "tunnel-user", "tunnel-pass")
        )
        val port = relay.start()
        val receivedFromTunnel = Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(
                "CONNECT secure.example:443 HTTP/1.1\r\nHost: secure.example:443\r\n\r\n".toByteArray()
            )
            client.getOutputStream().flush()
            val connectResponse = readHeader(client.getInputStream())
            client.getOutputStream().write("HELLO".toByteArray())
            client.getOutputStream().flush()
            val echo = String(readExact(client.getInputStream(), 5))
            connectResponse + echo
        }
        fake.join(5_000)
        relay.close()

        assertTrue(received.get().startsWith("CONNECT secure.example:443 HTTP/1.1"))
        assertTrue(received.get().contains("Proxy-Authorization: Basic dHVubmVsLXVzZXI6dHVubmVsLXBhc3M="))
        assertEquals("HELLO", payload.get())
        assertTrue(receivedFromTunnel.startsWith("HTTP/1.1 200 Connection Established"))
        assertTrue(receivedFromTunnel.endsWith("WORLD"))
    }


    @Test
    fun unavailableUpstreamReturns502InsteadOfDirectFallback() {
        val unavailable = ServerSocket(0)
        val port = unavailable.localPort
        unavailable.close()
        val relay = ProxyRelayServer(ProxyRelayConfig("HTTP", "127.0.0.1", port))
        val localPort = relay.start()
        val response = Socket("127.0.0.1", localPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(
                "GET http://must-not-go-direct.example/ HTTP/1.1\r\nHost: must-not-go-direct.example\r\n\r\n".toByteArray()
            )
            client.getOutputStream().flush()
            client.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        relay.close()
        assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
    }

    @Test
    fun onlyOneBrowserProfileCanHoldTheAppWideProxyLease() {
        val first = ProxySessionCoordinator.acquire("profile-a")
        assertNotNull(first)
        assertNull(ProxySessionCoordinator.acquire("profile-b"))
        assertEquals("profile-a", ProxySessionCoordinator.activeProfileId())
        assertTrue(ProxySessionCoordinator.release(first!!))
        val second = ProxySessionCoordinator.acquire("profile-b")
        assertNotNull(second)
        assertTrue(ProxySessionCoordinator.release(second!!))
    }

    private fun readHeader(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var state = 0
        while (output.size() < 64 * 1024) {
            val value = input.read()
            if (value < 0) throw AssertionError("EOF before HTTP headers")
            output.write(value)
            state = when (state) {
                0 -> if (value == 13) 1 else 0
                1 -> if (value == 10) 2 else if (value == 13) 1 else 0
                2 -> if (value == 13) 3 else 0
                3 -> if (value == 10) 4 else if (value == 13) 1 else 0
                else -> 4
            }
            if (state == 4) return output.toString("ISO-8859-1")
        }
        throw AssertionError("HTTP header too large")
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw AssertionError("Unexpected EOF")
            offset += read
        }
        return result
    }
}
