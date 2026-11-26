package com.nexora.browser.util

import java.util.UUID

/**
 * WebView's ProxyController is app-wide rather than per-WebView. This coordinator
 * enforces one active BrowserActivity network identity at a time, preventing one
 * profile from changing another profile's global proxy route.
 */
object ProxySessionCoordinator {

    data class Lease internal constructor(
        val token: String,
        val profileId: String
    )

    private var activeLease: Lease? = null

    @Synchronized
    fun acquire(profileId: String): Lease? {
        if (profileId.isBlank() || activeLease != null) return null
        return Lease(UUID.randomUUID().toString(), profileId).also { activeLease = it }
    }

    @Synchronized
    fun release(lease: Lease): Boolean {
        if (activeLease?.token != lease.token) return false
        activeLease = null
        return true
    }

    @Synchronized
    fun activeProfileId(): String? = activeLease?.profileId
}
