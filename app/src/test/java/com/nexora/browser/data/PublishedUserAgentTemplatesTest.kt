package com.nexora.browser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4.2B regression tests for the canonical UA template reader.
 * Fixtures are the real bundled generated assets and the canonical web catalog.
 */
class PublishedUserAgentTemplatesTest {

    private fun file(vararg candidates: String): String =
        candidates.map { File(it) }.first { it.isFile }.readText()

    private val templates = file(
        "src/main/assets/ua_templates.json", "app/src/main/assets/ua_templates.json"
    )
    private val slotContext = file(
        "src/main/assets/ua_slot_context.json", "app/src/main/assets/ua_slot_context.json"
    )

    private fun verified(catalog: JSONObject): JSONObject {
        val records = catalog.getJSONArray("records")
        return (0 until records.length()).map { records.getJSONObject(it) }
            .single { it.getString("status") == "verified" }
    }

    @Test fun publishedRecordLoadsWithExactFixtureAndFullEvidence() {
        val entries = PublishedUserAgentTemplates.read(templates, slotContext)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("facebook-android-fb4a-observed-2026-v1", entry.id)
        assertEquals("facebook", entry.appId)
        assertEquals("Facebook", entry.displayName)
        assertEquals("ANDROID", entry.targetOs)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/158.0.0.0 Mobile Safari/537.36 " +
                "[FB_IAB/FB4A;FBAV/588.0.0.42.55;IABMV/1;]",
            entry.userAgent
        )
        assertTrue(entry.verified)
        assertEquals("verified", entry.status)
        assertEquals("2026.11.28.2", entry.catalogReleaseId)
        assertEquals("2026.11.28.1", entry.catalogDataVersion)
        // The complete canonical evidence array survives: all three entries.
        assertEquals(3, entry.evidence.size)
        assertEquals(3, entry.evidenceCount)
        assertEquals(
            setOf("avidtrak.com", "hackernoon.com", "goodbox.ec"),
            entry.evidence.mapNotNull { it.sourceUrl }.map { java.net.URI(it).host.removePrefix("servientrega.") }.toSet()
        )
    }

    @Test fun candidateRecordsStayHidden() {
        val catalog = JSONObject(templates)
        assertEquals(12, catalog.getJSONArray("records").length())
        assertEquals(1, PublishedUserAgentTemplates.read(templates, slotContext).size)
    }

    @Test fun versionMismatchIsRejected() {
        val skewedVersion = JSONObject(slotContext).put("deviceCatalogVersion", "2026.11.24.1")
        assertTrue(PublishedUserAgentTemplates.read(templates, skewedVersion.toString()).isEmpty())
        val skewedRelease = JSONObject(slotContext).put("catalogReleaseId", "2026.11.28.1")
        assertTrue(PublishedUserAgentTemplates.read(templates, skewedRelease.toString()).isEmpty())
    }

    @Test fun missingOrMalformedDocumentsReturnEmpty() {
        assertTrue(PublishedUserAgentTemplates.read(null, null).isEmpty())
        assertTrue(PublishedUserAgentTemplates.read("", "").isEmpty())
        assertTrue(PublishedUserAgentTemplates.read("{}", "{}").isEmpty())
        assertTrue(PublishedUserAgentTemplates.read("not json", slotContext).isEmpty())
    }

    @Test fun unresolvableSlotSkipsTheRecord() {
        val context = JSONObject(slotContext)
        context.getJSONObject("apps").remove("facebook")
        assertTrue(PublishedUserAgentTemplates.read(templates, context.toString()).isEmpty())
    }

    @Test fun tamperedEvidenceIsRejected() {
        fun rejected(edit: (JSONObject) -> Unit): Boolean {
            val catalog = JSONObject(templates)
            edit(verified(catalog))
            return PublishedUserAgentTemplates.read(catalog.toString(), slotContext).isEmpty()
        }
        // Every source moved to one publisher: independence fails.
        assertTrue(rejected { row ->
            val evidence = row.getJSONArray("evidence")
            for (i in 0 until evidence.length()) {
                evidence.getJSONObject(i).put("source_url", "https://one.publisher.example/page$i")
            }
        })
        // Observations replaced: no primary entry agrees with the template any more.
        assertTrue(rejected { row ->
            val evidence = row.getJSONArray("evidence")
            for (i in 0 until evidence.length()) {
                evidence.getJSONObject(i).put("observed", "a completely different user agent")
            }
        })
        // Unlisted app build: the stated pair is absent from the slot context.
        assertTrue(rejected { row ->
            row.getJSONArray("evidence").getJSONObject(0).put("app_build", "unlisted")
        })
        // Impossible observation date on a primary entry.
        assertTrue(rejected { row ->
            row.getJSONArray("evidence").getJSONObject(0).put("observed_at", "2026-02-30")
        })
        // Demoted to candidate: never exposed.
        assertTrue(rejected { row -> row.put("status", "candidate") })
    }

    @Test fun bundledAssetsMatchTheCanonicalSources() {
        val webTemplates = file("../web/ua_templates.json", "web/ua_templates.json")
        assertEquals(webTemplates, templates)
        val webDevices = JSONObject(file("../web/devices.json", "web/devices.json"))
        val context = JSONObject(slotContext)
        assertEquals(
            webDevices.getJSONObject("meta").getString("dataVersion"),
            context.getString("deviceCatalogVersion")
        )
        assertEquals(
            webDevices.getJSONObject("meta").getString("catalogReleaseId"),
            context.getString("catalogReleaseId")
        )
    }
}
