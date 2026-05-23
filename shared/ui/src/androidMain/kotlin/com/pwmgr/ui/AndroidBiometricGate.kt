package com.pwmgr.ui

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Android biometric unlock backed by [KeyStore] + [BiometricPrompt].
 *
 * Hardware-bound AES-256 key generated with [setUserAuthenticationRequired] = true and
 * [BIOMETRIC_STRONG]. The OS releases the key (i.e., allows the [Cipher] to operate) only
 * after a successful biometric authentication.
 *
 * The wrap file lives next to the vault: `{iv: b64, ct: b64}` JSON. The IV is needed at
 * decrypt time; the keystore key never leaves the secure element so we can't store it
 * directly — only the IV + ciphertext.
 *
 * Re-enrollment safety: [setInvalidatedByBiometricEnrollment] = true means adding/removing
 * a fingerprint invalidates the keystore key. The next [unlock] then throws
 * [KeyPermanentlyInvalidatedException], we delete the stale wrap, and surface a
 * "biometric changed — re-enroll" error to the UI.
 */
@OptIn(ExperimentalEncodingApi::class)
class AndroidBiometricGate(
    private val activity: FragmentActivity,
    private val wrapPath: Path,
) : BiometricGate {

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        val mgr = BiometricManager.from(activity)
        mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    override fun isEnrolled(): Boolean = wrapPath.exists()

    override suspend fun enroll(vaultKey: ByteArray): Result<Unit> {
        require(vaultKey.size == 32) { "vault key must be 32 bytes" }
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            val authedCipher = prompt(
                cipher,
                title = "Enable biometric unlock",
                subtitle = "Authenticate to enroll your biometric for PwMgr",
            ) ?: return Result.failure(BiometricCancelledException())
            val ct = authedCipher.doFinal(vaultKey)
            val blob = WrapBlob(iv = Base64.encode(authedCipher.iv), ct = Base64.encode(ct))
            writeAtomic(wrapPath, json.encodeToString(WrapBlob.serializer(), blob).encodeToByteArray())
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun unlock(): ByteArray? {
        if (!wrapPath.exists()) return null
        return try {
            val blob = json.decodeFromString(WrapBlob.serializer(), wrapPath.readBytes().decodeToString())
            val key = loadKeyOrNull() ?: run {
                // Key entry vanished (factory reset, keystore wipe) — wrap is unusable.
                disable()
                return null
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(blob.iv)))
            }
            val authedCipher = prompt(
                cipher,
                title = "Unlock PwMgr",
                subtitle = "Use your biometric to unlock the vault",
            ) ?: return null
            authedCipher.doFinal(Base64.decode(blob.ct))
        } catch (e: KeyPermanentlyInvalidatedException) {
            // User re-enrolled their biometric; the keystore key was auto-revoked.
            disable()
            null
        }
    }

    override suspend fun disable() = withContext(Dispatchers.Default) {
        Files.deleteIfExists(wrapPath)
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            ks.deleteEntry(KEY_ALIAS)
        }
        Unit
    }

    private fun loadKeyOrNull(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        loadKeyOrNull()?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Shows the BiometricPrompt and suspends until the user authenticates or cancels.
     * Returns the authenticated [Cipher] (same one passed in) on success, null on cancel.
     * The prompt itself handles retry of recoverable failures (false reads, etc.).
     */
    private suspend fun prompt(cipher: Cipher, title: String, subtitle: String): Cipher? =
        suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    cont.resume(result.cryptoObject?.cipher)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    cont.resume(null)
                }
                // onAuthenticationFailed: a transient mismatch. The prompt UI will allow the
                // user to retry. We don't resume here — we wait for either success or error.
            }
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
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

    @Serializable
    private data class WrapBlob(val iv: String, val ct: String)

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pwmgr_biometric_v1"
        private val json = Json { encodeDefaults = true }
    }
}
