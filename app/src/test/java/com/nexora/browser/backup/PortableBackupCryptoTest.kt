package com.nexora.browser.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupCryptoTest {
    @Test
    fun encryptsAndDecryptsPayload() {
        val password = "a-long-backup-password".toCharArray()
        val plain = "profile and proxy credentials".toByteArray()
        assertArrayEquals(plain, PortableBackupCrypto.decrypt(PortableBackupCrypto.encrypt(plain, password), password))
    }

    @Test
    fun usesFreshSaltAndNonceForEachBackup() {
        val password = "a-long-backup-password".toCharArray()
        val plain = "same payload".toByteArray()
        assertNotEquals(
            PortableBackupCrypto.encrypt(plain, password).toList(),
            PortableBackupCrypto.encrypt(plain, password).toList()
        )
    }

    @Test
    fun rejectsWrongPasswordAndTampering() {
        val plain = "secret".toByteArray()
        val encrypted = PortableBackupCrypto.encrypt(plain, "correct-password".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCrypto.decrypt(encrypted, "incorrect-password".toCharArray())
        }
        val tampered = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCrypto.decrypt(tampered, "correct-password".toCharArray())
        }
    }

    @Test
    fun rejectsShortPasswordsAndInvalidHeaders() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCrypto.encrypt(byteArrayOf(1), "short".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCrypto.decrypt(byteArrayOf(1, 2, 3, 4), "long-enough-password".toCharArray())
        }
    }
}
