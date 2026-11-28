package com.nexora.browser.util

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Upstream proxy credentials are held only in memory. toString deliberately redacts them.
 */
class ProxyRelayConfig(
    val type: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    fun validate(): String? {
        if (type !in SUPPORTED_TYPES) return "Unsupported proxy protocol: $type"
        if (host.isBlank() || host.any { it.isWhitespace() || it == '\r' || it == '\n' || it in "/?#@\\" }) {
            return "Proxy host is invalid"
        }
        if (port !in 1..65535) return "Proxy port is invalid"
        if (username.length > 255 || password.length > 255) return "Proxy credentials exceed protocol limits"
        if (type in setOf("HTTP", "HTTPS") && username.contains(':')) {
            return "HTTP proxy usernames cannot contain a colon"
        }
        if (username.any { it == '\r' || it == '\n' } || password.any { it == '\r' || it == '\n' }) {
            return "Proxy credentials contain invalid characters"
        }
        return null
    }

    internal fun hasCredentials(): Boolean = username.isNotEmpty() || password.isNotEmpty()

    internal fun basicAuthorization(): String? {
        if (!hasCredentials()) return null
        val token = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8)
        )
        return "Basic $token"
    }

    override fun toString(): String =
        "ProxyRelayConfig(type=$type, host=$host, port=$port, credentials=${if (hasCredentials()) "redacted" else "none"})"

    companion object {
        val SUPPORTED_TYPES = setOf("HTTP", "HTTPS", "SOCKS5")
    }
}

/**
 * A loopback-only HTTP proxy for WebView. It forwards requests through one upstream
 * HTTP, HTTPS or SOCKS5 proxy and never falls back to a direct connection.
 *
 * WebView's ProxyController is app-wide, so BrowserActivity must hold the exclusive
 * ProxySessionCoordinator lease while this relay is active.
 */
