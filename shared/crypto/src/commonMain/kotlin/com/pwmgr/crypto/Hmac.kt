package com.pwmgr.crypto

/**
 * HMAC-SHA1. Used only by [com.pwmgr.core.totp.Totp] to compute RFC 4226 / 6238 codes —
 * NOT a general-purpose primitive. The vault's confidentiality and integrity rely on
 * AES-256-GCM; HMAC-SHA1 is here strictly because TOTP's spec mandates SHA-1.
 *
 * If you find yourself reaching for `Hmac.sha1` for anything other than TOTP, stop and
 * use AEAD or HKDF (commented in CRYPTO.md §2).
 */
object Hmac {
    /** HMAC-SHA1 of [data] under [key]. Output is 20 bytes. */
    fun sha1(key: ByteArray, data: ByteArray): ByteArray = hmacSha1(key, data)
}

internal expect fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray
