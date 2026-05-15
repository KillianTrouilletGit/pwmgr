package com.pwmgr.crypto

import java.security.SecureRandom

private val rng: SecureRandom = SecureRandom()

actual fun secureRandomBytes(length: Int): ByteArray {
    require(length >= 0) { "length must be non-negative (got $length)" }
    val out = ByteArray(length)
    rng.nextBytes(out)
    return out
}
