package com.nexora.browser.data

import android.content.Context
import com.nexora.browser.security.SecretVault
import org.json.JSONArray
import java.io.File

/** Stores profile metadata atomically in the app's private files directory. */
class ProfileStore internal constructor(private val file: LocalStoreFile) {
    constructor(context: Context) : this(LocalStoreFile.android(File(context.filesDir, "profiles.json")))

    private val secretVault = SecretVault()

    private fun readProfiles(migrate: Boolean): MutableList<BrowserProfile> {
        var needsMigration = false
        val profiles = file.readRows { obj ->
            require((obj.opt("id") as? String)?.isNotBlank() == true) { "Missing profile ID" }
            val decoded = ProfileCredentialCodec.decode(obj, secretVault::decrypt, secretVault::decrypt)
            if (decoded.needsMigration) needsMigration = true
            decoded.profile
        }.toMutableList()
        if (migrate && needsMigration) writeProfiles(profiles)
        return profiles
    }

    @Synchronized
    fun loadAll(): MutableList<BrowserProfile> = readProfiles(migrate = true)

    private fun writeProfiles(profiles: List<BrowserProfile>): Boolean {
        val rows = JSONArray()
        try {
            profiles.forEach { rows.put(ProfileCredentialCodec.encode(it, secretVault::encrypt)) }
        } catch (_: Exception) {
            return false
        }
        return file.write(rows)
    }

    /** Validate the committed file even when a caller brings its own list. */
    @Synchronized
    fun saveAllChecked(profiles: List<BrowserProfile>): Boolean {
        try {
            readProfiles(migrate = false)
        } catch (_: LocalStoreReadException) {
            return false
        }
        return writeProfiles(profiles)
    }

    @Synchronized
    fun saveAll(profiles: List<BrowserProfile>) {
        check(saveAllChecked(profiles)) { "Could not save profiles" }
    }

    private fun edit(change: (MutableList<BrowserProfile>) -> Boolean): Boolean {
        val profiles = try {
            readProfiles(migrate = false)
        } catch (_: LocalStoreReadException) {
            return false
        }
        return change(profiles) && writeProfiles(profiles)
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
    fun deleteMany(ids: Set<String>): Boolean = ids.isEmpty() || edit { rows ->
        rows.removeAll { it.id in ids }
        true
    }

    @Synchronized
    fun getById(id: String): BrowserProfile? = loadAll().firstOrNull { it.id == id }
}
