package com.nexora.browser.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCatalogTest {

    @Test
    fun catalogContainsEveryMajorProductModuleExactlyOnce() {
        val ids = FeatureCatalog.modules.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf(
                "profile-studio",
                "bulk-profile-creation",
                "fingerprint-engine",
                "proxy-center",
                "browser-workspace",
                "privacy-center",
                "automation-center",
                "extension-center",
                "chromium-engine",
                "settings-system"
            ),
            ids.toSet()
        )
    }

    @Test
    fun everyModuleHasAUsefulSummaryAndNextStep() {
        FeatureCatalog.modules.forEach { module ->
            assertTrue(module.title.isNotBlank())
            assertTrue(module.summary.isNotBlank())
            assertTrue(module.nextStep.isNotBlank())
            assertNotNull(module.status)
        }
    }

    @Test
    fun roadmapModulesAreNotConnectedToActiveActions() {
        val roadmap = FeatureCatalog.modules.filter { it.status == FeatureStatus.ROADMAP }
        assertTrue(roadmap.isNotEmpty())
        roadmap.forEach { module ->
            assertEquals(FeatureAction.NONE, module.action)
            assertFalse(module.actionLabel.isNotBlank())
        }
    }

    @Test
    fun automationCenterIsConnectedOnlyForImplementedLocalWorkflows() {
        val automation = FeatureCatalog.find("automation-center")
        assertNotNull(automation)
        assertEquals(FeatureStatus.PARTIAL, automation?.status)
        assertEquals(FeatureAction.AUTOMATION_CENTER, automation?.action)
        assertTrue(automation?.limitations?.any { it.contains("No general browser macro engine") } == true)
    }

    @Test
    fun partialModulesExplainBothWorkingBehaviorAndLimitations() {
        val partial = FeatureCatalog.modules.filter { it.status == FeatureStatus.PARTIAL }
        assertTrue(partial.isNotEmpty())
        partial.forEach { module ->
            assertTrue(module.workingNow.isNotEmpty())
            assertTrue(module.limitations.isNotEmpty())
        }
    }
}
