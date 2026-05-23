package com.pwmgr.ui

/**
 * Hardware-gated unlock for the in-memory vault key. Implementations:
 *
 *  - Android: Keystore-stored AES-256 key with [setUserAuthenticationRequired(true)] and
 *    [BIOMETRIC_STRONG]. Both enroll and unlock fire a [BiometricPrompt]. The Keystore key
 *    is auto-invalidated if the user re-enrolls a fingerprint, so the wrap becomes stale and
 *    must be redone with the master password.
 *
 *  - Windows: DPAPI ([CryptProtectData] / [CryptUnprotectData]) tied to the current Windows
 *    user. NOT a true biometric prompt — the user sees no Hello dialog. This is a
 *    convenience unlock that survives reboot but is bound to the OS user account. See
 *    docs/CRYPTO.md §3.1 for the asymmetry rationale.
 *
 * Sealed protocol:
 *  1. [isAvailable] — can biometric/convenience unlock be set up at all on this device?
 *     (Android: hardware + enrolled credential. Windows: always true.)
 *  2. [isEnrolled] — has the user previously enabled biometric unlock for *this* vault?
 *     (Checked by file presence — no auth needed.)
 *  3. [enroll] — wrap [vaultKey] under the hardware key. Android prompts biometric.
 *  4. [unlock] — return the VK if the user authenticates. Android prompts biometric.
 *  5. [disable] — delete the wrap and (where possible) the hardware key entry.
 */
interface BiometricGate {

    /** True if the device supports this gate at all (hardware + OS API present). */
    suspend fun isAvailable(): Boolean

    /** True if a wrap blob exists on disk for this vault. */
    fun isEnrolled(): Boolean

    /**
     * Wraps [vaultKey] and persists the result. Caller must have an unlocked vault when
     * invoking this. May prompt the user for biometric on platforms that gate enrollment.
     */
    suspend fun enroll(vaultKey: ByteArray): Result<Unit>

    /**
     * Prompts the user (on platforms that prompt) and returns the unwrapped VK on success.
     * Returns null on user cancel. Throws on hardware errors or tampered wrap.
     */
    suspend fun unlock(): ByteArray?

    /** Removes the wrap and any hardware key bound to it. */
    suspend fun disable()
}

/** Thrown when the user dismisses the biometric prompt without authenticating. */
class BiometricCancelledException : RuntimeException("biometric prompt cancelled")
