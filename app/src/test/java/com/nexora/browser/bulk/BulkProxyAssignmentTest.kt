package com.nexora.browser.bulk

import com.nexora.browser.data.ProxyEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkProxyAssignmentTest {
    @Test
    fun assignsSavedProxiesToProfilesInOrderWithoutReusingGlobalProxy() {
        val draft = BulkProfileDraft().apply {
            count = 3
            perProfiles.clear()
            perProfiles += PerProfileDraft("Profile 1")
            perProfiles += PerProfileDraft("Profile 2")
            perProfiles += PerProfileDraft("Profile 3")
        }
        val proxies = listOf(
            ProxyEntry("p1", "One", "HTTP", "one.example", 8080, false, "", 1L),
            ProxyEntry("p2", "Two", "HTTP", "two.example", 8080, false, "", 2L)
        )

        val result = draft.assignSavedProxiesInOrder(proxies)

        assertEquals("p1", draft.perProfiles[0].proxySelection)
        assertEquals("p2", draft.perProfiles[1].proxySelection)
        assertEquals("NONE", draft.perProfiles[2].proxySelection)
        assertEquals(2, result.assignedProfiles)
        assertEquals(1, result.directProfiles)
        assertEquals(0, result.unsupportedAssignments)
        assertEquals(0, result.unusedSavedProxies)
    }

    @Test
    fun reportsUnsupportedAndUnusedSavedProxyEntries() {
        val draft = BulkProfileDraft().apply {
            count = 1
            perProfiles.clear()
            perProfiles += PerProfileDraft("Profile 1")
        }
        val proxies = listOf(
            ProxyEntry("socks4", "Imported SOCKS4", "SOCKS4", "proxy.example", 1080, false, "", 1L),
            ProxyEntry("plain", "Plain", "HTTP", "plain.example", 8080, false, "", 2L)
        )

        val result = draft.assignSavedProxiesInOrder(proxies)

        assertEquals("socks4", draft.perProfiles.single().proxySelection)
        assertEquals(1, result.unsupportedAssignments)
        assertEquals(1, result.unusedSavedProxies)
    }

    @Test
    fun authenticatedHttpHttpsAndSocks5EntriesCanEachBeAssigned() {
        val types = listOf("HTTP", "HTTPS", "SOCKS5")
        val draft = BulkProfileDraft().apply { count = types.size }
        val entries = types.map { type ->
            ProxyEntry(type, type, type, "proxy.example", 8080, true, "fixture", 1L)
        }

        val result = draft.assignSavedProxiesInOrder(entries)

        assertEquals(types, draft.perProfiles.map { it.proxySelection })
        assertEquals(types.size, result.assignedProfiles)
        assertEquals(0, result.directProfiles)
        assertEquals(0, result.unsupportedAssignments)
        assertEquals(0, result.unusedSavedProxies)
    }

    @Test
    fun assignsTenAuthenticatedSessionsOneToOneWithoutReusingAnEntry() {
        val draft = BulkProfileDraft().apply {
            count = 10
            perProfiles.clear()
            repeat(10) { index -> perProfiles += PerProfileDraft("Profile ${index + 1}") }
        }
        val proxies = (1..10).map { index ->
            ProxyEntry(
                id = "session-$index",
                name = "NodeMaven session $index",
                type = "SOCKS5",
                host = "gate.nodemaven.com",
                port = 1080,
                hasCredentials = true,
                encryptedCredentials = "encrypted-$index",
                createdAt = index.toLong()
            )
        }

        val result = draft.assignSavedProxiesInOrder(proxies)
        val assignments = draft.perProfiles.map { it.proxySelection }

        assertEquals(10, result.assignedProfiles)
        assertEquals(0, result.directProfiles)
        assertEquals(0, result.unsupportedAssignments)
        assertEquals(0, result.unusedSavedProxies)
        assertEquals((1..10).map { "session-$it" }, assignments)
        assertEquals(10, assignments.toSet().size)
    }

}
