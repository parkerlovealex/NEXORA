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

    private fun readRows() = file.readRows { obj ->
        require((obj.opt("id") as? String)?.isNotBlank() == true) { "Missing profile ID" }
        ProfileCredentialCodec.decode(obj, decrypt, decrypt)
    }

    @Synchronized
    fun loadAll(): MutableList<BrowserProfile> {
        val original = readRows()
        val profiles = original.map { it.profile }.toMutableList()
        if (original.any { it.needsMigration }) writeProfiles(profiles, original)
        return profiles
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

    /** Validate the committed file even when a caller brings its own list. */
    @Synchronized
    fun saveAllChecked(profiles: List<BrowserProfile>): Boolean {
        val original = try {
            readRows()
        } catch (_: LocalStoreReadException) {
            return false
        }
        return writeProfiles(profiles, original)
    }

    @Synchronized
    fun saveAll(profiles: List<BrowserProfile>) {
        check(saveAllChecked(profiles)) { "Could not save profiles" }
    }

    private fun edit(explicitDeletions: Set<String> = emptySet(), change: (MutableList<BrowserProfile>) -> Boolean): Boolean {
        val original = try {
            readRows()
        } catch (_: LocalStoreReadException) {
            return false
        }
        val profiles = original.map { it.profile }.toMutableList()
        return change(profiles) && writeProfiles(profiles, original, explicitDeletions)
    }

    @Synchronized
    fun add(profile: BrowserProfile): Boolean = edit { rows ->
        rows.none { it.id == profile.id } && rows.add(profile)
    }

    @Synchronized
    fun update(profile: BrowserProfile): Boolean = edit { rows ->
        val index = rows.indexOfFirst { it.id == profile.id }
        if (index < 0) rows.add(profile) else rows[index] = profile
        true
    }

    @Synchronized
    fun delete(id: String) {
        check(deleteMany(setOf(id))) { "Could not remove profile" }
    }

    @Synchronized
    fun deleteMany(ids: Set<String>): Boolean = ids.isEmpty() || edit(explicitDeletions = ids) { rows ->
        rows.removeAll { it.id in ids }
        true
    }

    @Synchronized
    fun getById(id: String): BrowserProfile? {
        val profile = loadAll().firstOrNull { it.id == id }
        if (profile?.proxyCredentialsLocked == true) throw LocalStoreReadException(ProfileCredentialCodec.LOCKED_MESSAGE)
        return profile
    }
}
