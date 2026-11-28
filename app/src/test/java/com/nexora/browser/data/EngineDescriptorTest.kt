package com.nexora.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 4.3b: the installed WebView — never the catalog — defines the engine. */
class EngineDescriptorTest {

    private val ua158 = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/158.0.8127.93 Mobile Safari/537.36"

    @Test fun webViewPackageReportsTheEngine() {
        val d = EngineDescriptor.fromRuntime("158.0.8127.93", null)
        assertEquals("chromium", d.family)
        assertEquals(158, d.major)
        assertEquals("158.0.8127.93", d.fullVersion)
        assertEquals(EngineDescriptor.TRUST_PACKAGE, d.fullVersionTrust)
        assertEquals("android", d.platform)
    }

    @Test fun packageWinsOverTheNativeUa() {
        val d = EngineDescriptor.fromRuntime("159.0.8154.33", ua158)
        assertEquals(159, d.major)
        assertEquals("159.0.8154.33", d.fullVersion)
    }

    @Test fun nativeUaFallbackProvesTheMajorOnly() {
        val d = EngineDescriptor.fromRuntime(null, ua158)
        assertEquals("chromium", d.family)
        assertEquals(158, d.major)
        // A UA token must never be presented as a verified full engine build.
        assertNull(d.fullVersion)
        assertEquals(EngineDescriptor.TRUST_UNKNOWN, d.fullVersionTrust)
    }

    @Test fun reducedUaTokenIsNotAFullBuild() {
        val d = EngineDescriptor.fromRuntime(null, "Mozilla/5.0 Chrome/158.0.0.0 Mobile Safari/537.36")
        assertEquals(158, d.major)
        assertNull(d.fullVersion)
    }

    @Test fun malformedPackageVersionFallsBackToTheUa() {
        val d = EngineDescriptor.fromRuntime("not-a-version", ua158)
        assertEquals(158, d.major)
        assertNull(d.fullVersion)
        assertEquals(EngineDescriptor.TRUST_UNKNOWN, d.fullVersionTrust)
    }

    @Test fun nothingAvailableStaysUnknown() {
        val d = EngineDescriptor.fromRuntime(null, "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36")
        assertNull(d.family)
        assertNull(d.major)
        assertNull(d.fullVersion)
        val empty = EngineDescriptor.fromRuntime("", "")
        assertNull(empty.major)
        val blank = EngineDescriptor.fromRuntime(null, null)
        assertNull(blank.major)
    }
}
