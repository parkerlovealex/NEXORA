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
        val needsMigration: Boolean
    )

    fun encode(
        profile: BrowserProfile,
        encryptPayload: (String) -> String
    ): JSONObject {
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
        val encryptedCredentials = normalized.optString(KEY_ENCRYPTED_CREDENTIALS)

        if (encryptedCredentials.isNotBlank()) {
            try {
                val payload = JSONObject(decryptPayload(encryptedCredentials))
                normalized.put(KEY_USERNAME, payload.optString("username"))
                normalized.put(KEY_PASSWORD, payload.optString("password"))
            } catch (_: Exception) {
                // Match the existing safe fallback: unreadable credentials are
                // never surfaced as plaintext or allowed to crash profile loading.
                normalized.put(KEY_USERNAME, "")
                normalized.put(KEY_PASSWORD, "")
            }
            return Decoded(BrowserProfile.fromJson(normalized), false)
        }

        val legacyEncryptedPassword = normalized.optString(KEY_LEGACY_ENCRYPTED_PASSWORD)
        if (legacyEncryptedPassword.isNotBlank()) {
            normalized.put(
                KEY_PASSWORD,
                try {
                    decryptLegacyPassword(legacyEncryptedPassword)
                } catch (_: Exception) {
                    ""
                }
            )
            normalized.remove(KEY_LEGACY_ENCRYPTED_PASSWORD)
            return Decoded(BrowserProfile.fromJson(normalized), true)
        }

        val hasLegacyPlaintext =
            normalized.optString(KEY_USERNAME).isNotEmpty() ||
                normalized.optString(KEY_PASSWORD).isNotEmpty()
        return Decoded(BrowserProfile.fromJson(normalized), hasLegacyPlaintext)
    }

    private const val KEY_USERNAME = "proxyUser"
    private const val KEY_PASSWORD = "proxyPass"
    private const val KEY_LEGACY_ENCRYPTED_PASSWORD = "proxyPassEncrypted"
    private const val KEY_ENCRYPTED_CREDENTIALS = "proxyCredentialsEncrypted"
}
