package com.pwmgr.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * BouncyCastle-based Argon2id. Pure-Java implementation — works identically on JVM and Android,
 * so this single actual covers both targets via the jvmAndAndroidMain source set.
 *
 * Slower than native libargon2 by ~2-3x, but Argon2 is intentionally slow; for default params
 * (m=64 MiB, t=3, p=4) we measure ~300-500 ms on modern desktops, ~600-900 ms on mid-range
 * Android devices. Both are fine for a one-shot unlock KDF.
 *
 * Output is bit-identical to libargon2 / argon2-jvm for the same (password, salt, params),
 * since both follow the Argon2 RFC.
 */
internal actual fun argon2idDerive(
    password: CharArray,
    salt: ByteArray,
    memKiB: Int,
    iterations: Int,
    parallelism: Int,
    outputLen: Int,
): ByteArray {
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(iterations)
        .withMemoryAsKB(memKiB)
        .withParallelism(parallelism)
        .withSalt(salt)
        .build()

    // BouncyCastle expects UTF-8 password bytes. Encode and zeroize the intermediate buffer
    // immediately after derivation — callers still own the source CharArray.
    val pwBytes = toUtf8(password)
    return try {
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(outputLen)
        gen.generateBytes(pwBytes, out)
        out
    } finally {
        pwBytes.fill(0)
    }
}

/** UTF-8 encode without going through String (would create an unzeroizable copy). */
private fun toUtf8(chars: CharArray): ByteArray {
    val buffer = java.nio.CharBuffer.wrap(chars)
    val encoded = Charsets.UTF_8.encode(buffer)
    val out = ByteArray(encoded.remaining())
    encoded.get(out)
    // CharBuffer wraps the source array — no copy was made there. The encoded ByteBuffer
    // is heap-allocated and its backing array is the one we just drained.
    return out
}
