package com.nexora.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionPolicyTest {
    private fun tabs(count: Int) = (0 until count).map { index ->
        SessionTabState("https://site$index.example", "Tab $index", false, null)
    }

    @Test
    fun emptySessionHasNoActiveTab() {
        assertEquals(SessionSelection(emptyList(), 0), BrowserSessionPolicy.selectForPersistence(emptyList(), 9))
    }

    @Test
    fun sessionsUnderLimitArePreservedInOrder() {
        val source = tabs(8)
        val selected = BrowserSessionPolicy.selectForPersistence(source, 5)
        assertEquals(source, selected.tabs)
        assertEquals(5, selected.activeIndex)
    }

    @Test
    fun sessionsAtLimitArePreservedInOrder() {
        val source = tabs(BrowserSessionPolicy.MAX_PERSISTED_TABS)
        val selected = BrowserSessionPolicy.selectForPersistence(source, 19)
        assertEquals(source, selected.tabs)
        assertEquals(19, selected.activeIndex)
    }

    @Test
    fun activeTabBeyondPersistenceWindowIsRetained() {
        val source = tabs(27)
        val selected = BrowserSessionPolicy.selectForPersistence(source, 24)
        assertEquals(20, selected.tabs.size)
        assertEquals(source.take(19) + source[24], selected.tabs)
        assertEquals(19, selected.activeIndex)
        assertEquals(source[24], selected.tabs[selected.activeIndex])
    }

    @Test
    fun activeIndexIsClampedToExistingTabs() {
        val source = tabs(4)
        val selected = BrowserSessionPolicy.selectForPersistence(source, 99)
        assertEquals(source, selected.tabs)
        assertEquals(3, selected.activeIndex)
    }

    @Test
    fun negativeActiveIndexSelectsFirstTab() {
        val source = tabs(4)
        val selected = BrowserSessionPolicy.selectForPersistence(source, -3)
        assertEquals(source, selected.tabs)
        assertEquals(0, selected.activeIndex)
    }

    @Test
    fun selectionNeverExceedsPersistenceLimit() {
        val selected = BrowserSessionPolicy.selectForPersistence(tabs(200), 199)
        assertTrue(selected.tabs.size <= BrowserSessionPolicy.MAX_PERSISTED_TABS)
        assertEquals("https://site199.example", selected.tabs.last().url)
    }
}
