package com.nexora.browser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCredentialCodecTest {

    private fun profile(
        username: String = "proxy-user",
        password: String = "proxy-secret"
    ) = BrowserProfile(
        id = "profile-credential-test",
        name = "Credential test",
        deviceId = "pixel-test",
        osVersion = "Android 15",
        ramGb = 8,
        storageGb = 128,
        userAgent = "Mozilla/5.0",
        proxyType = "HTTP",
        proxyHost = "proxy.example",
        proxyPort = 8080,
        proxyUser = username,
        proxyPass = password
    )

    @Test
    fun encodedProfileDoesNotPersistProxyCredentialsInPlaintext() {
        val encoded = ProfileCredentialCodec.encode(profile()) { "wrapped:$it" }
        assertFalse(encoded.has("proxyUser"))
        assertFalse(encoded.has("proxyPass"))
        assertFalse(encoded.has("proxyPassEncrypted"))
        assertTrue(encoded.has("proxyCredentialsEncrypted"))
        assertFalse(encoded.toString().contains("proxy-user"))
        assertFalse(encoded.toString().contains("proxy-secret"))
    }

    @Test
    fun encryptedCredentialPayloadRoundTripsBothUsernameAndPassword() {
        val encoded = ProfileCredentialCodec.encode(profile()) { "wrapped:$it" }
        val decoded = ProfileCredentialCodec.decode(
            encoded,
            decryptPayload = { it.removePrefix("wrapped:") },
            decryptLegacyPassword = { it }
        )
        assertEquals("proxy-user", decoded.profile.proxyUser)
        assertEquals("proxy-secret", decoded.profile.proxyPass)
        assertFalse(decoded.needsMigration)
    }

    @Test
    fun legacyPlaintextCredentialsAreReadAndMarkedForMigration() {
        val oldJson = profile().toJson()
        val decoded = ProfileCredentialCodec.decode(
            oldJson,
            decryptPayload = { it },
            decryptLegacyPassword = { it }
        )
        assertEquals("proxy-user", decoded.profile.proxyUser)
        assertEquals("proxy-secret", decoded.profile.proxyPass)
        assertTrue(decoded.needsMigration)
    }

    @Test
    fun legacyEncryptedPasswordKeepsUsernameAndMigratesToCombinedPayload() {
        val oldJson = profile(password = "").toJson()
            .put("proxyPassEncrypted", "legacy:proxy-secret")
        val decoded = ProfileCredentialCodec.decode(
            oldJson,
            decryptPayload = { it },
            decryptLegacyPassword = { it.removePrefix("legacy:") }
        )
        assertEquals("proxy-user", decoded.profile.proxyUser)
        assertEquals("proxy-secret", decoded.profile.proxyPass)
        assertTrue(decoded.needsMigration)
    }

    @Test
    fun unreadableEncryptedPayloadDoesNotCrashOrExposeCiphertext() {
        val encoded = ProfileCredentialCodec.encode(profile()) { "wrapped:$it" }
        val decoded = ProfileCredentialCodec.decode(
            encoded,
            decryptPayload = { error("Keystore key unavailable") },
            decryptLegacyPassword = { it }
        )
        assertEquals("", decoded.profile.proxyUser)
        assertEquals("", decoded.profile.proxyPass)
        assertFalse(decoded.needsMigration)
    }

    @Test
    fun credentialFreeProfileDoesNotCreateAnEncryptedCredentialField() {
        val encoded = ProfileCredentialCodec.encode(profile("", "")) { "wrapped:$it" }
        assertFalse(encoded.has("proxyCredentialsEncrypted"))
        assertFalse(encoded.has("proxyUser"))
        assertFalse(encoded.has("proxyPass"))
    }
}
