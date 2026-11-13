package com.carevoice.documentation.infrastructure.crypto

import com.carevoice.documentation.application.FieldEncryptor
import com.carevoice.documentation.config.EncryptionConfig
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Adapter: AES-256-GCM field encryption (docs/CONTRACTS.md, section 11).
 *
 * Format `<keyId>:<base64(iv | ciphertext | tag)>` with a random 12-byte IV per value:
 * - GCM is authenticated: a modified ciphertext fails to decrypt instead of yielding garbage.
 * - A fresh IV per value means equal plaintexts have different ciphertexts (no "same name" leaks), and IV reuse
 *   under one key, which breaks GCM completely, cannot happen by construction.
 * - The key id prefix makes rotation possible: new values use the active key, old ones stay readable with theirs.
 * - The key id is bound as additional authenticated data, so a ciphertext cannot be relabelled to another key.
 */
class AesGcmFieldEncryptor(
    private val activeKeyId: String,
    keys: Map<String, ByteArray>,
    private val random: SecureRandom = SecureRandom(),
) : FieldEncryptor {
    private val keys: Map<String, SecretKeySpec> =
        keys.mapValues { (id, bytes) ->
            require(bytes.size == KEY_BYTES) { "key '$id' must be $KEY_BYTES bytes" }
            SecretKeySpec(bytes, "AES")
        }

    init {
        require(activeKeyId in this.keys) { "active key id '$activeKeyId' has no key" }
    }

    constructor(config: EncryptionConfig) : this(config.activeKeyId, config.keys)

    /** Encrypts with the active key and a fresh random IV (the one-time start value AES-GCM needs). */
    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.getValue(activeKeyId), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(activeKeyId.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return activeKeyId + SEPARATOR + Base64.getEncoder().encodeToString(iv + sealed)
    }

    /**
     * Decrypts with the key named in the prefix, so values written with an older key still work.
     * Throws [DecryptionException] for an unknown key, bad base64, a too-short value or a changed ciphertext.
     */
    override fun decrypt(ciphertext: String): String {
        // Step 1: the prefix names the key; an unknown key id fails at once.
        val keyId = ciphertext.substringBefore(SEPARATOR, missingDelimiterValue = "")
        val key = keys[keyId] ?: throw DecryptionException("Unknown key id")
        // Step 2: decode the rest from base64. It must be long enough for the IV and the tag (the integrity
        // check).
        val bytes =
            try {
                Base64.getDecoder().decode(ciphertext.substringAfter(SEPARATOR))
            } catch (_: IllegalArgumentException) {
                throw DecryptionException("Ciphertext is not valid base64")
            }
        if (bytes.size < IV_BYTES + TAG_BITS / 8) throw DecryptionException("Ciphertext is too short")
        // Step 3: set up AES-GCM with that key and the IV from the first bytes. The key id is checked too,
        // as extra authenticated data (AAD).
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        cipher.updateAAD(keyId.toByteArray(Charsets.UTF_8))
        // Step 4: decrypt. A changed value fails the tag check and is rejected.
        return try {
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            throw DecryptionException("Ciphertext failed authentication")
        }
    }

    companion object {
        /** AES in GCM mode. GCM works like a stream cipher, so it needs no padding (`NoPadding`). */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEPARATOR = ':'

        /** 32 bytes = a 256-bit key, which makes this AES-256. */
        const val KEY_BYTES = 32

        /** 12 bytes (96 bits): the IV length NIST SP 800-38D recommends for GCM. */
        const val IV_BYTES = 12

        /** 128 bits (16 bytes): the full-size GCM authentication tag, the largest GCM allows. */
        const val TAG_BITS = 128
    }
}

/** Never carries the ciphertext or plaintext in its message. */
class DecryptionException(message: String) : RuntimeException(message)
