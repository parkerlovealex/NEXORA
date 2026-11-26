package com.nexora.browser.util

/**
 * Defines proxy-import identity without persisting or logging plaintext credentials.
 * An endpoint may legitimately host many authenticated sessions, so endpoint-only
 * matching would incorrectly collapse residential/mobile proxy session IDs.
 */
object ProxyImportIdentity {
    fun sameEndpoint(
        existingType: String,
        existingHost: String,
        existingPort: Int,
        incomingType: String,
        incomingHost: String,
        incomingPort: Int
    ): Boolean =
        existingType.equals(incomingType, ignoreCase = true) &&
            existingHost.equals(incomingHost, ignoreCase = true) &&
            existingPort == incomingPort

    fun sameCredentials(
        existingHasCredentials: Boolean,
        existingUsername: String,
        existingPassword: String,
        incomingHasCredentials: Boolean,
        incomingUsername: String,
        incomingPassword: String
    ): Boolean {
        if (existingHasCredentials != incomingHasCredentials) return false
        if (!existingHasCredentials) return true
        return existingUsername == incomingUsername && existingPassword == incomingPassword
    }
}
