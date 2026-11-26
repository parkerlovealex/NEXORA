package com.nexora.browser.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-based encryption for portable NEXORA backup files. */
object PortableBackupCrypto {
    const val MIN_PASSWORD_LENGTH = 10
    const val MAX_BACKUP_BYTES = 20 * 1024 * 1024

    private val MAGIC = byteArrayOf(0x4e, 0x58, 0x42, 0x4b) // NXBK
    private const val FORMAT_VERSION = 1
    private const val ITERATIONS = 210_000
    private const val MIN_ITERATIONS = 150_000
    private const val MAX_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Backup password must contain at least $MIN_PASSWORD_LENGTH characters."
        }
        require(plaintext.size <= MAX_BACKUP_BYTES) { "Backup data exceeds the 20 MB limit." }

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, ITERATIONS)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)
            require(ciphertext.size <= MAX_BACKUP_BYTES + 32) { "Encrypted backup is too large." }

            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.write(MAGIC)
                    out.writeInt(FORMAT_VERSION)
                    out.writeInt(ITERATIONS)
                    out.writeByte(salt.size)
                    out.write(salt)
                    out.writeByte(iv.size)
                    out.write(iv)
                    out.writeInt(ciphertext.size)
                    out.write(ciphertext)
                }
                bytes.toByteArray()
            }
        } finally {
            key.encoded?.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Backup password must contain at least $MIN_PASSWORD_LENGTH characters."
        }
        require(blob.size in 32..(MAX_BACKUP_BYTES + 128)) { "Backup file size is invalid." }

        val input = DataInputStream(ByteArrayInputStream(blob))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "This is not a NEXORA encrypted backup." }
        val version = input.readInt()
        require(version == FORMAT_VERSION) { "This backup format version is not supported." }
        val iterations = input.readInt()
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Backup encryption parameters are invalid." }

        val saltLength = input.readUnsignedByte()
        require(saltLength == SALT_BYTES) { "Backup salt is invalid." }
        val salt = ByteArray(saltLength)
        input.readFully(salt)
        val ivLength = input.readUnsignedByte()
        require(ivLength == IV_BYTES) { "Backup nonce is invalid." }
        val iv = ByteArray(ivLength)
        input.readFully(iv)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 16..(MAX_BACKUP_BYTES + 32)) { "Backup payload size is invalid." }
        require(ciphertextLength == input.available()) { "Backup file is truncated or has trailing data." }
        val ciphertext = ByteArray(ciphertextLength)
        input.readFully(ciphertext)

        val key = deriveKey(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).also {
                require(it.size <= MAX_BACKUP_BYTES) { "Decrypted backup exceeds the 20 MB limit." }
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect password or damaged backup file.")
        } finally {
            key.encoded?.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }
}
