package com.pwmgr.crypto

/**
 * Authenticated encryption with associated data using AES-256-GCM.
 *
 * Invariants enforced at this layer:
 *  - key.size == 32           (AES-256)
 *  - nonce.size == 12         (96-bit GCM nonce; the standard)
 *  - tag is 16 bytes, appended to the ciphertext (JCA convention)
 *  - aad is authenticated but not encrypted
 *
 * Decryption that fails authentication throws [AeadAuthenticationException]. Callers MUST treat
 * this as either wrong key or tampered ciphertext and MUST NOT leak any plaintext-shaped data
 * back to the user.
 */
object Aead {
    const val KEY_BYTES: Int = 32
    const val NONCE_BYTES: Int = 12
    const val TAG_BYTES: Int = 16

    /**
     * Encrypts [plaintext] under AES-256-GCM. Output = ciphertext || 16-byte-tag.
     */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        requireKey(key); requireNonce(nonce)
        return aesGcmEncrypt(key, nonce, plaintext, aad)
    }

    /**
     * Decrypts [ciphertext] (which includes the trailing 16-byte tag). Throws
     * [AeadAuthenticationException] on tag mismatch or any authentication failure.
     */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        requireKey(key); requireNonce(nonce)
        require(ciphertext.size >= TAG_BYTES) { "ciphertext must be at least $TAG_BYTES bytes (got ${ciphertext.size})" }
        return aesGcmDecrypt(key, nonce, ciphertext, aad)
    }

    private fun requireKey(key: ByteArray) =
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes (got ${key.size})" }

    private fun requireNonce(nonce: ByteArray) =
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes (got ${nonce.size})" }
}

/** Thrown when AES-GCM authentication fails: either wrong key, wrong nonce, or tampered data. */
class AeadAuthenticationException(cause: Throwable? = null) :
    RuntimeException("AEAD authentication failed", cause)

internal expect fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
internal expect fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
