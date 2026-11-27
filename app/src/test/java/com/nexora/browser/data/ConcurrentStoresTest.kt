package com.nexora.browser.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConcurrentStoresTest {
    @get:Rule val directory = TemporaryFolder()
    private fun file(path: File) = LocalStoreFile(path, path::inputStream, path::writeBytes)
    private fun profile(id: String) = BrowserProfile(id, id, "", "", 0, 0, "")

    @Test fun staleWholeFileSnapshotCannotRemoveAnotherWritersProfile() {
        val path = File(directory.root, "profiles.json")
        val studio = ProfileStore(file(path))
        val restore = ProfileStore(file(path))
        val snapshot = studio.loadAll()
        assertTrue(restore.add(profile("restored")))
        assertFalse(studio.replaceIfUnchanged(snapshot, snapshot + profile("created")))
        assertEquals(listOf("restored"), studio.loadAll().map { it.id })
    }

    @Test fun twoEditorsCannotOverwriteEachOtherOrResurrectADeletedProfile() {
        val path = File(directory.root, "profiles.json")
        val first = ProfileStore(file(path))
        val second = ProfileStore(file(path))
        val original = profile("same")
        assertTrue(first.add(original))
        assertTrue(second.updateIfUnchanged(original, original.copy(name = "new")))
        assertFalse(first.updateIfUnchanged(original, original.copy(name = "stale")))
        val latest = first.loadAll().single()
        assertEquals("new", latest.name)
        assertTrue(second.deleteMany(setOf(latest.id)))
        assertFalse(first.updateIfUnchanged(latest, latest.copy(name = "gone")))
        assertTrue(first.loadAll().isEmpty())
    }

    @Test fun differentStoreInstancesSerializeConcurrentProfileAndBatchInserts() {
        val path = File(directory.root, "profiles.json")
        val batches = File(directory.root, "batches.json")
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..12).map { index -> pool.submit<Boolean> {
                check(start.await(10, TimeUnit.SECONDS))
                val id = index.toString()
                val row = GeneratedUserAgent(id, "ANDROID", "d", "Device", "15", "browser",
                    locale = "en-US", userAgent = "ua", databaseVersion = "test", runtimeNote = "")
                ProfileStore(file(path)).add(profile(id)) &&
                    UserAgentBatchStore(file(batches)).save(UserAgentBatch(id, id, index.toLong(), "test", listOf(row)))
            } }
            start.countDown()
            jobs.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            val ids = (1..12).map { it.toString() }.toSet()
            assertEquals(ids, ProfileStore(file(path)).loadAll().map { it.id }.toSet())
            assertEquals(ids, UserAgentBatchStore(file(batches)).loadAll().map { it.id }.toSet())
        } finally {
            pool.shutdownNow()
        }
    }
}
