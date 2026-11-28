package com.nexora.browser.util

import com.nexora.browser.bulk.BulkProfileDraft
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingCatalogValuesTest {
    @Test fun missingCatalogSafariRequiresManualEntry() {
        val ios = DevicePreset("fixture", "Apple", "Test device", "iPhone", 100, 200, 2.0,
            emptyList(), emptyList(), 0, "", listOf("18.2"), true)
        val errors = UserAgentGenerationEngine.validate(UserAgentGenerationRequest(0, 1), listOf(ios), emptyList(), "")
        assertTrue("iOS alone cannot establish the Safari version", errors.any { it.contains("manual Safari version") })
    }

    @Test fun missingHardwareStaysAbsentWhenProfilesAndBulkDraftsAreSaved() {
        val stored = BrowserProfile.fromJson(JSONObject()).toJson()
        val draft = BulkProfileDraft.fromJson(JSONObject()).toJson()
        for (json in listOf(stored, draft)) {
            assertFalse(json.has("ramGb"))
            assertFalse(json.has("storageGb"))
        }
        val known = BrowserProfile.fromJson(JSONObject().put("ramGb", 6).put("storageGb", 96)).toJson()
        assertEquals(6, known.getInt("ramGb"))
        assertEquals(96, known.getInt("storageGb"))
    }
}
