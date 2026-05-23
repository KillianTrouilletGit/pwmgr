package com.pwmgr.core.totp

/**
 * Base32 decoder per RFC 4648 — the encoding TOTP secrets ship in (`JBSWY3DPEHPK3PXP`-style).
 * Accepts input with mixed case, optional `=` padding, and spaces (Google Authenticator
 * QR codes sometimes include them for human readability).
 *
 * Not a general-purpose codec; we only ever DECODE here. Encoding lands when (if) we add
 * "show secret as QR" to the EntryEditor.
 */
internal object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray {
        val normalized = input.uppercase().filter { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        val cleaned = normalized.trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)

        val out = ArrayList<Byte>(cleaned.length * 5 / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        for (ch in cleaned) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { "invalid base32 char '$ch' in TOTP secret" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer ushr bitsLeft) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}
