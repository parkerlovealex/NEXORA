package com.nexora.browser.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyImportIdentityTest {
    @Test
    fun sameEndpointWithDifferentSessionCredentialsIsNotADuplicate() {
        assertTrue(
            ProxyImportIdentity.sameEndpoint(
                "HTTP", "gate.nodemaven.com", 8080,
                "HTTP", "GATE.NODEMAVEN.COM", 8080
            )
        )
        assertFalse(
            ProxyImportIdentity.sameCredentials(
                true, "user-country-gb-sid-111", "password",
                true, "user-country-gb-sid-222", "password"
            )
        )
    }

    @Test
    fun exactEndpointAndCredentialsAreADuplicate() {
        assertTrue(
            ProxyImportIdentity.sameEndpoint(
                "http", "gate.nodemaven.com", 8080,
                "HTTP", "gate.nodemaven.com", 8080
            )
        )
        assertTrue(
            ProxyImportIdentity.sameCredentials(
                true, "user-country-gb-sid-111", "password",
                true, "user-country-gb-sid-111", "password"
            )
        )
    }

    @Test
    fun authenticatedAndUnauthenticatedEntriesAreDistinct() {
        assertFalse(
            ProxyImportIdentity.sameCredentials(
                false, "", "",
                true, "username", "password"
            )
        )
    }
}
