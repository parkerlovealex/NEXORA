package com.nexora.browser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserProfileProxyReferenceTest {
    @Test
    fun proxyLibraryReferenceSurvivesProfileJsonRoundTrip() {
        val original = BrowserProfile(
            id = "profile-1",
            name = "Research",
            deviceId = "pixel-test",
            osVersion = "Android 15",
            ramGb = 8,
            storageGb = 128,
            userAgent = "Mozilla/5.0",
            proxyType = "HTTP",
            proxyHost = "proxy.example",
            proxyPort = 8080,
            proxyEntryId = "proxy-entry-123"
        )
        val restored = BrowserProfile.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original.proxyEntryId, restored.proxyEntryId)
        assertEquals(original.proxyHost, restored.proxyHost)
        assertEquals(original.proxyPort, restored.proxyPort)
    }

    @Test
    fun oldProfileJsonDefaultsToManualProxyWithoutReference() {
        val oldJson = JSONObject()
            .put("id", "legacy")
            .put("name", "Legacy")
            .put("deviceId", "pixel-test")
            .put("userAgent", "Mozilla/5.0")
            .put("proxyType", "HTTP")
            .put("proxyHost", "old-proxy.example")
            .put("proxyPort", 8080)
        val restored = BrowserProfile.fromJson(oldJson)
        assertEquals("", restored.proxyEntryId)
        assertEquals("old-proxy.example", restored.proxyHost)
    }
}
