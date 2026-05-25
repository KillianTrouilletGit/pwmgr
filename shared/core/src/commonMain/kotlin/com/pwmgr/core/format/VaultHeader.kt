package com.pwmgr.core.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for the encrypted vault file. See docs/CRYPTO.md for the normative spec.
 * All binary fields are base64 (RFC 4648 standard alphabet, no line wrap).
 */
@Serializable
data class VaultFileDto(
    val version: Int,
    val kdf: KdfParams,
    val wrap: AeadBlob,
    val payload: AeadBlob,
    val meta: VaultMeta,
    /**
     * Optional recovery wrap of the Vault Key under a randomly-generated recovery code,
     * derived through its own Argon2id lineage independent of the master password. When
     * set, the user can recover their vault by entering the recovery code even if they
     * forget the master password.
     *
     * Absent on vaults created before recovery codes were introduced; users can opt in
     * after the fact via Settings.
     */
    val recovery: RecoveryBlock? = null,
)

@Serializable
data class RecoveryBlock(
    val kdf: KdfParams,             // Argon2id over the recovery code (separate salt + cost)
    val wrap: AeadBlob,             // AES-256-GCM ciphertext of VK under the recovery-derived key
)

@Serializable
data class KdfParams(
    val algo: String,           // "argon2id"
    val salt: String,           // base64 of 32 bytes
    val memKiB: Int,
    @SerialName("iter") val iterations: Int,
    @SerialName("par") val parallelism: Int,
)

@Serializable
data class AeadBlob(
    val alg: String,            // "AES-256-GCM"
    val nonce: String,          // base64 of 12 bytes
    val ct: String,             // base64 ciphertext (includes 16-byte tag)
)

@Serializable
data class VaultMeta(
    val revision: Long,
    val modifiedAt: String,     // RFC 3339 UTC
    val deviceId: String,       // UUIDv4
)

const val CURRENT_VAULT_VERSION: Int = 1
const val KDF_ALGO_ARGON2ID: String = "argon2id"
const val AEAD_ALG_AES256_GCM: String = "AES-256-GCM"
