package com.pwmgr.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AeadTest {

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (0x10 + it).toByte() }
    private val aad = "hello-aad".encodeToByteArray()
    private val plaintext = "a very secret payload — déjà vu".encodeToByteArray()

    @Test
    fun roundTrip_recovers_plaintext() {
        val ct = Aead.encrypt(key, nonce, plaintext, aad)
        val pt = Aead.decrypt(key, nonce, ct, aad)
        assertContentEquals(plaintext, pt)
    }

    @Test
    fun ciphertext_is_longer_than_plaintext_by_tag() {
        val ct = Aead.encrypt(key, nonce, plaintext, aad)
        kotlin.test.assertEquals(plaintext.size + Aead.TAG_BYTES, ct.size)
    }

    @Test
    fun wrong_key_fails_authentication() {
        val ct = Aead.encrypt(key, nonce, plaintext, aad)
        val badKey = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<AeadAuthenticationException> { Aead.decrypt(badKey, nonce, ct, aad) }
    }

    @Test
    fun wrong_nonce_fails_authentication() {
        val ct = Aead.encrypt(key, nonce, plaintext, aad)
        val badNonce = nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<AeadAuthenticationException> { Aead.decrypt(key, badNonce, ct, aad) }
    }

    @Test
    fun wrong_aad_fails_authentication() {
        val ct = Aead.encrypt(key, nonce, plaintext, aad)
        val badAad = "different-aad".encodeToByteArray()
        assertFailsWith<AeadAuthenticationException> { Aead.decrypt(key, nonce, ct, badAad) }
    }

    @Test
    fun flipping_any_ciphertext_byte_fails_authentication() {
        val ct = Aead.encrypt(key, nonce, plaintext, aad)
        for (i in ct.indices) {
            val tampered = ct.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<AeadAuthenticationException>(
                message = "tamper at byte $i was not detected",
            ) { Aead.decrypt(key, nonce, tampered, aad) }
        }
    }

    @Test
    fun nonce_size_validation() {
        assertFailsWith<IllegalArgumentException> {
            Aead.encrypt(key, ByteArray(11), plaintext, aad)
        }
        assertFailsWith<IllegalArgumentException> {
            Aead.encrypt(key, ByteArray(13), plaintext, aad)
        }
    }

    @Test
    fun key_size_validation() {
        assertFailsWith<IllegalArgumentException> {
            Aead.encrypt(ByteArray(16), nonce, plaintext, aad)
        }
    }

    @Test
    fun two_encryptions_with_random_nonces_differ() {
        // Smoke test that distinct nonces produce distinct ciphertexts (sanity check on the path).
        val n1 = secureRandomBytes(12)
        val n2 = secureRandomBytes(12)
        val c1 = Aead.encrypt(key, n1, plaintext, aad)
        val c2 = Aead.encrypt(key, n2, plaintext, aad)
        assertNotEquals(c1.toList(), c2.toList())
    }

    @Test
    fun csprng_produces_distinct_nonces() {
        val seen = hashSetOf<List<Byte>>()
        repeat(10_000) {
            val n = secureRandomBytes(12)
            kotlin.test.assertTrue(seen.add(n.toList()), "duplicate nonce after $it iterations")
        }
    }
}
