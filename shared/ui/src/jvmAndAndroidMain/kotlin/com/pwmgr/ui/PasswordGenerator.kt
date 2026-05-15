package com.pwmgr.ui

import java.security.SecureRandom

/**
 * Cryptographic password generator. SecureRandom + rejection-free sampling via
 * SecureRandom.nextInt(bound), which is unbiased by construction.
 *
 * Guarantees at least one character from each enabled class when length permits.
 */
object PasswordGenerator {

    data class Options(
        val length: Int = 20,
        val lowercase: Boolean = true,
        val uppercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
    ) {
        init {
            require(length in MIN_LENGTH..MAX_LENGTH) { "length must be in $MIN_LENGTH..$MAX_LENGTH (got $length)" }
            require(lowercase || uppercase || digits || symbols) { "at least one character class must be enabled" }
        }
    }

    const val MIN_LENGTH: Int = 4
    const val MAX_LENGTH: Int = 128

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/~"

    private val rng = SecureRandom()

    fun generate(options: Options): String {
        val classes = buildList {
            if (options.lowercase) add(LOWER)
            if (options.uppercase) add(UPPER)
            if (options.digits) add(DIGITS)
            if (options.symbols) add(SYMBOLS)
        }
        val alphabet = classes.joinToString("")

        val result = CharArray(options.length)
        for ((i, cls) in classes.withIndex()) {
            if (i >= options.length) break
            result[i] = cls[rng.nextInt(cls.length)]
        }
        for (i in classes.size until options.length) {
            result[i] = alphabet[rng.nextInt(alphabet.length)]
        }
        // Shuffle so seed positions are not deterministic.
        for (i in result.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val tmp = result[i]; result[i] = result[j]; result[j] = tmp
        }
        return String(result).also { result.fill(' ') }
    }
}
