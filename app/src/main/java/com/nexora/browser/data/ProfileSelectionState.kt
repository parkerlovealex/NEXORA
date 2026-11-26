package com.nexora.browser.data

/**
 * Small, UI-independent selection model for bulk profile management.
 * Keeps selection stable while the profile list is filtered or refreshed.
 */
class ProfileSelectionState {
    private val ids = linkedSetOf<String>()

    val selectedIds: Set<String>
        get() = ids.toSet()

    val count: Int
        get() = ids.size

    fun contains(id: String): Boolean = id in ids

    fun setSelected(id: String, selected: Boolean) {
        if (id.isBlank()) return
        if (selected) ids.add(id) else ids.remove(id)
    }

    fun toggle(id: String) {
        if (id.isBlank()) return
        if (!ids.add(id)) ids.remove(id)
    }

    fun selectAll(profileIds: Collection<String>) {
        ids.clear()
        ids.addAll(profileIds.filter { it.isNotBlank() })
    }

    fun clear() {
        ids.clear()
    }

    fun retain(validIds: Set<String>): Boolean = ids.retainAll(validIds)
}
