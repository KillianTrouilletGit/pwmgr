package com.pwmgr.storage

import com.pwmgr.core.model.VaultPayload
import com.pwmgr.core.vault.InvalidVaultFormatException
import kotlinx.datetime.Instant

/**
 * Orchestrates pull → merge → push against a [CloudStorage]. The engine itself does no
 * encryption: callers pass in encrypt/decrypt callbacks that close over the in-memory vault
 * key. This keeps the storage module crypto-agnostic.
 *
 * Concurrency model: optimistic, ETag-based. If the remote changes between our pull and
 * push, the upload fails with [ConflictException]; the engine then re-pulls, re-merges,
 * and retries up to [MAX_RETRIES] times.
 */
class SyncEngine(
    private val cloud: CloudStorage,
    private val maxRetries: Int = MAX_RETRIES,
) {

    /**
     * Synchronizes [localPayload] with the remote vault file.
     *
     * @param localPayload the current in-memory payload (the source of truth for entries
     *   created/edited locally)
     * @param localBytes the encrypted vault bytes corresponding exactly to [localPayload].
     *   Used as the upload body when no merge is needed (first sync to empty remote, or
     *   when the merged result equals [localPayload]).
     * @param decryptRemote called with the remote ciphertext to obtain its plaintext payload.
     *   Implementations should pass the unlocked vault key in via closure.
     * @param encryptMerged called when an upload of merged content is required. Must produce
     *   a vault file whose plaintext (when decrypted again) is [VaultPayload].
     * @param now wall-clock time used for tombstone TTL evaluation.
     */
    suspend fun sync(
        localPayload: VaultPayload,
        localBytes: ByteArray,
        decryptRemote: (ByteArray) -> VaultPayload,
        encryptMerged: (VaultPayload) -> ByteArray,
        now: Instant,
    ): SyncOutcome {
        var attempt = 0
        while (true) {
            attempt++
            val remote = cloud.get()

            if (remote == null) {
                val uploaded = cloud.upsert(localBytes, expectedEtag = null)
                return SyncOutcome.CreatedRemote(uploaded.etag)
            }

            // Self-heal: a zero-byte or otherwise structurally invalid remote file (typically
            // an orphan from an interrupted first sync — `createInAppData` succeeded but
            // `uploadMedia` didn't) is overwritten with the local copy. We DO NOT do this for
            // AEAD authentication failures (different password / different vault) — those
            // legitimately propagate up so the user sees the mismatch.
            val remotePayload = try {
                decryptRemote(remote.bytes)
            } catch (_: InvalidVaultFormatException) {
                val uploaded = cloud.upsert(localBytes, expectedEtag = null)
                return SyncOutcome.CreatedRemote(uploaded.etag)
            }
            val mergeResult = MergeEngine.merge(localPayload, remotePayload, now)
            val merged = mergeResult.merged

            // If the merged payload's entries are identical to remote's, the remote already
            // holds everything we have plus everything they have — no upload needed.
            val sameAsRemote = merged.entries == remotePayload.entries
            if (sameAsRemote) {
                return SyncOutcome.UpToDate(remote.etag, mergeResult.stats, merged)
            }

            val bodyBytes = if (merged.entries == localPayload.entries) localBytes else encryptMerged(merged)
            try {
                val uploaded = cloud.upsert(bodyBytes, expectedEtag = remote.etag)
                return SyncOutcome.Synced(merged, bodyBytes, uploaded.etag, mergeResult.stats)
            } catch (e: ConflictException) {
                if (attempt >= maxRetries) throw e
                // Loop: pull again, remerge, retry.
            }
        }
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}

sealed class SyncOutcome {
    /** The remote did not exist; local bytes were uploaded verbatim. */
    data class CreatedRemote(val etag: String) : SyncOutcome()

    /** Remote already had a superset of local; nothing was uploaded. Merged payload reflects pull. */
    data class UpToDate(val etag: String, val stats: MergeStats, val merged: VaultPayload) : SyncOutcome()

    /** A merged payload was uploaded. The local in-memory payload should be replaced with [merged]. */
    class Synced(
        val merged: VaultPayload,
        val newLocalBytes: ByteArray,
        val etag: String,
        val stats: MergeStats,
    ) : SyncOutcome()
}
