package com.nexora.browser.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LockedProfileCredentialsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun lockedCurrentAndLegacyRowsSurviveMigrationAndUnrelatedEdits() {
        val path = File(directory.root, "profiles.json")
        val combined = JSONObject().put("id", "locked").put("proxyCredentialsEncrypted", "unavailable-current")
        val legacy = JSONObject().put("id", "old").put("proxyUser", "previous").put("proxyPassEncrypted", "unavailable-old")
        val plain = JSONObject().put("id", "other").put("proxyUser", "migrating").put("proxyPass", "test-password")
        path.writeText(JSONArray().put(combined).put(legacy).put(plain).toString())
        var recovered = false
        val file = LocalStoreFile(path, path::inputStream, path::writeBytes)
        val store = ProfileStore(file, { "test-envelope:" + it }, { cipher ->
            when (cipher) {
                "unavailable-current" -> { check(recovered); JSONObject().put("username", "u").put("password", "p").toString() }
                "unavailable-old" -> { check(recovered); "recovered-password" }
                else -> cipher.removePrefix("test-envelope:")
            }
        })
        val listed = store.loadAll()
        assertEquals(listOf(true, true, false), listed.map { it.proxyCredentialsLocked })
        assertThrows(LocalStoreReadException::class.java) { store.getById("locked") }
        assertFalse(store.update(listed[0].copy(proxyUser = "replacement")))
        assertFalse(store.saveAllChecked(listOf(listed[2])))
        assertTrue(store.update(listed[2].copy(name = "renamed")))
        val stored = JSONArray(path.readText())
        assertEquals(combined.toString(), stored.getJSONObject(0).toString())
        assertEquals(legacy.toString(), stored.getJSONObject(1).toString())
        assertFalse(stored.getJSONObject(2).has("proxyPass"))
        recovered = true
        assertEquals("p", store.getById("locked")!!.proxyPass)
        assertEquals("recovered-password", store.getById("old")!!.proxyPass)
        assertEquals("renamed", store.getById("other")!!.name)
    }

    @Test fun malformedDecryptedEnvelopeRemainsReadOnlyAndNeverBecomesPlaintext() {
        val source = JSONObject().put("id", "p").put("proxyCredentialsEncrypted", "original")
        for (decrypted in listOf("{", "{}", "{\"username\":4,\"password\":\"p\"}")) {
            val result = ProfileCredentialCodec.decode(source, { decrypted }, { error("unexpected legacy read") })
            assertTrue(result.profile.proxyCredentialsLocked)
            assertFalse(result.needsMigration)
            assertEquals("original", result.preservedSource!!.getString("proxyCredentialsEncrypted"))
            assertThrows(IllegalStateException::class.java) {
                ProfileCredentialCodec.encode(result.profile) { error("unexpected encryption") }
            }
        }
    }
}