class ProxyRelayServer(private val upstream: ProxyRelayConfig) : Closeable {

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(
        2, 32, 30L, TimeUnit.SECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "NEXORA-ProxyRelay-Worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "NEXORA-ProxyRelay-Deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    @Volatile private var server: ServerSocket? = null
    @Volatile var localPort: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(): Int {
        if (closed.get()) throw IllegalStateException("Proxy relay has already been closed")
        if (running.get()) return localPort
        upstream.validate()?.let { throw IllegalArgumentException(it) }

        val listener = ServerSocket()
        listener.reuseAddress = false
        listener.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 32)
        server = listener
        localPort = listener.localPort
        running.set(true)

        Thread({ acceptLoop(listener) }, "NEXORA-ProxyRelay-Accept").apply {
            isDaemon = true
            start()
        }
        return localPort
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get()) {
            val client = try {
                listener.accept()
            } catch (_: Exception) {
                if (running.get()) continue else break
            }
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                activeSockets.add(client)
                if (!running.get()) throw IOException("Relay closed")
                workers.execute { handleClient(client) }
            } catch (_: RejectedExecutionException) {
                activeSockets.remove(client)
                closeQuietly(client)
            } catch (_: Exception) {
                activeSockets.remove(client)
                closeQuietly(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstreamSocket: Socket? = null
        try {
            val headerBytes = readHeaderBlock(client.getInputStream())
                ?: throw IOException("Client closed before sending an HTTP request")
            val request = parseHeaderBlock(headerBytes)
            val method = request.requestLine.substringBefore(' ').uppercase()
            if (method == "CONNECT") {
                val targetText = request.requestLine.split(' ').getOrNull(1)
                    ?: throw IOException("CONNECT target is missing")
                val target = parseAuthority(targetText, 443)
                upstreamSocket = openConnectTunnel(target.host, target.port, client)
                if (upstreamSocket != null) {
                    activeSockets.add(upstreamSocket)
                    tunnel(client, upstreamSocket)
                }
            } else {
                val target = parseRequestTarget(request)
                upstreamSocket = openForwardSocket(target.host, target.port)
                activeSockets.add(upstreamSocket)
                forwardHttpRequest(client, upstreamSocket, request, target)
            }
        } catch (error: Exception) {
            writeError(client, 502, "Bad Gateway", error.message ?: "Upstream proxy connection failed")
        } finally {
            upstreamSocket?.let {
                activeSockets.remove(it)
                closeQuietly(it)
            }
            activeSockets.remove(client)
            closeQuietly(client)
        }
    }

    private fun openConnectTunnel(host: String, port: Int, client: Socket): Socket? {
        return when (upstream.type) {
            "SOCKS5" -> {
                val socket = connectToUpstream()
                try {
                    socks5Connect(socket, host, port)
                    client.getOutputStream().write(
                        "HTTP/1.1 200 Connection Established\r\nProxy-Agent: NEXORA\r\n\r\n"
                            .toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    client.getOutputStream().flush()
                    socket
                } catch (error: Exception) {
                    closeQuietly(socket)
                    throw error
                }
            }
            "HTTP", "HTTPS" -> {
                val socket = connectToUpstream()
                try {
                    val authority = formatAuthority(host, port)
                    val headers = StringBuilder()
                        .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                        .append("Host: ").append(authority).append("\r\n")
                        .append("Proxy-Connection: keep-alive\r\n")
                    upstream.basicAuthorization()?.let {
                        headers.append("Proxy-Authorization: ").append(it).append("\r\n")
                    }
                    headers.append("\r\n")
                    socket.getOutputStream().write(headers.toString().toByteArray(StandardCharsets.ISO_8859_1))
                    socket.getOutputStream().flush()

                    val response = readHeaderBlock(socket.getInputStream())
                        ?: throw IOException("Upstream proxy closed during CONNECT")
                    val status = parseStatusCode(response)
                    if (status !in 200..299) {
                        client.getOutputStream().write(response)
                        client.getOutputStream().flush()
                        socket.getInputStream().copyTo(client.getOutputStream())
                        closeQuietly(socket)
                        null
                    } else {
                        client.getOutputStream().write(
                            "HTTP/1.1 200 Connection Established\r\nProxy-Agent: NEXORA\r\n\r\n"
                                .toByteArray(StandardCharsets.ISO_8859_1)
                        )
                        client.getOutputStream().flush()
                        socket
                    }
                } catch (error: Exception) {
                    closeQuietly(socket)
                    throw error
                }
            }
            else -> throw IOException("Unsupported upstream proxy protocol")
        }
    }

    private fun openForwardSocket(targetHost: String, targetPort: Int): Socket {
        val socket = connectToUpstream()
        try {
            if (upstream.type == "SOCKS5") socks5Connect(socket, targetHost, targetPort)
            return socket
        } catch (error: Exception) {
            closeQuietly(socket)
            throw error
        }
    }

    private fun connectToUpstream(): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        val upstreamHost = upstream.host.removePrefix("[").removeSuffix("]")
        try {
            raw.connect(InetSocketAddress(upstreamHost, upstream.port), CONNECT_TIMEOUT_MS)
        } catch (error: Exception) {
            closeQuietly(raw)
            throw error
        }
        raw.soTimeout = SOCKET_TIMEOUT_MS
        if (upstream.type != "HTTPS") return raw

        return try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = sslFactory.createSocket(raw, upstreamHost, upstream.port, true) as SSLSocket
            tls.soTimeout = SOCKET_TIMEOUT_MS
            val sslParameters = tls.sslParameters
            sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = sslParameters
            tls.startHandshake()
            tls
        } catch (error: Exception) {
            closeQuietly(raw)
            throw IOException("TLS connection to HTTPS proxy failed: ${error.message ?: "handshake error"}")
        }
    }

