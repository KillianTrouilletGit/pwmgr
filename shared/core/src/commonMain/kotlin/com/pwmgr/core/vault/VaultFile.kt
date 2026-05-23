package com.pwmgr.core.vault

import com.pwmgr.core.format.AEAD_ALG_AES256_GCM
import com.pwmgr.core.format.AeadBlob
import com.pwmgr.core.format.CanonicalJson
import com.pwmgr.core.format.CURRENT_VAULT_VERSION
import com.pwmgr.core.format.KDF_ALGO_ARGON2ID
import com.pwmgr.core.format.KdfParams
import com.pwmgr.core.format.VaultFileDto
import com.pwmgr.core.format.VaultMeta
import com.pwmgr.core.model.VaultPayload
import com.pwmgr.crypto.Aead
import com.pwmgr.crypto.AeadAuthenticationException
import com.pwmgr.crypto.Kdf
import com.pwmgr.crypto.secureRandomBytes
import com.pwmgr.crypto.zeroize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Stateless vault open/save operations. See docs/CRYPTO.md for the normative spec.
 *
 * Lifecycle:
 *  1. [create] generates a fresh VK and writes an empty payload.
 *  2. [unlock] derives MK from the password, decrypts wrap to recover VK, then decrypts payload.
 *  3. [save] re-encrypts the payload under the existing VK with a fresh nonce.
 *
 * VK is returned to the caller as a [VaultSession]; the caller is responsible for invoking
 * [VaultSession.lock] which zeros VK in memory.
 */
@OptIn(ExperimentalEncodingApi::class)
object VaultFile {

    private val json = Json {
        ignoreUnknownKeys = false       // strict at top level (see CRYPTO.md §4.3)
        prettyPrint = false
        encodeDefaults = true
    }

