package com.pwmgr.core.totp

import com.pwmgr.crypto.Hmac

/**
 * RFC 6238 TOTP generator. Combines a base32-encoded shared secret with the current Unix
 * time to produce a rotating numeric code.
 *
 * Defaults follow industry consensus (Google Authenticator, Authy, Bitwarden, 1Password):
 *  - period: 30 seconds
 *  - digits: 6
 *  - algorithm: HMAC-SHA1 (the only one mass-deployed; SHA-256/512 variants exist but few
 *    services issue them, and many TOTP apps only support SHA-1)
 *
 * Test vectors from RFC 6238 Appendix B are covered in TotpTest.
 */
object Totp {

    const val DEFAULT_PERIOD_SECONDS: Int = 30
    const val DEFAULT_DIGITS: Int = 6

    /**
     * Computes the TOTP code for [secretBase32] at [unixTimeSeconds].
     *
     * @param secretBase32 the shared secret, base32-encoded (case-insensitive, optional `=` padding).
     * @return zero-padded numeric string of length [digits]
     */
    fun generate(
        secretBase32: String,
        unixTimeSeconds: Long,
        periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
        digits: Int = DEFAULT_DIGITS,
    ): String {
        require(periodSeconds > 0) { "periodSeconds must be > 0" }
        require(digits in 6..8) { "digits must be 6, 7, or 8" }
        val secret = Base32.decode(secretBase32)
        val counter = unixTimeSeconds / periodSeconds
        return hotp(secret, counter, digits)
    }

    /** Seconds until the current code rotates. */
    fun secondsRemaining(unixTimeSeconds: Long, periodSeconds: Int = DEFAULT_PERIOD_SECONDS): Int =
        (periodSeconds - (unixTimeSeconds % periodSeconds).toInt()).let {
            if (it == 0) periodSeconds else it
        }

    /** RFC 4226 HOTP — the building block TOTP uses with `counter = time / period`. */
    private fun hotp(secret: ByteArray, counter: Long, digits: Int): String {
        val counterBytes = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        val mac = Hmac.sha1(secret, counterBytes)

        // Dynamic truncation per RFC 4226 §5.3.
        val offset = (mac[mac.size - 1].toInt() and 0x0f)
        val binCode =
            ((mac[offset].toInt() and 0x7f) shl 24) or
                ((mac[offset + 1].toInt() and 0xff) shl 16) or
                ((mac[offset + 2].toInt() and 0xff) shl 8) or
                (mac[offset + 3].toInt() and 0xff)

        val modulus = intPow10(digits)
        val code = (binCode % modulus).toString()
        return code.padStart(digits, '0')
    }

    private fun intPow10(n: Int): Int {
        var r = 1
        repeat(n) { r *= 10 }
        return r
    }
}
