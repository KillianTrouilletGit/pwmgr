package com.pwmgr.ui

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Windows DPAPI-based convenience unlock. NOT gated by Windows Hello — DPAPI encrypts under
 * a key derived from the current Windows user's credential store, transparently. No prompt
 * fires when we encrypt or decrypt.
 *
 * Security profile:
 *  - Bound to the OS user account: a different Windows user on the same machine cannot
 *    decrypt this blob.
 *  - Survives reboot.
 *  - NOT bound to a biometric: anyone logged in as this Windows user can decrypt.
 *  - NOT portable: copying the blob to another machine yields ciphertext nobody can decrypt.
 *
 * This is weaker than Android's path. The plan accepted this asymmetry as the pragmatic
 * v1 — true Windows Hello requires WinRT KeyCredentialManager bindings that aren't first-class
 * from the JVM. See docs/CRYPTO.md §3.1.
 */
class WindowsDpapiGate(private val wrapPath: Path) : BiometricGate {

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Touch the API with a trivial round-trip to verify Crypt32 is reachable.
            val probe = byteArrayOf(0x42)
            val protected = Crypt32Util.cryptProtectData(probe, ENTROPY, 0, DESCRIPTION, null)
            val recovered = Crypt32Util.cryptUnprotectData(protected, ENTROPY, 0, null)
            recovered.contentEquals(probe)
        } catch (_: Throwable) {
            false
        }
    }

    override fun isEnrolled(): Boolean = wrapPath.exists()

    override suspend fun enroll(vaultKey: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(vaultKey.size == 32) { "vault key must be 32 bytes" }
            val protectedBlob = Crypt32Util.cryptProtectData(vaultKey, ENTROPY, 0, DESCRIPTION, null)
            writeAtomic(wrapPath, protectedBlob)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun unlock(): ByteArray? = withContext(Dispatchers.IO) {
        if (!wrapPath.exists()) return@withContext null
        try {
            val blob = wrapPath.readBytes()
            Crypt32Util.cryptUnprotectData(blob, ENTROPY, 0, null)
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun disable(): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(wrapPath)
        Unit
    }

    private fun writeAtomic(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newOutputStream(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        ).use { it.write(bytes) }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        // Entropy + description mix into DPAPI's per-call binding. ENTROPY makes the blob
        // useless even to other apps running as the same Windows user — they'd have to know
        // the exact entropy bytes used during encryption. DESCRIPTION is informational only
        // (shows in Windows audit log).
        private val ENTROPY: ByteArray = "pwmgr-biometric-wrap-v1".encodeToByteArray()
        private const val DESCRIPTION: String = "PwMgr vault key wrap"
    }
}
