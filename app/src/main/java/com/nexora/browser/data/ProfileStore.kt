package com.nexora.browser.data

import android.content.Context
import android.util.AtomicFile
import com.nexora.browser.security.SecretVault
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/** Stores profile metadata atomically in the app's private files directory. */
class ProfileStore(context: Context) {

    private val atomicFile = AtomicFile(File(context.filesDir, "profiles.json"))
    private val secretVault = SecretVault()

    @Synchronized
    fun loadAll(): MutableList<BrowserProfile> {
        if (!atomicFile.baseFile.exists()) return mutableListOf()
        var needsSecretMigration = false
        val profiles = try {
            val array = atomicFile.openRead().bufferedReader().use { JSONArray(it.readText()) }
            val list = mutableListOf<BrowserProfile>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val decoded = ProfileCredentialCodec.decode(
                    obj,
                    decryptPayload = { secretVault.decrypt(it) },
                    decryptLegacyPassword = { secretVault.decrypt(it) }
                )
                if (decoded.needsMigration) needsSecretMigration = true
                val profile = decoded.profile
                if (profile.id.isNotBlank()) list.add(profile)
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
        if (needsSecretMigration && profiles.isNotEmpty()) saveAllChecked(profiles)
        return profiles
    }

    /** Returns false if encryption or the atomic disk write fails. */
    @Synchronized
    fun saveAllChecked(profiles: List<BrowserProfile>): Boolean {
        val array = JSONArray()
        try {
            profiles.forEach { profile ->
                val obj = ProfileCredentialCodec.encode(profile) { payload ->
                    secretVault.encrypt(payload)
                }
                array.put(obj)
            }
        } catch (_: Exception) {
            return false
        }
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) atomicFile.failWrite(stream)
            false
        }
    }

    @Synchronized
    fun saveAll(profiles: List<BrowserProfile>) {
        saveAllChecked(profiles)
    }

    @Synchronized
    fun add(profile: BrowserProfile): Boolean {
        val list = loadAll()
        if (list.any { it.id == profile.id }) return false
        list.add(profile)
        return saveAllChecked(list)
    }

    @Synchronized
    fun update(profile: BrowserProfile): Boolean {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        return saveAllChecked(list)
    }

    @Synchronized
    fun delete(id: String) {
        val list = loadAll()
        list.removeAll { it.id == id }
        saveAllChecked(list)
    }

    /** Removes a group of profile records in one atomic write. */
    @Synchronized
    fun deleteMany(ids: Set<String>): Boolean {
        if (ids.isEmpty()) return true
        val remaining = loadAll().filterNot { it.id in ids }
        return saveAllChecked(remaining)
    }

    @Synchronized
    fun getById(id: String): BrowserProfile? = loadAll().firstOrNull { it.id == id }
}
