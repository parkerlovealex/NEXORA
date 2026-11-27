package com.nexora.browser.ui

import java.util.Locale

internal data class CountryChoice(val region: String, val label: String) {
    override fun toString() = label

    fun languageTag(device: Locale): String {
        if (region.isEmpty()) return device.toLanguageTag()
        val language = device.language.takeUnless { it.isBlank() || it == "und" } ?: "en"
        return Locale.Builder().setLanguage(language).setRegion(region).build().toLanguageTag()
    }

    companion object {
        fun choices(displayLocale: Locale): List<CountryChoice> {
            val countries = Locale.getISOCountries().map { region ->
                val name = Locale.Builder().setRegion(region).build().getDisplayCountry(displayLocale)
                CountryChoice(region, "$name ($region)")
            }
            return listOf(CountryChoice("", "Use device default")) + countries.sortedBy { it.label }
        }
    }
}