    /** Builds a new vault file from the given password and returns the serialized JSON bytes. */
    fun create(
        password: CharArray,
        deviceId: String,
        memKiB: Int = Kdf.DEFAULT_MEM_KIB,
        iterations: Int = Kdf.DEFAULT_ITERATIONS,
        parallelism: Int = Kdf.DEFAULT_PARALLELISM,
        nowIso: String,
    ): CreateResult {
        val salt = secureRandomBytes(Kdf.SALT_BYTES)
        val kdfParams = KdfParams(KDF_ALGO_ARGON2ID, Base64.encode(salt), memKiB, iterations, parallelism)
        val meta = VaultMeta(revision = 0L, modifiedAt = nowIso, deviceId = deviceId)
        val aad = computeAad(CURRENT_VAULT_VERSION, kdfParams)

        val vk = secureRandomBytes(Aead.KEY_BYTES)
        val wrapNonce = secureRandomBytes(Aead.NONCE_BYTES)
        val payloadNonce = secureRandomBytes(Aead.NONCE_BYTES)

        val mk = Kdf.derive(password, salt, memKiB, iterations, parallelism)
        try {
            val wrapCt = Aead.encrypt(mk, wrapNonce, vk, aad)
            val emptyPayload = json.encodeToString(VaultPayload.serializer(), VaultPayload()).encodeToByteArray()
            val payloadCt = Aead.encrypt(vk, payloadNonce, emptyPayload, aad)

            val dto = VaultFileDto(
                version = CURRENT_VAULT_VERSION,
                kdf = kdfParams,
                wrap = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(wrapNonce), Base64.encode(wrapCt)),
                payload = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(payloadNonce), Base64.encode(payloadCt)),
                meta = meta,
            )
            val bytes = json.encodeToString(VaultFileDto.serializer(), dto).encodeToByteArray()
            return CreateResult(bytes, VaultSession(vk.copyOf(), dto))
        } finally {
            mk.zeroize()
            vk.zeroize()
        }
    }

    /**
     * Decrypts the vault file bytes with [password], returning a [VaultSession] holding VK
     * and the parsed entries.
     *
     * @throws InvalidVaultFormatException if header invariants are violated
     * @throws WrongPasswordException if wrap decryption fails (wrong password or tampered wrap/header)
     * @throws CorruptVaultException if wrap decrypts successfully but payload decryption fails
     */
    fun unlock(fileBytes: ByteArray, password: CharArray): UnlockResult {
        val dto = parseAndValidate(fileBytes)
        val salt = Base64.decode(dto.kdf.salt)
        val wrapNonce = Base64.decode(dto.wrap.nonce)
        val wrapCt = Base64.decode(dto.wrap.ct)
        val payloadNonce = Base64.decode(dto.payload.nonce)
        val payloadCt = Base64.decode(dto.payload.ct)

        val aad = computeAad(dto.version, dto.kdf)
        val mk = Kdf.derive(password, salt, dto.kdf.memKiB, dto.kdf.iterations, dto.kdf.parallelism)
        val vk = try {
            Aead.decrypt(mk, wrapNonce, wrapCt, aad)
        } catch (e: AeadAuthenticationException) {
            throw WrongPasswordException(e)
        } finally {
            mk.zeroize()
        }

        val plaintext = try {
            Aead.decrypt(vk, payloadNonce, payloadCt, aad)
        } catch (e: AeadAuthenticationException) {
            vk.zeroize()
            throw CorruptVaultException("payload authentication failed after wrap succeeded — file is corrupted", e)
        }

        val payload = try {
            json.decodeFromString(VaultPayload.serializer(), plaintext.decodeToString())
        } catch (e: Throwable) {
            vk.zeroize()
            plaintext.zeroize()
            throw CorruptVaultException("payload JSON is malformed", e)
        }
        plaintext.zeroize()
        return UnlockResult(VaultSession(vk, dto), payload)
    }

    /**
     * Opens a vault using a directly-supplied Vault Key (i.e., obtained from biometric unlock),
     * skipping Argon2id and wrap.ct decryption entirely. Returns the same [UnlockResult] as
     * password-based [unlock] would, with a fresh [VaultSession] holding the VK.
     *
     * The caller is responsible for source-of-VK trust: typically [vaultKey] came back from
     * a hardware-gated biometric flow that proves user presence.
     *
     * @throws CorruptVaultException if the VK can't decrypt the payload (vault may have been
     *   re-encrypted under a new password on another device — user must re-enter master password)
     */
    fun unlockWithVaultKey(fileBytes: ByteArray, vaultKey: ByteArray): UnlockResult {
        val dto = parseAndValidate(fileBytes)
        val payloadNonce = Base64.decode(dto.payload.nonce)
        val payloadCt = Base64.decode(dto.payload.ct)
        val aad = computeAad(dto.version, dto.kdf)
        val plaintext = try {
            Aead.decrypt(vaultKey, payloadNonce, payloadCt, aad)
        } catch (e: AeadAuthenticationException) {
            throw CorruptVaultException(
                "Biometric VK does not match this vault. The master password may have been changed elsewhere — re-enter the current password.",
                e,
            )
        }
        val payload = try {
            json.decodeFromString(VaultPayload.serializer(), plaintext.decodeToString())
        } catch (e: Throwable) {
            plaintext.zeroize()
            throw CorruptVaultException("payload JSON is malformed", e)
        }
        plaintext.zeroize()
        return UnlockResult(VaultSession(vaultKey.copyOf(), dto), payload)
    }

    /**
     * Decrypts a remote vault file using an already-unlocked Vault Key, skipping master-password
     * derivation entirely. Used by the sync engine to read a remote vault without prompting
     * the user again.
     *
     * Caveat: this only works when the remote was encrypted under the same VK we hold (i.e.,
     * same vault, same master password). If the master password was changed on another device,
     * the remote's `wrap.ct` was re-encrypted under a new MK — its VK is the same, so this
     * still works. If two devices ever created independent vaults with the same Drive account,
     * decryption will fail with [AeadAuthenticationException] propagated up.
     */
    fun decryptWithVaultKey(fileBytes: ByteArray, vaultKey: ByteArray): VaultPayload {
        val dto = parseAndValidate(fileBytes)
        val payloadNonce = Base64.decode(dto.payload.nonce)
        val payloadCt = Base64.decode(dto.payload.ct)
        val aad = computeAad(dto.version, dto.kdf)
        val plaintext = Aead.decrypt(vaultKey, payloadNonce, payloadCt, aad)
        return try {
            json.decodeFromString(VaultPayload.serializer(), plaintext.decodeToString())
        } finally {
            plaintext.zeroize()
        }
    }

    /**
     * Re-encrypts [newPayload] under the session's VK and returns the new serialized bytes.
     * Increments [VaultMeta.revision] and updates [VaultMeta.modifiedAt]. The on-disk wrap is
     * NOT changed (AAD does not include meta — see CRYPTO.md §5.4).
     */
    fun save(session: VaultSession, newPayload: VaultPayload, nowIso: String): ByteArray {
        val previous = session.dto
        val newMeta = previous.meta.copy(revision = previous.meta.revision + 1, modifiedAt = nowIso)
        val aad = computeAad(previous.version, previous.kdf)
        val nonce = secureRandomBytes(Aead.NONCE_BYTES)
        val plaintext = json.encodeToString(VaultPayload.serializer(), newPayload).encodeToByteArray()
        val ct = try {
            Aead.encrypt(session.vaultKey, nonce, plaintext, aad)
        } finally {
            plaintext.zeroize()
        }
        val newDto = previous.copy(
            payload = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(nonce), Base64.encode(ct)),
            meta = newMeta,
        )
        session.dto = newDto
        return json.encodeToString(VaultFileDto.serializer(), newDto).encodeToByteArray()
    }

    /** Computes the canonical-JSON AAD over `{version, kdf}` per CRYPTO.md §4.2 / §5.4. */
    fun computeAad(version: Int, kdf: KdfParams): ByteArray {
        val element: JsonObject = buildJsonObject {
            put("version", version)
            put(
                "kdf",
                buildJsonObject {
                    put("algo", kdf.algo)
                    put("salt", kdf.salt)
                    put("memKiB", kdf.memKiB)
                    put("iter", kdf.iterations)
                    put("par", kdf.parallelism)
                },
            )
        }
        return CanonicalJson.encode(element)
    }

    private fun parseAndValidate(fileBytes: ByteArray): VaultFileDto {
        val dto = try {
            json.decodeFromString(VaultFileDto.serializer(), fileBytes.decodeToString())
        } catch (e: Throwable) {
            throw InvalidVaultFormatException("malformed vault JSON", e)
        }
        if (dto.version != CURRENT_VAULT_VERSION) {
            throw InvalidVaultFormatException("unsupported vault version ${dto.version} (this build knows version $CURRENT_VAULT_VERSION)")
        }
        if (dto.kdf.algo != KDF_ALGO_ARGON2ID) {
            throw InvalidVaultFormatException("unsupported kdf algo '${dto.kdf.algo}'")
        }
        if (dto.kdf.memKiB < Kdf.MIN_MEM_KIB) {
            throw InvalidVaultFormatException("kdf.memKiB ${dto.kdf.memKiB} below floor ${Kdf.MIN_MEM_KIB}")
        }
        if (dto.kdf.iterations < Kdf.MIN_ITERATIONS) {
            throw InvalidVaultFormatException("kdf.iter ${dto.kdf.iterations} below floor ${Kdf.MIN_ITERATIONS}")
        }
        if (dto.kdf.parallelism !in 1..Kdf.MAX_PARALLELISM) {
            throw InvalidVaultFormatException("kdf.par ${dto.kdf.parallelism} outside 1..${Kdf.MAX_PARALLELISM}")
        }
        if (dto.wrap.alg != AEAD_ALG_AES256_GCM || dto.payload.alg != AEAD_ALG_AES256_GCM) {
            throw InvalidVaultFormatException("unsupported AEAD alg")
        }
        val saltSize = Base64.decode(dto.kdf.salt).size
        if (saltSize != Kdf.SALT_BYTES) {
            throw InvalidVaultFormatException("kdf.salt must be ${Kdf.SALT_BYTES} bytes (got $saltSize)")
        }
        val wrapNonceSize = Base64.decode(dto.wrap.nonce).size
        val payloadNonceSize = Base64.decode(dto.payload.nonce).size
        if (wrapNonceSize != Aead.NONCE_BYTES || payloadNonceSize != Aead.NONCE_BYTES) {
            throw InvalidVaultFormatException("nonces must be ${Aead.NONCE_BYTES} bytes")
        }
        val wrapCtSize = Base64.decode(dto.wrap.ct).size
        val expectedWrapCt = Aead.KEY_BYTES + Aead.TAG_BYTES
        if (wrapCtSize != expectedWrapCt) {
            throw InvalidVaultFormatException("wrap.ct must be $expectedWrapCt bytes, got $wrapCtSize")
        }
        return dto
    }
}

/** Mutable handle to an unlocked vault. Carries VK in memory; call [lock] to zero it. */
class VaultSession internal constructor(
    vaultKey: ByteArray,
    dto: VaultFileDto,
) {
    private var vk: ByteArray = vaultKey
    internal var dto: VaultFileDto = dto

    val vaultKey: ByteArray
        get() {
            check(vk.isNotEmpty()) { "session is locked" }
            return vk
        }

    val meta get() = dto.meta

    fun lock() {
        vk.zeroize()
        vk = ByteArray(0)
    }
}

class CreateResult(val fileBytes: ByteArray, val session: VaultSession)

data class UnlockResult(val session: VaultSession, val payload: VaultPayload)

sealed class VaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class InvalidVaultFormatException(message: String, cause: Throwable? = null) : VaultException(message, cause)
class WrongPasswordException(cause: Throwable? = null) : VaultException("wrong password or tampered header/wrap", cause)
class CorruptVaultException(message: String, cause: Throwable? = null) : VaultException(message, cause)
