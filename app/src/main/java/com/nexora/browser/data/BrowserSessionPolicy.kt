package com.nexora.browser.data

/** UI-independent session selection rules shared by browser persistence and tests. */
data class SessionTabState(
    val url: String,
    val title: String,
    val desktopMode: Boolean,
    val groupId: String?
)

data class SessionSelection(
    val tabs: List<SessionTabState>,
    val activeIndex: Int
)

object BrowserSessionPolicy {
    const val MAX_PERSISTED_TABS = 20

    /**
     * Keep the active tab in the persisted window even when it is beyond the
     * first [MAX_PERSISTED_TABS] tabs. This avoids restoring a different tab
     * after a user switches to a later tab in a large live session.
     */
    fun selectForPersistence(tabs: List<SessionTabState>, activeIndex: Int): SessionSelection {
        if (tabs.isEmpty()) return SessionSelection(emptyList(), 0)

        val safeActiveIndex = activeIndex.coerceIn(0, tabs.lastIndex)
        val selected = if (tabs.size <= MAX_PERSISTED_TABS || safeActiveIndex < MAX_PERSISTED_TABS) {
            tabs.take(MAX_PERSISTED_TABS)
        } else {
            tabs.take(MAX_PERSISTED_TABS - 1) + tabs[safeActiveIndex]
        }
        val persistedActiveIndex = if (safeActiveIndex < MAX_PERSISTED_TABS) {
            safeActiveIndex.coerceAtMost(selected.lastIndex)
        } else {
            MAX_PERSISTED_TABS - 1
        }
        return SessionSelection(selected, persistedActiveIndex)
    }
}
