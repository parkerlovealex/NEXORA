package com.nexora.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSelectionStateTest {
    @Test
    fun selectAllAndClearAreDeterministic() {
        val state = ProfileSelectionState()
        state.selectAll(listOf("a", "b", "c", ""))
        assertEquals(setOf("a", "b", "c"), state.selectedIds)
        assertEquals(3, state.count)
        state.clear()
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun toggleAndSetSelectedDoNotDuplicateIds() {
        val state = ProfileSelectionState()
        state.setSelected("a", true)
        state.setSelected("a", true)
        assertEquals(1, state.count)
        state.toggle("a")
        assertFalse(state.contains("a"))
        state.toggle("b")
        assertTrue(state.contains("b"))
    }

    @Test
    fun selectionSurvivesFilteringAndDropsDeletedProfiles() {
        val state = ProfileSelectionState()
        state.selectAll(listOf("a", "b", "c"))
        assertFalse(state.retain(setOf("a", "b", "c", "d")))
        assertTrue(state.retain(setOf("a", "c")))
        assertEquals(setOf("a", "c"), state.selectedIds)
    }

    @Test
    fun blankIdsAreIgnored() {
        val state = ProfileSelectionState()
        state.setSelected("", true)
        state.toggle(" ")
        assertTrue(state.selectedIds.isEmpty())
    }
}
