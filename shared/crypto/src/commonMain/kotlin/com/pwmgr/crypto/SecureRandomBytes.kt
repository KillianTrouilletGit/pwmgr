package com.pwmgr.crypto

/**
 * Cryptographically secure random bytes from the platform CSPRNG.
 * On JVM/Android this delegates to [java.security.SecureRandom].
 *
 * @throws IllegalArgumentException if [length] is negative
 */
expect fun secureRandomBytes(length: Int): ByteArray
