package com.nexora.browser.data

import android.content.Context
import com.nexora.browser.security.SecretVault
import org.json.JSONArray
import java.io.File

/** Stores profile metadata atomically in the app's private files directory. */
class ProfileStore internal constructor(
    private val file: LocalStoreFile,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String
) {
    internal constructor(file: LocalStoreFile, vault: SecretVault = SecretVault()) :
        this(file, vault::encrypt, vault::decrypt)
    constructor(context: Context) : this(LocalStoreFile.android(File(context.filesDir, "profiles.json")))

    internal fun <T> withLock(action: () -> T): T = synchronized(file.lock, action)

    private fun readRows() = file.readRows { obj ->
        require((obj.opt("id") as? String)?.isNotBlank() == true) { "Missing profile ID" }
        ProfileCredentialCodec.decode(obj, decrypt, decrypt)
    }

    fun loadAll(): MutableList<BrowserProfile> = withLock {
        val original = readRows()
        val profiles = original.map { it.profile }.toMutableList()
        if (original.any { it.needsMigration }) writeProfiles(profiles, original)
        profiles
    }

    private fun writeProfiles(
        profiles: List<BrowserProfile>, original: List<ProfileCredentialCodec.Decoded>,
        explicitDeletions: Set<String> = emptySet()
    ): Boolean {
        val retained = original.filter { it.preservedSource != null }.associateBy { it.profile.id }
        val remainingIds = profiles.mapTo(mutableSetOf()) { it.id }
        if (retained.keys.any { it !in remainingIds && it !in explicitDeletions }) return false
        val rows = JSONArray()
        try {
            for (profile in profiles) {
                val locked = retained[profile.id]
                if (locked != null && locked.profile != profile) return false
                rows.put(locked?.preservedSource ?: ProfileCredentialCodec.encode(profile, encrypt))
            }
        } catch (_: Exception) {
            return false
        }
        return file.write(rows)
    }

    /** Refuse a replacement computed from an out-of-date screen or restore snapshot. */
    fun replaceIfUnchanged(expected: List<BrowserProfile>, replacement: List<BrowserProfile>): Boolean = withLock {
        val original = try {
            readRows()
        } catch (_: LocalStoreReadException) {
            return@withLock false
        }
        original.map { it.profile } == expected && writeProfiles(replacement, original)
    }

    private fun edit(explicitDeletions: Set<String> = emptySet(), change: (MutableList<BrowserProfile>) -> Boolean): Boolean = withLock {
        val original = try {
            readRows()
        } catch (_: LocalStoreReadException) {
            return@withLock false
        }
        val profiles = original.map { it.profile }.toMutableList()
        change(profiles) && writeProfiles(profiles, original, explicitDeletions)
    }

    fun add(profile: BrowserProfile): Boolean = edit { rows ->
        rows.none { it.id == profile.id } && rows.add(profile)
    }

    fun updateIfUnchanged(expected: BrowserProfile, replacement: BrowserProfile): Boolean = edit { rows ->
        val index = rows.indexOfFirst { it.id == expected.id }
        if (index < 0 || expected.id != replacement.id || rows[index] != expected) return@edit false
        rows[index] = replacement
        true
    }

    fun delete(id: String) {
        check(deleteMany(setOf(id))) { "Could not remove profile" }
    }

    fun deleteMany(ids: Set<String>): Boolean = ids.isEmpty() || edit(explicitDeletions = ids) { rows ->
        rows.removeAll { it.id in ids }
        true
    }

    fun getById(id: String): BrowserProfile? {
        val profile = loadAll().firstOrNull { it.id == id }
        if (profile?.proxyCredentialsLocked == true) throw LocalStoreReadException(ProfileCredentialCodec.LOCKED_MESSAGE)
        return profile
    }
}
