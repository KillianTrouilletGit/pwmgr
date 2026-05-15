package com.pwmgr.storage

import com.pwmgr.crypto.Aead
import com.pwmgr.crypto.secureRandomBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Stores the OAuth refresh token in a small file next to the vault, encrypted with AES-256-GCM
 * under the unlocked vault key (VK). Without VK the file is opaque ciphertext.
 *
 * Rationale: VK is already the user's most-trusted secret. Reusing it for the token avoids
 * pulling in platform secure-storage APIs (DPAPI on Windows, Android Keystore) for v1, and
 * makes the security story uniform across platforms.
 *
 * On-disk format (JSON):
 * ```
 * { "alg": "AES-256-GCM", "nonce": "<b64-12B>", "ct": "<b64>" }
 * ```
 * AAD is the fixed bytes "pwmgr-token-v1" so this ciphertext can't be substituted for vault
 * payload ciphertext.
 */
@OptIn(ExperimentalEncodingApi::class)
class FileTokenStore(private val path: Path) : TokenStore {

    override fun isConfigured(): Boolean = path.exists()

    override suspend fun read(vaultKey: ByteArray): OAuthAccount? = withContext(Dispatchers.IO) {
        if (!path.exists()) return@withContext null
        val raw = path.readBytes()
        val blob = json.decodeFromString(EncryptedBlob.serializer(), raw.decodeToString())
        val nonce = Base64.decode(blob.nonce)
        val ct = Base64.decode(blob.ct)
        val plaintext = Aead.decrypt(vaultKey, nonce, ct, AAD)
        json.decodeFromString(OAuthAccount.serializer(), plaintext.decodeToString())
    }

    override suspend fun write(vaultKey: ByteArray, account: OAuthAccount): Unit = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(OAuthAccount.serializer(), account).encodeToByteArray()
        val nonce = secureRandomBytes(Aead.NONCE_BYTES)
        val ct = Aead.encrypt(vaultKey, nonce, plaintext, AAD)
        val blob = EncryptedBlob(
            alg = "AES-256-GCM",
            nonce = Base64.encode(nonce),
            ct = Base64.encode(ct),
        )
        val body = json.encodeToString(EncryptedBlob.serializer(), blob).encodeToByteArray()

        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newOutputStream(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        ).use { it.write(body) }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        Files.deleteIfExists(path)
        Unit
    }

    @Serializable
    private data class EncryptedBlob(val alg: String, val nonce: String, val ct: String)

    companion object {
        private val AAD: ByteArray = "pwmgr-token-v1".encodeToByteArray()
        private val json = Json { encodeDefaults = true }
    }
}

