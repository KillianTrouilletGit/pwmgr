package com.pwmgr.crypto

/**
 * Password-based key derivation using Argon2id, the only KDF permitted by the v1 vault format.
 *
 * Parameter floors enforced at the call site:
 *  - memKiB >= 19_456  (OWASP Argon2id minimum)
 *  - iterations >= 2
 *  - parallelism in 1..16
 *  - salt.size == 32
 *  - outputLen == 32 (we only derive 256-bit keys)
 *
 * The implementation MUST zero the password bytes it builds internally; the caller is
 * still responsible for zeroing the [password] CharArray afterwards.
 */
object Kdf {
    const val DEFAULT_MEM_KIB: Int = 65_536
    const val DEFAULT_ITERATIONS: Int = 3
    const val DEFAULT_PARALLELISM: Int = 4
    const val SALT_BYTES: Int = 32
    const val KEY_BYTES: Int = 32

    const val MIN_MEM_KIB: Int = 19_456
    const val MIN_ITERATIONS: Int = 2
    const val MAX_PARALLELISM: Int = 16

    /**
     * Derives a 32-byte key from [password] using Argon2id with the given parameters.
     *
     * @param password the master password as a CharArray (caller should zeroize after the call)
     * @param salt 32-byte random salt from the vault header
     * @param memKiB memory cost in KiB
     * @param iterations time cost
     * @param parallelism lanes
     * @return the derived 32-byte key
     * @throws IllegalArgumentException if any parameter is out of policy
     */
    fun derive(
        password: CharArray,
        salt: ByteArray,
        memKiB: Int = DEFAULT_MEM_KIB,
        iterations: Int = DEFAULT_ITERATIONS,
        parallelism: Int = DEFAULT_PARALLELISM,
    ): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes, got ${salt.size}" }
        require(memKiB >= MIN_MEM_KIB) { "memKiB must be >= $MIN_MEM_KIB (got $memKiB)" }
        require(iterations >= MIN_ITERATIONS) { "iterations must be >= $MIN_ITERATIONS (got $iterations)" }
        require(parallelism in 1..MAX_PARALLELISM) { "parallelism must be in 1..$MAX_PARALLELISM (got $parallelism)" }
        require(password.isNotEmpty()) { "password must not be empty" }
        return argon2idDerive(password, salt, memKiB, iterations, parallelism, KEY_BYTES)
    }
}

internal expect fun argon2idDerive(
    password: CharArray,
    salt: ByteArray,
    memKiB: Int,
    iterations: Int,
    parallelism: Int,
    outputLen: Int,
): ByteArray
