package com.nexora.browser.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockerEngineTest {
    @Test
    fun parsesSupportedDomainAndExceptionRules() {
        val report = ContentBlockerEngine.parseRules(
            listOf(
                "! comment",
                "||ads.example^",
                "@@||trusted.ads.example^",
                "example.org",
                "##.ad-banner",
                "/regex/",
                "||cdn.example^\$script"
            )
        )

        assertEquals(3, report.supportedLines)
        assertEquals(3, report.ignoredLines)
        assertEquals(3, report.rules.size)
    }

    @Test
    fun blocksExactHostsAndTheirSubdomainsButNotLookalikes() {
        val engine = ContentBlockerEngine()
        engine.update(
            ContentBlockerSnapshot(
                enabled = true,
                allowedSites = emptySet(),
                rawRules = listOf("||ads.example^")
            )
        )

        assertTrue(engine.shouldBlock("https://ads.example/banner.js", "https://news.example/"))
        assertTrue(engine.shouldBlock("https://cdn.ads.example/banner.js", "https://news.example/"))
        assertFalse(engine.shouldBlock("https://notads.example/banner.js", "https://news.example/"))
        assertFalse(engine.shouldBlock("https://ads.example.attacker.test/banner.js", "https://news.example/"))
    }

    @Test
    fun exceptionRuleTakesPrecedenceOverBlockRule() {
        val engine = ContentBlockerEngine()
        engine.update(
            ContentBlockerSnapshot(
                enabled = true,
                allowedSites = emptySet(),
                rawRules = listOf(
                    "||ads.example^",
                    "@@||trusted.ads.example^"
                )
            )
        )

        assertTrue(engine.shouldBlock("https://ads.example/banner.js", "https://news.example/"))
        assertFalse(engine.shouldBlock("https://trusted.ads.example/banner.js", "https://news.example/"))
    }

    @Test
    fun pathRuleOnlyMatchesItsPathPrefix() {
        val engine = ContentBlockerEngine()
        engine.update(
            ContentBlockerSnapshot(
                enabled = true,
                allowedSites = emptySet(),
                rawRules = listOf("||cdn.example/ads/")
            )
        )

        assertTrue(engine.shouldBlock("https://cdn.example/ads/slot.js", "https://news.example/"))
        assertFalse(engine.shouldBlock("https://cdn.example/content/app.js", "https://news.example/"))
    }

    @Test
    fun siteAllowlistDisablesRulesForThatTopLevelSiteAndSubdomains() {
        val engine = ContentBlockerEngine()
        engine.update(
            ContentBlockerSnapshot(
                enabled = true,
                allowedSites = setOf("publisher.example"),
                rawRules = listOf("||ads.example^")
            )
        )

        assertFalse(engine.shouldBlock("https://ads.example/banner.js", "https://publisher.example/article"))
        assertFalse(engine.shouldBlock("https://ads.example/banner.js", "https://www.publisher.example/article"))
        assertTrue(engine.shouldBlock("https://ads.example/banner.js", "https://other.example/article"))
    }

    @Test
    fun disabledEngineDoesNotBlock() {
        val engine = ContentBlockerEngine()
        engine.update(
            ContentBlockerSnapshot(
                enabled = false,
                allowedSites = emptySet(),
                rawRules = listOf("||ads.example^")
            )
        )

        assertFalse(engine.shouldBlock("https://ads.example/banner.js", "https://news.example/"))
        assertFalse(engine.isEnabled())
    }

    @Test
    fun normalizesInternationalizedAndTrailingDotHosts() {
        assertEquals("xn--bcher-kva.example", ContentBlockerEngine.normalizeHost("BÜCHER.example.") )
    }
    @Test
    fun evaluateReturnsTheMatchedBlockRuleForDiagnostics() {
        val engine = ContentBlockerEngine()
        engine.update(
            ContentBlockerSnapshot(
                enabled = true,
                allowedSites = emptySet(),
                rawRules = listOf("||ads.example^")
            )
        )

        val decision = engine.evaluate("https://ads.example/banner.js?user=secret", "https://news.example/")
        assertTrue(decision.blocked)
        assertEquals("ads.example", decision.matchedRule?.host)
        assertEquals(null, decision.matchedRule?.pathPrefix)
    }

    @Test
    fun evaluateDoesNotExposeAnExceptionAsABlockedDecision() {
        val engine = ContentBlockerEngine()
        engine.update(
            ContentBlockerSnapshot(
                enabled = true,
                allowedSites = emptySet(),
                rawRules = listOf(
                    "||ads.example^",
                    "@@||trusted.ads.example^"
                )
            )
        )

        val decision = engine.evaluate("https://trusted.ads.example/banner.js", "https://news.example/")
        assertFalse(decision.blocked)
        assertEquals(null, decision.matchedRule)
    }

}
