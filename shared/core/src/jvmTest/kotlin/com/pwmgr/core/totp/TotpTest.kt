package com.pwmgr.core.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TotpTest {

    /**
     * RFC 6238 Appendix B test vectors. The secret in the RFC is the ASCII string
     * "12345678901234567890" (or "...678901234567890123456789012" for SHA-256/SHA-512).
     * Base32-encoded that ASCII secret is "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ".
     *
     * RFC uses SHA-1 with 8-digit codes. We support 6-8 digits — checking 8 to verify.
     */
    @Test
    fun rfc6238_sha1_vectors() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val cases = listOf(
            59L           to "94287082",
            1111111109L   to "07081804",
            1111111111L   to "14050471",
            1234567890L   to "89005924",
            2000000000L   to "69279037",
            20000000000L  to "65353130",
        )
        for ((time, expected) in cases) {
            val code = Totp.generate(secret, time, digits = 8)
            assertEquals(expected, code, "T=$time")
        }
    }

    @Test
    fun default_period_is_30s_and_default_digits_is_6() {
        // Code at T=0 should match the SHA-1 RFC vector truncated to 6 digits.
        // RFC says "94287082" for T=59 with 8 digits → last 6 = "287082".
        val code = Totp.generate("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59L)
        assertEquals("287082", code)
    }

    @Test
    fun seconds_remaining_wraps_at_period_boundary() {
        assertEquals(30, Totp.secondsRemaining(0L))       // exact boundary → full period left
        assertEquals(29, Totp.secondsRemaining(1L))
        assertEquals(1,  Totp.secondsRemaining(29L))
        assertEquals(30, Totp.secondsRemaining(30L))
    }

    @Test
    fun base32_decode_handles_padding_spaces_and_case() {
        // "JBSWY3DPEHPK3PXP" base32-decodes to "Hello!worldP" — used as a smoke vector.
        val a = Base32.decode("jbswy3dpehpk3pxp")
        val b = Base32.decode("JBSWY3DP EHPK3PXP===")
        val c = Base32.decode("JBSWY3DPEHPK3PXP")
        assertEquals(a.toList(), c.toList())
        assertEquals(b.toList(), c.toList())
    }

    @Test
    fun base32_rejects_invalid_chars() {
        assertFailsWith<IllegalArgumentException> { Base32.decode("ABCD!EFG") }
    }

    @Test
    fun digits_must_be_in_range() {
        assertFailsWith<IllegalArgumentException> { Totp.generate("AA", 0L, digits = 5) }
        assertFailsWith<IllegalArgumentException> { Totp.generate("AA", 0L, digits = 9) }
    }

    @Test
    fun period_must_be_positive() {
        assertFailsWith<IllegalArgumentException> { Totp.generate("AA", 0L, periodSeconds = 0) }
    }
}
