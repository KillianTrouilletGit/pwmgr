package com.pwmgr.crypto

/**
 * Overwrites a sensitive byte array with zeros.
 *
 * Best-effort: the JVM may have copied this buffer during GC. Still worth doing for in-memory
 * scraping resistance. Callers MUST invoke this on any [ByteArray] that held key material,
 * derived keys, or plaintext payloads, as soon as the data is no longer needed.
 */
fun ByteArray.zeroize() {
    for (i in indices) this[i] = 0
}

/**
 * Overwrites a sensitive char array with spaces.
 * Used for master-password input that has not yet been encoded to bytes for Argon2id.
 */
fun CharArray.zeroize() {
    for (i in indices) this[i] = ' '
}
