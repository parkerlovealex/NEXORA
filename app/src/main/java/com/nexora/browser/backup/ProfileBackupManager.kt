package com.nexora.browser.backup

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyBackupRecord
import com.nexora.browser.data.ProxyLibraryStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Encrypted portable backup for profile configuration and the saved proxy library. */
data class ProfileBackupPreview(
    val createdAt: Long,
    val profileCount: Int,
    val proxyCount: Int,
    val proxiedProfileCount: Int
)

data class ProfileBackupRestoreResult(
    val profilesAdded: Int,
    val profilesSkipped: Int,
    val profileIdsRemapped: Int,
    val proxiesAdded: Int
)

object ProfileBackupManager {
    private const val FORMAT = "NEXORA_PORTABLE_BACKUP"
    private const val SCHEMA_VERSION = 1
    private const val MAX_PROFILES = 1_000
    private const val MAX_PROXIES = 500

    fun createBackup(
        profileStore: ProfileStore,
        proxyStore: ProxyLibraryStore,
        password: CharArray
    ): ByteArray {
        val profiles = profileStore.loadAll()
        check(profiles.none { it.proxyCredentialsLocked }) {
            com.nexora.browser.data.ProfileCredentialCodec.LOCKED_MESSAGE
        }
        val proxies = proxyStore.loadAll()
        require(profiles.size <= MAX_PROFILES) { "There are too many profiles for this backup format." }
        require(proxies.size <= MAX_PROXIES) { "There are too many saved proxies for this backup format." }
        val payload = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("profiles", JSONArray().apply {
                profiles.forEach { put(it.toJson()) }
            })
            .put("proxies", JSONArray().apply {
                proxies.forEach { entry ->
                    val credentials = proxyStore.credentials(entry)
                    check(!entry.hasCredentials || credentials.first.isNotEmpty() || credentials.second.isNotEmpty()) {
                        "Saved credentials for proxy '${entry.name}' could not be decrypted. Fix or remove that proxy before backing up."
                    }
                    put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("name", entry.name)
                            .put("type", entry.type)
                            .put("host", entry.host)
                            .put("port", entry.port)
                            .put("username", credentials.first)
                            .put("password", credentials.second)
                            .put("createdAt", entry.createdAt)
                    )
                }
            })
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        return try {
            PortableBackupCrypto.encrypt(plain, password)
        } finally {
            plain.fill(0)
        }
    }

    fun inspect(bytes: ByteArray, password: CharArray): ProfileBackupPreview {
        val backup = decode(bytes, password)
        return ProfileBackupPreview(
            createdAt = backup.createdAt,
            profileCount = backup.profiles.size,
            proxyCount = backup.proxies.size,
            proxiedProfileCount = backup.profiles.count { it.hasProxy }
        )
    }

    /** Merge is additive: profile IDs already on this device are skipped, never overwritten. */
    fun restore(
        bytes: ByteArray,
        password: CharArray,
        profileStore: ProfileStore,
        proxyStore: ProxyLibraryStore
    ): ProfileBackupRestoreResult {
        val backup = decode(bytes, password)
        return profileStore.withLock {
            proxyStore.withLock { mergeBackup(backup, profileStore, proxyStore) }
        }
    }

    private fun mergeBackup(
        backup: DecodedBackup, profileStore: ProfileStore, proxyStore: ProxyLibraryStore
    ): ProfileBackupRestoreResult {
        val existingProfiles = profileStore.loadAll()
        val existingIds = existingProfiles.map { it.id }.toSet()
        val toAdd = backup.profiles.filterNot { it.id in existingIds }
        val usedWebViewKeys = existingProfiles.map { it.webViewProfileKey }.toMutableSet()
        var profileIdsRemapped = 0
        val safeProfiles = toAdd.mapNotNull { profile ->
            var safeId = profile.id
            if ("nx_${safeId.take(8)}" in usedWebViewKeys) {
                // A37: derive the replacement ID deterministically from
                // the original ID. A random ID made every retry of the
                // same restore add another copy, because the backup's
                // original ID never matched the restored profile. With
                // a derived ID, a retry recognises its own earlier
                // restore and skips the profile.
                safeId = UUID.nameUUIDFromBytes(
                    "nexora-restore:${profile.id}".toByteArray(Charsets.UTF_8)
                ).toString()
                if (safeId in existingIds) return@mapNotNull null
                if ("nx_${safeId.take(8)}" in usedWebViewKeys) {
                    // The derived key also collides with an unrelated
                    // profile; only then fall back to a random ID.
                    do {
                        safeId = UUID.randomUUID().toString()
                    } while ("nx_${safeId.take(8)}" in usedWebViewKeys || safeId in existingIds)
                }
                profileIdsRemapped++
            }
            usedWebViewKeys += "nx_${safeId.take(8)}"
            profile.copy(id = safeId)
        }

        val oldProxyEntries = proxyStore.loadAll()
        val proxyMerge = proxyStore.mergePortable(backup.proxies)
        check(proxyMerge.success) { "Could not restore the saved proxy library. No profiles were changed." }

        val remappedProfiles = safeProfiles.map { profile ->
            val newProxyId = if (profile.proxyEntryId.isBlank()) {
                ""
            } else {
                proxyMerge.idMapping[profile.proxyEntryId].orEmpty()
            }
            profile.copy(proxyEntryId = newProxyId)
        }

        if (remappedProfiles.isNotEmpty() && !profileStore.replaceIfUnchanged(existingProfiles, existingProfiles + remappedProfiles)) {
            val rollbackSucceeded = proxyStore.save(oldProxyEntries)
            val suffix = if (rollbackSucceeded) " The proxy-library update was rolled back." else
                " Warning: proxy-library rollback also failed; inspect the proxy library before continuing."
            throw IllegalStateException("Could not write restored profiles.$suffix")
        }

        return ProfileBackupRestoreResult(
            profilesAdded = remappedProfiles.size,
            profilesSkipped = backup.profiles.size - remappedProfiles.size,
            profileIdsRemapped = profileIdsRemapped,
            proxiesAdded = proxyMerge.added
        )
    }

    private fun decode(bytes: ByteArray, password: CharArray): DecodedBackup {
        val plain = PortableBackupCrypto.decrypt(bytes, password)
        return try {
            val root = JSONObject(String(plain, Charsets.UTF_8))
            require(root.optString("format") == FORMAT) { "Backup content is not recognized." }
            require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) {
                "This backup schema version is not supported."
            }
            val profilesArray = root.optJSONArray("profiles") ?: JSONArray()
            val proxiesArray = root.optJSONArray("proxies") ?: JSONArray()
            require(profilesArray.length() <= MAX_PROFILES) { "Backup contains too many profiles." }
            require(proxiesArray.length() <= MAX_PROXIES) { "Backup contains too many proxies." }

            val profiles = mutableListOf<BrowserProfile>()
            val profileIds = mutableSetOf<String>()
            for (index in 0 until profilesArray.length()) {
                val item = profilesArray.optJSONObject(index)
                    ?: throw IllegalArgumentException("Profile record ${index + 1} is invalid.")
                val profile = BrowserProfile.fromJson(item)
                require(profile.id.isNotBlank() && profile.id.length <= 128) {
                    "Profile record ${index + 1} has an invalid ID."
                }
                require(profile.name.isNotBlank() && profile.name.length <= 60) {
                    "Profile '${profile.id}' has an invalid name."
                }
                require(profileIds.add(profile.id)) { "Backup contains duplicate profile IDs." }
                require(profile.proxyType.uppercase() in setOf("NONE", "HTTP", "HTTPS", "SOCKS5", "SOCKS4")) {
                    "Profile '${profile.name}' has an unsupported proxy type."
                }
                if (profile.proxyType.uppercase() != "NONE") {
                    require(profile.proxyHost.isNotBlank() && profile.proxyPort in 1..65535) {
                        "Profile '${profile.name}' has an incomplete proxy endpoint."
                    }
                }
                profiles += profile
            }

            val proxies = mutableListOf<ProxyBackupRecord>()
            val proxyIds = mutableSetOf<String>()
            for (index in 0 until proxiesArray.length()) {
                val item = proxiesArray.optJSONObject(index)
                    ?: throw IllegalArgumentException("Proxy record ${index + 1} is invalid.")
                val proxy = ProxyBackupRecord(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    type = item.optString("type").uppercase(),
                    host = item.optString("host"),
                    port = item.optInt("port"),
                    username = item.optString("username"),
                    password = item.optString("password"),
                    createdAt = item.optLong("createdAt", 0L)
                )
                require(proxy.id.isNotBlank() && proxy.id.length <= 128) {
                    "Proxy record ${index + 1} has an invalid ID."
                }
                require(proxyIds.add(proxy.id)) { "Backup contains duplicate proxy IDs." }
                require(proxy.type in setOf("HTTP", "HTTPS", "SOCKS5", "SOCKS4")) {
                    "Proxy '${proxy.name}' has an unsupported type."
                }
                require(proxy.host.isNotBlank() && proxy.host.length <= 255 && proxy.port in 1..65535) {
                    "Proxy '${proxy.name}' has an invalid endpoint."
                }
                require(proxy.name.isNotBlank() && proxy.name.length <= 60) {
                    "A proxy record has an invalid name."
                }
                proxies += proxy
            }

            DecodedBackup(
                createdAt = root.optLong("createdAt", 0L),
                profiles = profiles,
                proxies = proxies
            )
        } finally {
            plain.fill(0)
        }
    }

    private data class DecodedBackup(
        val createdAt: Long,
        val profiles: List<BrowserProfile>,
        val proxies: List<ProxyBackupRecord>
    )
}
