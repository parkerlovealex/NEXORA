package com.nexora.browser.data

/** A locally saved proxy. Credentials are encrypted and never kept in this model in plaintext. */
data class ProxyEntry(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val hasCredentials: Boolean,
    val encryptedCredentials: String,
    val createdAt: Long
) {
    val isValid: Boolean
        get() = host.isNotBlank() && port in 1..65535 && type in SUPPORTED_IMPORT_TYPES

    /** Supported through NEXORA's loopback relay, not by WebView's native proxy auth. */
    val isSupportedByProxyRelay: Boolean
        get() = type in RELAY_SUPPORTED_TYPES

    companion object {
        val SUPPORTED_IMPORT_TYPES = setOf("SOCKS5", "SOCKS4", "HTTP", "HTTPS")
        val RELAY_SUPPORTED_TYPES = setOf("SOCKS5", "HTTP", "HTTPS")
    }
}
