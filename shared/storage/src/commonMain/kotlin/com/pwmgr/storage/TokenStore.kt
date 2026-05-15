package com.pwmgr.storage

import kotlinx.serialization.Serializable

/**
 * Persists and retrieves an OAuth refresh token. Implementations encrypt the token at rest
 * using the unlocked vault key (the same VK that protects vault entries), so the token is
 * worthless without the master password.
 *
 * The token lives in a small file alongside the vault — no platform secure-storage API
 * (DPAPI, Keystore) is required for v1.
 */
interface TokenStore {

    /** Returns the stored refresh token, or null if Drive has never been configured. */
    suspend fun read(vaultKey: ByteArray): OAuthAccount?

    /** Persists [account]. Overwrites any prior value. */
    suspend fun write(vaultKey: ByteArray, account: OAuthAccount)

    /** Removes the persisted token (logout / "disconnect Drive"). */
    suspend fun clear()

    /** True if an account has been previously persisted (cheap check, no decryption). */
    fun isConfigured(): Boolean
}

/**
 * An authenticated Drive account. We only persist the refresh_token; access tokens are
 * obtained on demand and cached in memory.
 */
@Serializable
data class OAuthAccount(
    val refreshToken: String,
    val email: String?,         // shown in the UI; null if id_token wasn't requested
    val obtainedAt: Long,       // epoch millis, for "last connected on …" displays
)
