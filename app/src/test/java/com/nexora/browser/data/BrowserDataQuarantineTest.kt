package com.nexora.browser.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class BrowserDataQuarantineTest {
    private val files = mutableMapOf<String, MutableMap<String, Any?>>()
    private fun values(name: String) = files.getOrPut(name) { mutableMapOf() }
    private fun open(name: String): SharedPreferences {
        val data = values(name)
        return proxy(SharedPreferences::class.java) { _, method, args ->
            when (method) {
                "getAll" -> data.toMap()
                "contains" -> args[0] in data
                "getString", "getBoolean", "getInt" -> data[args[0]] ?: args[1]
                "edit" -> {
                    val changes = mutableMapOf<String, Any?>()
                    proxy(SharedPreferences.Editor::class.java) { editor, operation, params ->
                        when (operation) {
                            "apply", "commit" -> { data.putAll(changes); true }
                            else -> {
                                check(operation.startsWith("put"))
                                changes[params[0] as String] = params[1]
                                editor
                            }
                        }
                    }
                }
                else -> error("Unexpected preferences call " + method)
            }
        }
    }

    private fun <T> proxy(type: Class<T>, call: (Any, String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { receiver, method, args ->
            call(receiver, method.name, args ?: emptyArray())
        })

    @Test fun collidingProfilesCannotSeeLegacyOrPreviouslyCopiedBrowsingMetadata() {
        val a = "Aa"
        val b = "BB"
        assertEquals(a.hashCode(), b.hashCode())
        val old = values("nexora_browser_" + a.hashCode())
        val page = """[{"url":"https://prior.example","title":"Saved","timestamp":1}]"""
        old.putAll(mapOf("history" to page, "bookmarks" to page, "session_tabs" to page))
        val alreadyCopied = values(BrowserDataStore.profilePrefsName(a))
        alreadyCopied.putAll(old)
        alreadyCopied["__nexora_namespace_migrated_v2"] = true
        val legacyBefore = old.toMap()
        val copiedBefore = alreadyCopied.toMap()

        val first = BrowserDataStore(a, ::open)
        val second = BrowserDataStore(b, ::open)
        for (store in listOf(first, second)) {
            assertTrue(store.history().isEmpty())
            assertTrue(store.bookmarks().isEmpty())
            assertTrue(store.sessionSnapshot().tabs.isEmpty())
        }
        first.addBookmark("https://first.example", "First")
        second.addBookmark("https://second.example", "Second")
        assertEquals("https://first.example", BrowserDataStore(a, ::open).bookmarks().single().url)
        assertEquals("https://second.example", BrowserDataStore(b, ::open).bookmarks().single().url)
        assertEquals(legacyBefore, old)
        assertEquals(copiedBefore, alreadyCopied)
    }

    @Test fun aPriorMigrationMarkerStillTriggersQuarantineAfterTheLegacyFileIsGone() {
        val name = BrowserDataStore.profilePrefsName("marked")
        val previous = values(name)
        previous["__nexora_namespace_migrated_v2"] = true
        previous["history"] = """[{"url":"https://ambiguous.example"}]"""
        val snapshot = previous.toMap()
        assertTrue(BrowserDataStore("marked", ::open).history().isEmpty())
        assertEquals(snapshot, previous)
    }

    @Test fun aProfileSpecificStoreWithNoLegacyMigrationCanKeepItsHistory() {
        val previous = values(BrowserDataStore.profilePrefsName("clean"))
        previous["history"] = """[{"url":"https://clean.example"}]"""
        assertEquals("https://clean.example", BrowserDataStore("clean", ::open).history().single().url)
    }
}
