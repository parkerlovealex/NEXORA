package com.nexora.browser.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Result of inspecting one pasted User-Agent line. */
data class ParsedUserAgent(
    val lineNumber: Int,
    val value: String,
    val osFamily: String,
    val browserFamily: String,
    val valid: Boolean,
    val warning: String = ""
)

object UserAgentImporter {
    fun parse(input: String): List<ParsedUserAgent> {
        val seen = mutableSetOf<String>()
        return input.lines().mapIndexedNotNull { index, raw ->
            val value = raw.trim()
            if (value.isEmpty()) return@mapIndexedNotNull null
            val key = value.lowercase(Locale.ROOT)
            val duplicate = !seen.add(key)
            val os = detectOs(value)
            val browser = detectBrowser(value)
            val valid = value.length in 20..2048 && value.contains("Mozilla/", ignoreCase = true) && !duplicate
            val warning = when {
                duplicate -> "Duplicate User-Agent"
                value.length !in 20..2048 -> "Length must be 20–2048 characters"
                !value.contains("Mozilla/", ignoreCase = true) -> "Unrecognized User-Agent format"
                os == "Unknown" || browser == "Unknown" -> "Could not identify OS or browser family"
                else -> ""
            }
            ParsedUserAgent(index + 1, value, os, browser, valid, warning)
        }
    }

    private fun detectOs(value: String): String = when {
        value.contains("Windows NT", true) -> "Windows"
        value.contains("iPhone", true) || value.contains("iPad", true) -> "iOS"
        value.contains("Android", true) -> "Android"
        value.contains("Macintosh", true) || value.contains("Mac OS", true) -> "macOS"
        value.contains("Linux", true) || value.contains("X11", true) -> "Linux"
        else -> "Unknown"
    }

    private fun detectBrowser(value: String): String = when {
        value.contains("Edg/", true) || value.contains("EdgiOS/", true) -> "Edge"
        value.contains("Firefox/", true) || value.contains("FxiOS/", true) -> "Firefox"
        value.contains("CriOS/", true) -> "Chrome iOS"
        value.contains("Chrome/", true) -> "Chrome"
        value.contains("Safari/", true) -> "Safari"
        else -> "Unknown"
    }
}

data class ParsedProxy(
    val lineNumber: Int,
    val type: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val valid: Boolean,
    val error: String = ""
) {
    val hasCredentials: Boolean get() = username.isNotEmpty() || password.isNotEmpty()
}

object ProxyImporter {
    private val allowedTypes = setOf("SOCKS5", "SOCKS4", "HTTP", "HTTPS")

    /**
     * Supported forms: host:port, host:port:user:pass, and scheme://user:pass@host:port.
     * Bracketed IPv6 is accepted in URI form and host:port form.
     */
    fun parse(input: String, defaultType: String): List<ParsedProxy> {
        val normalizedDefault = defaultType.uppercase(Locale.ROOT)
        return input.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            try {
                val parsed = if (line.contains("://")) parseUri(index + 1, line) else parseHostPort(index + 1, line, normalizedDefault)
                if (parsed.type !in allowedTypes) parsed.copy(valid = false, error = "Unsupported proxy protocol")
                else if (parsed.host.isBlank()) parsed.copy(valid = false, error = "Host is missing")
                else if (parsed.port !in 1..65535) parsed.copy(valid = false, error = "Port must be 1–65535")
                else if (parsed.host.any { it.isWhitespace() || it == '/' || it == '@' }) parsed.copy(valid = false, error = "Invalid host")
                else parsed
            } catch (e: Exception) {
                ParsedProxy(index + 1, normalizedDefault, "", 0, valid = false, error = e.message ?: "Invalid proxy format")
            }
        }
    }

    private fun parseUri(lineNumber: Int, line: String): ParsedProxy {
        val uri = URI(line)
        val type = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "socks5", "socks" -> "SOCKS5"
            "socks4" -> "SOCKS4"
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> uri.scheme?.uppercase(Locale.ROOT).orEmpty()
        }
        val rawUserInfo = uri.rawUserInfo.orEmpty()
        val split = rawUserInfo.split(":", limit = 2)
        val username = split.getOrNull(0)?.let { decode(it) }.orEmpty()
        val password = split.getOrNull(1)?.let { decode(it) }.orEmpty()
        return ParsedProxy(
            lineNumber = lineNumber,
            type = type,
            host = uri.host.orEmpty(),
            port = uri.port,
            username = username,
            password = password,
            valid = uri.host != null && uri.port in 1..65535,
            error = if (uri.host == null) "Invalid host or URI" else if (uri.port !in 1..65535) "Port must be 1–65535" else ""
        )
    }

    private fun parseHostPort(lineNumber: Int, line: String, defaultType: String): ParsedProxy {
        if (line.startsWith("[")) {
            val match = Regex("^\\[([^]]+)]:(\\d+)$").matchEntire(line)
                ?: throw IllegalArgumentException("IPv6 must use [address]:port or a full proxy URI")
            return ParsedProxy(lineNumber, defaultType, match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 0, valid = true)
        }
        val parts = line.split(":", limit = 4)
        return when (parts.size) {
            2 -> ParsedProxy(lineNumber, defaultType, parts[0], parts[1].toIntOrNull() ?: 0, valid = true)
            4 -> ParsedProxy(
                lineNumber, defaultType, parts[0], parts[1].toIntOrNull() ?: 0,
                parts[2], parts[3], valid = true
            )
            else -> throw IllegalArgumentException("Expected host:port or host:port:username:password")
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
