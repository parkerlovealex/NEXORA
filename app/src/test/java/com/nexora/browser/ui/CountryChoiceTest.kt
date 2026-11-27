package com.nexora.browser.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CountryChoiceTest {
    @Test fun selectingUnitedStatesFromUnitedResultsKeepsItsRegion() {
        val all = CountryChoice.choices(Locale.ENGLISH)
        val filtered = all.filter { it.label.startsWith("United") }
        val position = filtered.indexOfFirst { it.region == "US" }
        assertTrue(position >= 0)
        assertNotEquals("US", all[position].region)
        assertEquals("en-US", filtered[position].languageTag(Locale.ENGLISH))
        assertEquals("fr-US", filtered[position].languageTag(Locale.FRENCH))
        assertEquals("en-GB", all.first().languageTag(Locale.UK))
    }
}
