package com.nexora.browser.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LocalStoreReadTest {
    @get:Rule val directory = TemporaryFolder()

    private fun storeFile(path: File) = LocalStoreFile(path, path::inputStream, path::writeBytes)
    private fun profile(id: String) = BrowserProfile(id, "Saved", "device", "15", 8, 128, "ua")
    private val batch = UserAgentBatch("batch", "Saved", 1, "test", listOf(
        GeneratedUserAgent("row", "ANDROID", "device", "Device", "15", "browser",
            locale = "en-US", userAgent = "ua", databaseVersion = "test", runtimeNote = "")
    ))

    @Test fun damagedProfilesBlockAllWritesAndKeepTheOriginalBytes() {
        val path = File(directory.root, "profiles.json")
        val store = ProfileStore(storeFile(path))
        for (value in listOf("[", "[{\"id\":\"keep\"},false]", "[{}]", "[] trailing data")) {
            path.writeText(value)
            assertThrows(LocalStoreReadException::class.java) { store.loadAll() }
            assertFalse(store.add(profile("new")))
            assertFalse(store.updateIfUnchanged(profile("keep"), profile("keep")))
            assertFalse(store.deleteMany(setOf("keep")))
            assertFalse(store.replaceIfUnchanged(emptyList(), emptyList()))
            assertArrayEquals(value.toByteArray(), path.readBytes())
        }
    }

    @Test fun damagedBatchesCannotBeDeletedOrReplaced() {
        val path = File(directory.root, "batches.json")
        val store = UserAgentBatchStore(storeFile(path))
        for (value in listOf("{", "[null]", "[{\"id\":\"batch\",\"records\":[{},null]}]")) {
            path.writeText(value)
            assertThrows(LocalStoreReadException::class.java) { store.loadAll() }
            assertFalse(store.save(batch))
            assertFalse(store.delete(batch.id))
            assertArrayEquals(value.toByteArray(), path.readBytes())
        }
    }

    @Test fun aGenuinelyMissingStoreCanBeCreatedAndReadBack() {
        val profiles = ProfileStore(storeFile(File(directory.root, "profiles.json")))
        val batches = UserAgentBatchStore(storeFile(File(directory.root, "batches.json")))
        assertTrue(profiles.loadAll().isEmpty())
        assertTrue(batches.loadAll().isEmpty())
        assertTrue(profiles.add(profile("p")))
        assertTrue(batches.save(batch))
        assertEquals("p", profiles.loadAll().single().id)
        assertEquals(batch, batches.loadAll().single())
    }

    @Test fun ioErrorsAndAnUnreadableBackupNeverBecomeAnEmptyBaseline() {
        val path = File(directory.root, "profiles.json")
        val store = ProfileStore(LocalStoreFile(path, { throw IOException("read failed") }) {
            error("write attempted after read failure")
        })
        assertThrows(LocalStoreReadException::class.java) { store.loadAll() }
        assertFalse(store.replaceIfUnchanged(emptyList(), emptyList()))
        File(directory.root, "profiles.json.bak").writeText("original bytes")
        assertThrows(LocalStoreReadException::class.java) { ProfileStore(storeFile(path)).loadAll() }
    }
}
