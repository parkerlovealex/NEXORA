package com.nexora.browser.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowPlannerTest {
    private val profiles = listOf(
        WorkflowProfileSnapshot("p1", "Alpha", "NONE", "", 0, "", "pixel"),
        WorkflowProfileSnapshot("p2", "Beta", "NONE", "", 0, "", "pixel"),
        WorkflowProfileSnapshot("p3", "Gamma", "HTTP", "proxy.example", 8080, "proxy-3", "pixel"),
        WorkflowProfileSnapshot("p4", "Delta", "SOCKS4", "socks.example", 1080, "", "")
    )

    @Test
    fun auditFindsUnsupportedAndIncompleteConfiguration() {
        val preview = WorkflowPlanner.auditProfiles(profiles)
        assertEquals(WorkflowPlanner.ID_PROFILE_AUDIT, preview.workflowId)
        assertTrue(preview.findings.any { it.contains("SOCKS4") })
        assertTrue(preview.findings.any { it.contains("device preset") })
    }

    @Test
    fun proxyAssignmentIsOneToOneAndLeavesUnsupportedProxyTypesOut() {
        val proxies = listOf(
            WorkflowProxySnapshot("x1", "HTTP", "HTTP", "one.example", 8080, 1L),
            WorkflowProxySnapshot("x2", "SOCKS4", "SOCKS4", "four.example", 1080, 2L),
            WorkflowProxySnapshot("x3", "SOCKS5", "SOCKS5", "two.example", 1080, 3L)
        )
        val preview = WorkflowPlanner.assignUnassignedProxies(profiles, proxies)
        assertEquals(2, preview.assignments.size)
        assertEquals(2, preview.assignments.map { it.proxyId }.toSet().size)
        assertTrue(preview.assignments.none { it.proxyId == "x2" })
        assertEquals(setOf("p1", "p2"), preview.assignments.map { it.profileId }.toSet())
    }

    @Test
    fun alreadyAssignedProxiesAreNotPairedAgain() {
        // p3 already holds library proxy x1 (proxyEntryId). A fresh
        // one-to-one run for the unassigned profiles must skip x1 even
        // though it is a valid library entry — pairing it again would
        // put two profiles on one proxy.
        val withAssignment = listOf(
            WorkflowProfileSnapshot("p1", "Alpha", "NONE", "", 0, "", "pixel"),
            WorkflowProfileSnapshot("p2", "Beta", "NONE", "", 0, "", "pixel"),
            WorkflowProfileSnapshot("p3", "Gamma", "HTTP", "one.example", 8080, "x1", "pixel")
        )
        val proxies = listOf(
            WorkflowProxySnapshot("x1", "HTTP", "HTTP", "one.example", 8080, 1L),
            WorkflowProxySnapshot("x3", "SOCKS5", "SOCKS5", "two.example", 1080, 3L)
        )
        val preview = WorkflowPlanner.assignUnassignedProxies(withAssignment, proxies)
        assertEquals(1, preview.assignments.size)
        assertEquals("x3", preview.assignments.single().proxyId)
        assertTrue(preview.assignments.none { it.proxyId == "x1" })
    }

    @Test
    fun profilesWithExistingProxyAreNeverOverwritten() {
        val preview = WorkflowPlanner.assignUnassignedProxies(
            profiles,
            listOf(WorkflowProxySnapshot("x1", "HTTP", "HTTP", "one.example", 8080, 1L))
        )
        assertTrue(preview.assignments.none { it.profileId == "p3" || it.profileId == "p4" })
    }
}
