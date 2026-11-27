package com.nexora.browser.data

import org.json.JSONObject

/**
 * Encodes profile proxy credentials as one encrypted payload.
 *
 * The codec accepts the app's historical proxyPassEncrypted field and old
 * plaintext profile JSON for migration. The encryption primitive is injected
 * so the serialization contract can be tested independently of Android Keystore.
 */
object ProfileCredentialCodec {

    data class Decoded(
        val profile: BrowserProfile,
        val needsMigration: Boolean,
        internal val preservedSource: JSONObject? = null
    )

    fun encode(
        profile: BrowserProfile,
        encryptPayload: (String) -> String
    ): JSONObject {
        check(!profile.proxyCredentialsLocked) { LOCKED_MESSAGE }
        val json = profile.toJson()
        val username = profile.proxyUser
        val password = profile.proxyPass

        // Never persist either credential field in plaintext.
        json.remove(KEY_USERNAME)
        json.remove(KEY_PASSWORD)
        json.remove(KEY_LEGACY_ENCRYPTED_PASSWORD)
        json.remove(KEY_ENCRYPTED_CREDENTIALS)

        if (username.isNotEmpty() || password.isNotEmpty()) {
            val payload = JSONObject()
                .put("username", username)
                .put("password", password)
                .toString()
            json.put(KEY_ENCRYPTED_CREDENTIALS, encryptPayload(payload))
        }
        return json
    }

    fun decode(
        source: JSONObject,
        decryptPayload: (String) -> String,
        decryptLegacyPassword: (String) -> String
    ): Decoded {
        val normalized = JSONObject(source.toString())
        if (source.has(KEY_ENCRYPTED_CREDENTIALS) || source.has(KEY_LEGACY_ENCRYPTED_PASSWORD)) {
            try {
                if (source.has(KEY_ENCRYPTED_CREDENTIALS)) {
                    val encrypted = source.get(KEY_ENCRYPTED_CREDENTIALS) as String
                    require(encrypted.isNotBlank())
                    val payload = JSONObject(decryptPayload(encrypted))
                    val user = payload.get("username") as String
                    val password = payload.get("password") as String
                    require(user.isNotEmpty() || password.isNotEmpty())
                    normalized.put(KEY_USERNAME, user)
                    normalized.put(KEY_PASSWORD, password)
                    return Decoded(BrowserProfile.fromJson(normalized), false)
                }
                val encrypted = source.get(KEY_LEGACY_ENCRYPTED_PASSWORD) as String
                require(encrypted.isNotBlank())
                val password = decryptLegacyPassword(encrypted)
                require(password.isNotEmpty())
                normalized.put(KEY_PASSWORD, password)
                return Decoded(BrowserProfile.fromJson(normalized), true)
            } catch (_: Exception) {
                normalized.remove(KEY_USERNAME)
                normalized.remove(KEY_PASSWORD)
                return Decoded(BrowserProfile.fromJson(normalized).copy(proxyCredentialsLocked = true), false, source)
            }
        }

        val hasLegacyPlaintext =
            normalized.optString(KEY_USERNAME).isNotEmpty() ||
                normalized.optString(KEY_PASSWORD).isNotEmpty()
        return Decoded(BrowserProfile.fromJson(normalized), hasLegacyPlaintext)
    }

    const val LOCKED_MESSAGE = "Saved proxy credentials could not be unlocked. Unlock the device and retry."
    private const val KEY_USERNAME = "proxyUser"
    private const val KEY_PASSWORD = "proxyPass"
    private const val KEY_LEGACY_ENCRYPTED_PASSWORD = "proxyPassEncrypted"
    private const val KEY_ENCRYPTED_CREDENTIALS = "proxyCredentialsEncrypted"
}
