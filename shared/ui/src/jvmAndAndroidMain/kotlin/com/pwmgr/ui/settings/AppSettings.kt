package com.pwmgr.ui.settings

import kotlinx.serialization.Serializable

/**
 * Per-device user preferences. Stored in plain JSON next to the vault — these values are
 * not secret and we want them readable for troubleshooting. Vault-bound things like
 * biometric enrolment are NOT here; they're tracked by the existence of `biometric.wrap`.
 *
 * Backward/forward compatibility: every field has a default. Adding a new field never
 * breaks parsing of older files; removing a field requires bumping a schema version
 * (deferred until we actually need to).
 */
@Serializable
data class AppSettings(
    /** Lock the vault after this many milliseconds without user activity. 0 = never. */
    val autoLockMs: Long = 5 * 60_000L,

    /** Clear the system clipboard this many ms after a copy. 0 = don't auto-clear. */
    val clipboardClearMs: Long = 20_000L,
) {
    companion object {
        val DEFAULT = AppSettings()

        val AUTOLOCK_PRESETS_MS: List<Pair<Long, String>> = listOf(
            60_000L to "1 minute",
            5 * 60_000L to "5 minutes",
            15 * 60_000L to "15 minutes",
            60 * 60_000L to "1 hour",
            0L to "Never",
        )

        val CLIPBOARD_PRESETS_MS: List<Pair<Long, String>> = listOf(
            10_000L to "10 seconds",
            20_000L to "20 seconds",
            60_000L to "1 minute",
            0L to "Never (keep)",
        )
    }
}
