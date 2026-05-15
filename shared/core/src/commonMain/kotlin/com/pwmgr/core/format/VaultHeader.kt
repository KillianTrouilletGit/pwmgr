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
