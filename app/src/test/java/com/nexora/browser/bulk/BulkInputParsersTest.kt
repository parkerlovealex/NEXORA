package com.nexora.browser.bulk

import com.nexora.browser.util.ProxyImporter
import com.nexora.browser.util.UserAgentImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkInputParsersTest {
    @Test
    fun userAgentsDetectFamiliesAndDuplicates() {
        val rows = UserAgentImporter.parse(
            listOf(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            ).joinToString("\n")
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0].valid)
        assertEquals("Windows", rows[0].osFamily)
        assertEquals("Chrome", rows[0].browserFamily)
        assertEquals("iOS", rows[1].osFamily)
        assertEquals("Safari", rows[1].browserFamily)
        assertFalse(rows[2].valid)
    }

    @Test
    fun proxyImporterParsesSupportedFormsAndRejectsInvalidPort() {
        val rows = ProxyImporter.parse(
            listOf(
                "proxy.example:8080",
                "socks5://user:pass@127.0.0.1:1080",
                "host.example:3128:alice:secret",
                "socks4://127.0.0.1:9050",
                "[2001:db8::1]:1080",
                "http://bad.example:99999"
            ).joinToString("\n"),
            "HTTP"
        )
        assertEquals(6, rows.size)
        assertTrue(rows[0].valid)
        assertEquals("HTTP", rows[0].type)
        assertEquals("user", rows[1].username)
        assertEquals("pass", rows[1].password)
        assertEquals("secret", rows[2].password)
        assertEquals("SOCKS4", rows[3].type)
        assertEquals("2001:db8::1", rows[4].host)
        assertFalse(rows[5].valid)
    }
}
