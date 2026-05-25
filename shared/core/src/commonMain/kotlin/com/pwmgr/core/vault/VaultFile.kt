package com.pwmgr.core.vault

import com.pwmgr.core.format.AEAD_ALG_AES256_GCM
import com.pwmgr.core.format.AeadBlob
import com.pwmgr.core.format.CanonicalJson
import com.pwmgr.core.format.CURRENT_VAULT_VERSION
import com.pwmgr.core.format.KDF_ALGO_ARGON2ID
import com.pwmgr.core.format.KdfParams
import com.pwmgr.core.format.RecoveryBlock
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
        // Relaxed: tolerate unknown top-level fields so older code can still parse newer
        // vaults (forward-compat). Strict enforcement of known fields and version is done
        // explicitly in parseAndValidate; AEAD AAD-binding still catches tampering of the
        // fields that matter for confidentiality / integrity.
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    /**
     * Builds a new vault file from the given password and returns the serialized JSON bytes,
     * the in-memory session, and a freshly-generated recovery code the caller MUST show to
     * the user exactly once. Losing both master password and recovery code = vault is gone.
     */
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

        // Recovery code: a fresh independent KDF lineage. We use the SAME Argon2id parameters
        // as the master-password path (memory-hard makes brute-force impractical even if the
        // attacker steals the vault file), with its own random salt.
        val recoveryCode = generateRecoveryCode()
        val recoverySalt = secureRandomBytes(Kdf.SALT_BYTES)
        val recoveryKdfParams = KdfParams(KDF_ALGO_ARGON2ID, Base64.encode(recoverySalt), memKiB, iterations, parallelism)
        val recoveryWrapNonce = secureRandomBytes(Aead.NONCE_BYTES)

        val mk = Kdf.derive(password, salt, memKiB, iterations, parallelism)
        val recoveryKey = Kdf.derive(recoveryCode.toCharArray(), recoverySalt, memKiB, iterations, parallelism)
        try {
            val wrapCt = Aead.encrypt(mk, wrapNonce, vk, aad)
            val recoveryAad = computeAad(CURRENT_VAULT_VERSION, recoveryKdfParams)
            val recoveryWrapCt = Aead.encrypt(recoveryKey, recoveryWrapNonce, vk, recoveryAad)
            val emptyPayload = json.encodeToString(VaultPayload.serializer(), VaultPayload()).encodeToByteArray()
            val payloadCt = Aead.encrypt(vk, payloadNonce, emptyPayload, aad)

            val dto = VaultFileDto(
                version = CURRENT_VAULT_VERSION,
                kdf = kdfParams,
                wrap = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(wrapNonce), Base64.encode(wrapCt)),
                payload = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(payloadNonce), Base64.encode(payloadCt)),
                meta = meta,
                recovery = RecoveryBlock(
                    kdf = recoveryKdfParams,
                    wrap = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(recoveryWrapNonce), Base64.encode(recoveryWrapCt)),
                ),
            )
            val bytes = json.encodeToString(VaultFileDto.serializer(), dto).encodeToByteArray()
            return CreateResult(bytes, VaultSession(vk.copyOf(), dto), recoveryCode)
        } finally {
            mk.zeroize()
            recoveryKey.zeroize()
            vk.zeroize()
        }
    }

    /**
     * Recovers a vault when the master password is forgotten but the recovery code is known.
     * Decrypts VK via the recovery wrap, derives a NEW MK from [newPassword], re-encrypts the
     * main wrap under it. The payload and recovery block are unchanged — VK doesn't rotate.
     *
     * @return new serialized bytes (caller writes them to disk), the unlocked session, AND a
     *   freshly-generated recovery code (the old one is invalidated by the new recovery wrap).
     *
     * @throws InvalidVaultFormatException if the file has no recovery block
     * @throws WrongPasswordException if the recovery code is wrong
     */
    fun recoverWithCode(
        fileBytes: ByteArray,
        recoveryCode: String,
        newPassword: CharArray,
        deviceId: String,
        nowIso: String,
    ): CreateResult {
        val dto = parseAndValidate(fileBytes)
        val recovery = dto.recovery
            ?: throw InvalidVaultFormatException("this vault has no recovery block; cannot recover without master password")
        val recoverySalt = Base64.decode(recovery.kdf.salt)
        val recoveryNonce = Base64.decode(recovery.wrap.nonce)
        val recoveryCt = Base64.decode(recovery.wrap.ct)
        val recoveryAad = computeAad(dto.version, recovery.kdf)

        val recoveryKey = Kdf.derive(
            recoveryCode.toCharArray(),
            recoverySalt,
            recovery.kdf.memKiB,
            recovery.kdf.iterations,
            recovery.kdf.parallelism,
        )
        val vk = try {
            Aead.decrypt(recoveryKey, recoveryNonce, recoveryCt, recoveryAad)
        } catch (e: AeadAuthenticationException) {
            throw WrongPasswordException(e)
        } finally {
            recoveryKey.zeroize()
        }

        // VK in hand → re-wrap under a NEW master password (fresh salt + KDF lineage), and
        // also rotate the recovery wrap so the OLD recovery code stops being valid.
        val newSalt = secureRandomBytes(Kdf.SALT_BYTES)
        val newKdfParams = KdfParams(
            KDF_ALGO_ARGON2ID,
            Base64.encode(newSalt),
            recovery.kdf.memKiB,
            recovery.kdf.iterations,
            recovery.kdf.parallelism,
        )
        val newAad = computeAad(dto.version, newKdfParams)
        val newWrapNonce = secureRandomBytes(Aead.NONCE_BYTES)

        val newRecoveryCode = generateRecoveryCode()
        val newRecoverySalt = secureRandomBytes(Kdf.SALT_BYTES)
        val newRecoveryKdfParams = newKdfParams.copy(salt = Base64.encode(newRecoverySalt))
        val newRecoveryWrapNonce = secureRandomBytes(Aead.NONCE_BYTES)

        val newMk = Kdf.derive(newPassword, newSalt, newKdfParams.memKiB, newKdfParams.iterations, newKdfParams.parallelism)
        val newRecoveryKey = Kdf.derive(
            newRecoveryCode.toCharArray(),
            newRecoverySalt,
            newRecoveryKdfParams.memKiB,
            newRecoveryKdfParams.iterations,
            newRecoveryKdfParams.parallelism,
        )
        try {
            val newWrapCt = Aead.encrypt(newMk, newWrapNonce, vk, newAad)
            val newRecoveryAad = computeAad(dto.version, newRecoveryKdfParams)
            val newRecoveryWrapCt = Aead.encrypt(newRecoveryKey, newRecoveryWrapNonce, vk, newRecoveryAad)

            // Re-encrypt the existing payload under the NEW AAD (kdf changed → AAD changed).
            val payloadNonce = Base64.decode(dto.payload.nonce)
            val payloadCt = Base64.decode(dto.payload.ct)
            val oldAad = computeAad(dto.version, dto.kdf)
            val plaintext = Aead.decrypt(vk, payloadNonce, payloadCt, oldAad)
            val newPayloadNonce = secureRandomBytes(Aead.NONCE_BYTES)
            val newPayloadCt = try {
                Aead.encrypt(vk, newPayloadNonce, plaintext, newAad)
            } finally {
                plaintext.zeroize()
            }

            val newDto = VaultFileDto(
                version = dto.version,
                kdf = newKdfParams,
                wrap = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(newWrapNonce), Base64.encode(newWrapCt)),
                payload = AeadBlob(AEAD_ALG_AES256_GCM, Base64.encode(newPayloadNonce), Base64.encode(newPayloadCt)),
                meta = dto.meta.copy(
                    revision = dto.meta.revision + 1,
                    modifiedAt = nowIso,
                    deviceId = deviceId,
                ),
                recovery = RecoveryBlock(
                    kdf = newRecoveryKdfParams,
                    wrap = AeadBlob(
                        AEAD_ALG_AES256_GCM,
                        Base64.encode(newRecoveryWrapNonce),
                        Base64.encode(newRecoveryWrapCt),
                    ),
                ),
            )
            val newBytes = json.encodeToString(VaultFileDto.serializer(), newDto).encodeToByteArray()
            return CreateResult(newBytes, VaultSession(vk.copyOf(), newDto), newRecoveryCode)
        } finally {
            newMk.zeroize()
            newRecoveryKey.zeroize()
            vk.zeroize()
        }
    }

    /**
     * Generates a recovery code as 6 groups of 4 base32 characters separated by hyphens:
     * `JBSW-Y3DP-EHPK-3PXP-AB23-CDEF`. 24 characters of base32 = 120 bits of entropy, well
     * above the 80-bit threshold for "Argon2id makes brute-force impossible at scale".
     */
    fun generateRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // exclude I,O,0,1 to ease transcription
        val bytes = secureRandomBytes(15)               // 120 bits
        val sb = StringBuilder(29)
        var buffer = 0
        var bitsLeft = 0
        var groupCounter = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val idx = (buffer ushr bitsLeft) and 0x1f
                sb.append(alphabet[idx])
                groupCounter++
                if (groupCounter == 4 && sb.length < 29) {
                    sb.append('-')
                    groupCounter = 0
                }
            }
        }
        return sb.toString()
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

class CreateResult(val fileBytes: ByteArray, val session: VaultSession, val recoveryCode: String)

data class UnlockResult(val session: VaultSession, val payload: VaultPayload)

sealed class VaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class InvalidVaultFormatException(message: String, cause: Throwable? = null) : VaultException(message, cause)
class WrongPasswordException(cause: Throwable? = null) : VaultException("wrong password or tampered header/wrap", cause)
class CorruptVaultException(message: String, cause: Throwable? = null) : VaultException(message, cause)
