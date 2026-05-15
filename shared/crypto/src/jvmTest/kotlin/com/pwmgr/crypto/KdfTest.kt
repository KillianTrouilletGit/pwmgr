package com.pwmgr.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Argon2 tests use the minimum permitted parameters (memKiB=19456, iter=2, par=1) to keep
 * the test suite under a few seconds. The production defaults (memKiB=65536, iter=3, par=4)
 * are exercised end-to-end in VaultFileTest once.
 */
class KdfTest {

    private val salt = ByteArray(32) { it.toByte() }
    private val testMem = Kdf.MIN_MEM_KIB
    private val testIter = Kdf.MIN_ITERATIONS
    private val testPar = 1

    @Test
    fun derive_is_deterministic() {
        val k1 = Kdf.derive("hunter2".toCharArray(), salt, testMem, testIter, testPar)
        val k2 = Kdf.derive("hunter2".toCharArray(), salt, testMem, testIter, testPar)
        assertContentEquals(k1, k2)
        kotlin.test.assertEquals(32, k1.size)
    }

    @Test
    fun different_password_produces_different_key() {
        val k1 = Kdf.derive("hunter2".toCharArray(), salt, testMem, testIter, testPar)
        val k2 = Kdf.derive("hunter3".toCharArray(), salt, testMem, testIter, testPar)
        assertNotEquals(k1.toList(), k2.toList())
    }

    @Test
    fun different_salt_produces_different_key() {
        val otherSalt = ByteArray(32) { (it + 1).toByte() }
        val k1 = Kdf.derive("hunter2".toCharArray(), salt, testMem, testIter, testPar)
        val k2 = Kdf.derive("hunter2".toCharArray(), otherSalt, testMem, testIter, testPar)
        assertNotEquals(k1.toList(), k2.toList())
    }

    @Test
    fun salt_size_floor_enforced() {
        assertFailsWith<IllegalArgumentException> {
            Kdf.derive("pw".toCharArray(), ByteArray(31), testMem, testIter, testPar)
        }
    }

    @Test
    fun mem_floor_enforced() {
        assertFailsWith<IllegalArgumentException> {
            Kdf.derive("pw".toCharArray(), salt, Kdf.MIN_MEM_KIB - 1, testIter, testPar)
        }
    }

    @Test
    fun iteration_floor_enforced() {
        assertFailsWith<IllegalArgumentException> {
            Kdf.derive("pw".toCharArray(), salt, testMem, 1, testPar)
        }
    }

    @Test
    fun parallelism_bounds_enforced() {
        assertFailsWith<IllegalArgumentException> {
            Kdf.derive("pw".toCharArray(), salt, testMem, testIter, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Kdf.derive("pw".toCharArray(), salt, testMem, testIter, Kdf.MAX_PARALLELISM + 1)
        }
    }

    @Test
    fun empty_password_rejected() {
        assertFailsWith<IllegalArgumentException> {
            Kdf.derive(CharArray(0), salt, testMem, testIter, testPar)
        }
    }
}