    private fun socks5Connect(socket: Socket, targetHost: String, targetPort: Int) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        val wantsAuth = upstream.hasCredentials()
        output.write(if (wantsAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()

        val greeting = readExact(input, 2)
        if (greeting[0].toInt() != 5) throw IOException("Invalid SOCKS5 greeting")
        when (greeting[1].toInt() and 0xff) {
            0 -> Unit
            2 -> {
                if (!wantsAuth) throw IOException("SOCKS5 proxy requires credentials")
                val user = upstream.username.toByteArray(StandardCharsets.UTF_8)
                val pass = upstream.password.toByteArray(StandardCharsets.UTF_8)
                if (user.size > 255 || pass.size > 255) throw IOException("SOCKS5 credentials are too long")
                output.write(byteArrayOf(1, user.size.toByte()))
                output.write(user)
                output.write(byteArrayOf(pass.size.toByte()))
                output.write(pass)
                output.flush()
                val authReply = readExact(input, 2)
                if (authReply[0].toInt() != 1 || authReply[1].toInt() != 0) {
                    throw IOException("SOCKS5 authentication failed")
                }
            }
            0xff -> throw IOException("SOCKS5 proxy rejected available authentication methods")
            else -> throw IOException("SOCKS5 proxy selected an unsupported authentication method")
        }

        val targetAddress = if (targetHost.contains(':')) {
            try { InetAddress.getByName(targetHost).address.takeIf { it.size == 16 } }
            catch (_: Exception) { null }
        } else null
        if (targetAddress != null) {
            output.write(byteArrayOf(5, 1, 0, 4))
            output.write(targetAddress)
        } else {
            val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
            if (hostBytes.isEmpty() || hostBytes.size > 255) {
                throw IOException("Destination hostname is invalid for SOCKS5")
            }
            output.write(byteArrayOf(5, 1, 0, 3, hostBytes.size.toByte()))
            output.write(hostBytes)
        }
        output.write(byteArrayOf((targetPort ushr 8).toByte(), targetPort.toByte()))
        output.flush()

        val reply = readExact(input, 4)
        if (reply[0].toInt() != 5) throw IOException("Invalid SOCKS5 connect response")
        if (reply[1].toInt() != 0) throw IOException("SOCKS5 connect failed with status ${reply[1].toInt() and 0xff}")
        when (reply[3].toInt() and 0xff) {
            1 -> readExact(input, 4)
            3 -> {
                val size = readExact(input, 1)[0].toInt() and 0xff
                readExact(input, size)
            }
            4 -> readExact(input, 16)
            else -> throw IOException("Invalid SOCKS5 bound address")
        }
        readExact(input, 2) // bound port
    }

    private fun forwardHttpRequest(
        client: Socket,
        upstreamSocket: Socket,
        request: ParsedHeaderBlock,
        target: RequestTarget
    ) {
        val method = request.requestLine.substringBefore(' ').uppercase()
        val expectContinue = request.headers.any {
            it.name.equals("Expect", ignoreCase = true) &&
                it.value.equals("100-continue", ignoreCase = true)
        }
        val upgradeRequested =
            request.headers.any { it.name.equals("Upgrade", ignoreCase = true) } &&
                request.headers.any {
                    it.name.equals("Connection", ignoreCase = true) &&
                        it.value.contains("upgrade", ignoreCase = true)
                }
        val upstreamRequestLine = when (upstream.type) {
            "SOCKS5" -> "$method ${target.originForm} ${request.requestLine.substringAfterLast(' ')}"
            else -> {
                val absolute = target.absoluteForm
                "$method $absolute ${request.requestLine.substringAfterLast(' ')}"
            }
        }

        val headersOut = StringBuilder().append(upstreamRequestLine).append("\r\n")
        request.headers.forEach { header ->
            when (header.name.lowercase()) {
                "proxy-authorization", "proxy-connection", "connection", "expect" -> Unit
                else -> headersOut.append(header.rawLine).append("\r\n")
            }
        }
        headersOut.append(if (upgradeRequested) "Connection: Upgrade\r\n" else "Connection: close\r\n")
        if (upstream.type == "HTTP" || upstream.type == "HTTPS") {
            upstream.basicAuthorization()?.let {
                headersOut.append("Proxy-Authorization: ").append(it).append("\r\n")
            }
        }
        headersOut.append("\r\n")

        val upstreamOut = upstreamSocket.getOutputStream()
        upstreamOut.write(headersOut.toString().toByteArray(StandardCharsets.ISO_8859_1))
        upstreamOut.flush()

        if (expectContinue) {
            client.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            client.getOutputStream().flush()
        }

        val transferEncodings = request.headers.filter {
            it.name.equals("Transfer-Encoding", ignoreCase = true)
        }.map { it.value }
        if (transferEncodings.size > 1) throw IOException("Multiple Transfer-Encoding headers are unsupported")
        val transferEncoding = transferEncodings.singleOrNull().orEmpty()
        val contentLengthHeaders = request.headers.filter {
            it.name.equals("Content-Length", ignoreCase = true)
        }.map {
            it.value.trim().toLongOrNull() ?: throw IOException("Invalid Content-Length")
        }.distinct()
        if (transferEncoding.isNotBlank() && contentLengthHeaders.isNotEmpty()) {
            throw IOException("Transfer-Encoding and Content-Length cannot be combined")
        }
        if (contentLengthHeaders.size > 1) throw IOException("Conflicting Content-Length headers")
        val contentLength = contentLengthHeaders.firstOrNull() ?: 0L
        if (contentLength < 0) throw IOException("Invalid Content-Length")

        when {
            transferEncoding.contains("chunked", ignoreCase = true) ->
                copyChunkedBody(client.getInputStream(), upstreamOut)
            transferEncoding.isNotBlank() ->
                throw IOException("Unsupported request Transfer-Encoding")
            contentLength > 0 -> copyExactly(client.getInputStream(), upstreamOut, contentLength)
        }
        upstreamOut.flush()

        if (upgradeRequested) {
            val responseHeaders = readHeaderBlock(upstreamSocket.getInputStream())
                ?: throw IOException("Upstream closed during protocol upgrade")
            client.getOutputStream().write(responseHeaders)
            client.getOutputStream().flush()
            if (parseStatusCode(responseHeaders) == 101) {
                tunnel(client, upstreamSocket)
            } else {
                upstreamSocket.getInputStream().copyTo(client.getOutputStream())
                client.getOutputStream().flush()
            }
        } else {
            upstreamSocket.getInputStream().copyTo(client.getOutputStream())
            client.getOutputStream().flush()
        }
    }

    private fun parseRequestTarget(request: ParsedHeaderBlock): RequestTarget {
        val parts = request.requestLine.split(' ', limit = 3)
        if (parts.size != 3) throw IOException("Malformed HTTP request line")
        val rawTarget = parts[1]
        val uri = try {
            if (rawTarget.startsWith("http://", true) || rawTarget.startsWith("https://", true)) {
                URI(rawTarget)
            } else null
        } catch (_: Exception) {
            throw IOException("Malformed absolute request URL")
        }

        val host: String
        val port: Int
        val origin: String
        if (uri != null) {
            host = (uri.host ?: throw IOException("Request URL has no hostname"))
                .removePrefix("[").removeSuffix("]")
            if (uri.rawUserInfo != null) throw IOException("Destination URL userinfo is not allowed")
            val scheme = uri.scheme.lowercase()
            port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
            val path = uri.rawPath.takeIf { it.isNotBlank() } ?: "/"
            origin = path + (uri.rawQuery?.let { "?$it" } ?: "")
        } else {
            val hostHeader = request.headers.firstOrNull {
                it.name.equals("Host", ignoreCase = true)
            }?.value ?: throw IOException("HTTP Host header is missing")
            val authority = parseAuthority(hostHeader, 80)
            host = authority.host
            port = authority.port
            origin = if (rawTarget.startsWith("/")) rawTarget else "/"
        }

        if (host.isBlank() || port !in 1..65535) throw IOException("Request destination is invalid")
        val authority = formatAuthority(host, port)
        val absolute = if (uri != null) rawTarget else "http://$authority$origin"
        return RequestTarget(host, port, origin, absolute)
    }

    private fun parseAuthority(value: String, defaultPort: Int): Authority {
        val text = value.trim()
        if (text.isBlank() || text.any { it.isWhitespace() || it == '\r' || it == '\n' || it == '/' || it == '@' }) {
            throw IOException("Destination authority is invalid")
        }
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close <= 1) throw IOException("Invalid IPv6 authority")
            val host = text.substring(1, close)
            val suffix = text.substring(close + 1)
            val port = if (suffix.isBlank()) defaultPort else {
                if (!suffix.startsWith(":")) throw IOException("Invalid IPv6 port")
                suffix.substring(1).toIntOrNull() ?: throw IOException("Invalid destination port")
            }
            return Authority(host, port)
        }
        val colon = text.lastIndexOf(':')
        if (colon > 0 && text.indexOf(':') == colon) {
            val host = text.substring(0, colon)
            val port = text.substring(colon + 1).toIntOrNull() ?: throw IOException("Invalid destination port")
            return Authority(host, port)
        }
        return Authority(text, defaultPort)
    }

    private fun tunnel(client: Socket, upstreamSocket: Socket) {
        val lifetime = RelayTunnelLifetime(System.nanoTime())
        client.soTimeout = RelayTunnelLifetime.MAX_READ_MS
        upstreamSocket.soTimeout = RelayTunnelLifetime.MAX_READ_MS
        val expiry = deadlines.scheduleWithFixedDelay({
            if (lifetime.expired(System.nanoTime())) {
                closeQuietly(client)
                closeQuietly(upstreamSocket)
            }
        }, 5, 5, TimeUnit.SECONDS)
        try {
            val upload = Thread({
                try {
                    copyTunnel(client, upstreamSocket, lifetime)
                } catch (_: IOException) {
                    // Closing either socket interrupts pending I/O.
                } finally {
                    runCatching { upstreamSocket.shutdownOutput() }
                }
            }, "NEXORA-ProxyRelay-Upstream").apply { isDaemon = true; start() }
            try {
                copyTunnel(upstreamSocket, client, lifetime)
            } catch (_: IOException) {
                // The caller closes the sockets when forwarding ends.
            } finally {
                runCatching { client.shutdownOutput() }
                try { upload.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        } finally {
            expiry.cancel(false)
        }
    }

    private fun copyTunnel(source: Socket, destination: Socket, lifetime: RelayTunnelLifetime) {
        val input = source.getInputStream()
        val output = destination.getOutputStream()
        val block = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(block)
            if (count < 0) break
            output.write(block, 0, count)
            lifetime.recordTraffic(System.nanoTime())
        }
        output.flush()
    }

    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        var state = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else throw IOException("Incomplete HTTP headers")
            output.write(value)
            state = when (state) {
                0 -> if (value == 13) 1 else 0
                1 -> if (value == 10) 2 else if (value == 13) 1 else 0
                2 -> if (value == 13) 3 else 0
                3 -> if (value == 10) 4 else if (value == 13) 1 else 0
                else -> 4
            }
            if (state == 4) return output.toByteArray()
        }
        throw IOException("HTTP headers exceed the configured size limit")
    }

    private fun parseHeaderBlock(bytes: ByteArray): ParsedHeaderBlock {
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        if (requestLine.isBlank()) throw IOException("HTTP request line is empty")
        val headers = lines.drop(1).filter { it.isNotEmpty() }.map { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) throw IOException("Malformed HTTP header")
            HeaderLine(line.substring(0, separator).trim(), line.substring(separator + 1).trim(), line)
        }
        return ParsedHeaderBlock(requestLine, headers)
    }

    private fun parseStatusCode(bytes: ByteArray): Int {
        val line = String(bytes, StandardCharsets.ISO_8859_1).substringBefore("\r\n")
        return line.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw IOException("Unexpected EOF in proxy handshake")
            offset += read
        }
        return result
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Request body ended early")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyChunkedBody(input: InputStream, output: OutputStream) {
        while (true) {
            val line = readCrlfLine(input)
            output.write(line)
            val sizeText = String(line, 0, line.size - 2, StandardCharsets.ISO_8859_1)
                .substringBefore(';').trim()
            val size = sizeText.toLongOrNull(16) ?: throw IOException("Invalid chunk size")
            if (size < 0) throw IOException("Invalid chunk size")
            if (size == 0L) {
                while (true) {
                    val trailer = readCrlfLine(input)
                    output.write(trailer)
                    if (trailer.size == 2) return
                }
            }
            copyExactly(input, output, size + 2L) // chunk bytes and trailing CRLF
        }
    }

    private fun readCrlfLine(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (output.size() < MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) throw IOException("Unexpected EOF in chunked request body")
            output.write(value)
            if (previous == 13 && value == 10) return output.toByteArray()
            previous = value
        }
        throw IOException("Chunk line exceeds the configured size limit")
    }

    private fun writeError(client: Socket, status: Int, reason: String, detail: String) {
        try {
            val safe = detail.replace('\r', ' ').replace('\n', ' ').take(240)
            val body = "$reason: $safe".toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().write(header.toByteArray(StandardCharsets.ISO_8859_1))
            client.getOutputStream().write(body)
            client.getOutputStream().flush()
        } catch (_: Exception) {
            // The client may already have disconnected.
        }
    }

    private fun formatAuthority(host: String, port: Int): String {
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$formattedHost:$port"
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: Exception) {}
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        activeSockets.toList().forEach(::closeQuietly)
        activeSockets.clear()
        workers.shutdownNow()
        deadlines.shutdownNow()
        localPort = -1
    }

    private data class HeaderLine(val name: String, val value: String, val rawLine: String)
    private data class ParsedHeaderBlock(val requestLine: String, val headers: List<HeaderLine>)
    private data class RequestTarget(
        val host: String,
        val port: Int,
        val originForm: String,
        val absoluteForm: String
    )
    private data class Authority(val host: String, val port: Int)

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
    }
}

/** Both directions share an idle deadline. Continuous traffic still has a finite lifetime. */
internal class RelayTunnelLifetime(private val openedAt: Long) {
    private val lastTraffic = AtomicLong(openedAt)

    fun recordTraffic(now: Long) { lastTraffic.set(now) }

    fun expired(now: Long): Boolean =
        now - openedAt >= TimeUnit.MILLISECONDS.toNanos(MAX_READ_MS.toLong()) ||
            now - lastTraffic.get() >= TimeUnit.MINUTES.toNanos(5)

    companion object {
        const val MAX_READ_MS = 60 * 60 * 1000
    }
}
