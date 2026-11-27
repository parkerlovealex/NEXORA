package com.nexora.browser.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PublishedUserAgentTemplatesTest {
    private fun webFile(name: String) = listOf(File("../web", name), File("web", name)).first { it.isFile }.readText()
    private val devices = webFile("devices.json")
    private val templates = webFile("ua_templates.json")

    private fun verified(catalog: JSONObject): JSONObject {
        val records = catalog.getJSONArray("records")
        return (0 until records.length()).map { records.getJSONObject(it) }.single { it.getString("status") == "verified" }
    }

    @Test fun publishedRecordReachesGeneratorWithItsLiteralTokensAndCatalogVersion() {
        val row = verified(JSONObject(templates))
        val entry = PublishedUserAgentTemplates.read(templates, devices).single()
        val release = JSONObject(devices).getJSONObject("meta").getJSONObject("apps").getJSONObject(entry.appId)
        val expected = row.getString("template").replace("{appVersion}", release.getString("version"))
        assertEquals(row.getString("id"), entry.id)
        assertEquals(expected, entry.render("galaxy-s24", "15"))
        assertFalse(entry.render("galaxy-s24", "15").contains("{"))
    }

    @Test fun missingCorroborationOrCatalogPairKeepsTheTemplateOut() {
        fun rejected(edit: (JSONObject, JSONObject) -> Unit) {
            val catalog = JSONObject(templates)
            val db = JSONObject(devices)
            edit(verified(catalog), db)
            assertTrue(PublishedUserAgentTemplates.read(catalog.toString(), db.toString()).isEmpty())
        }
        rejected { row, _ -> row.put("status", "candidate") }
        rejected { row, _ -> row.getJSONArray("evidence").getJSONObject(2).put("observed", "different observation") }
        rejected { row, _ ->
            val evidence = row.getJSONArray("evidence")
            evidence.getJSONObject(0).put("source_url", "https://one.publisher.co.uk/a")
            evidence.getJSONObject(2).put("source_url", "https://two.publisher.co.uk/b")
        }
        rejected { row, _ ->
            val evidence = row.getJSONArray("evidence")
            evidence.getJSONObject(2).put("source_id", evidence.getJSONObject(0).getString("source_id"))
        }
        rejected { row, _ -> row.getJSONArray("evidence").getJSONObject(0).put("observed_at", "2026-02-30") }
        rejected { row, _ -> row.getJSONArray("evidence").getJSONObject(0).put("app_build", "unlisted") }
        rejected { row, db -> db.getJSONObject("meta").getJSONObject("apps").remove(row.getString("app")) }
    }

    @Test fun independentTemplateRefreshUsesBothPublicPathsAndKeepsTheDeviceRevision() {
        val urls = mutableListOf<String>()
        fun refresh(active: String, published: String?) = DeviceDataUpdater.fetchUpdate("https://catalog.example", active, null) {
            urls += it.path
            if (it.path == "/api/v1/data") devices else published
        }
        val (updated, snapshot) = refresh(devices, templates)
        assertEquals(listOf("/api/v1/data", "/ua_templates.json"), urls)
        assertEquals(DeviceDataUpdater.Result.UPDATED, updated)
        val active = requireNotNull(snapshot)
        assertEquals(DeviceDataUpdater.validateAndVersion(devices), DeviceDataUpdater.validateAndVersion(active))
        assertEquals(1, PublishedUserAgentTemplates.read(DeviceDataUpdater.activeTemplates(active, null), active).size)
        assertEquals(DeviceDataUpdater.Result.ALREADY_CURRENT to null, refresh(active, templates))
        assertEquals(DeviceDataUpdater.Result.OFFLINE_OR_ERROR to null, refresh(active, null))
        assertEquals(DeviceDataUpdater.Result.INVALID_DATA to null, refresh(active, "{}"))
        val invalid = JSONObject(templates).put("dataVersion", "2099.01.01.1")
        verified(invalid).getJSONArray("evidence").getJSONObject(0).put("app_version", "unknown")
        assertEquals(DeviceDataUpdater.Result.INVALID_DATA to null, refresh(active, invalid.toString()))
    }
}
