package com.pwmgr.storage

import com.pwmgr.core.model.VaultEntry
import com.pwmgr.core.model.VaultPayload
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Pure entry-by-entry merge of two payloads. Last-write-wins via [VaultEntry.updatedAt],
 * with tombstones winning for [tombstoneTtl] then being dropped from the merged result.
 *
 * The output is sorted by entry id so equality between two merge runs is deterministic.
 */
object MergeEngine {

    val DEFAULT_TOMBSTONE_TTL: Duration = 30.days

    fun merge(
        local: VaultPayload,
        remote: VaultPayload,
        now: Instant,
        tombstoneTtl: Duration = DEFAULT_TOMBSTONE_TTL,
    ): MergeResult {
        val localById = local.entries.associateBy { it.id }
        val remoteById = remote.entries.associateBy { it.id }
        val allIds = localById.keys + remoteById.keys

        var localOnly = 0
        var remoteOnly = 0
        var localWon = 0
        var remoteWon = 0
        var tied = 0
        var purged = 0

        val mergedEntries = mutableListOf<VaultEntry>()
        for (id in allIds) {
            val l = localById[id]
            val r = remoteById[id]

            val winner = pickWinner(l, r, now, tombstoneTtl).also { picked ->
                when {
                    l == null -> remoteOnly++
                    r == null -> localOnly++
                    picked === l && picked === r -> tied++   // never happens with distinct objects; documentary
                    picked === l -> localWon++
                    picked === r -> remoteWon++
                }
            }

            val deletedAt = winner.deletedAt
            if (deletedAt != null && (now - deletedAt) > tombstoneTtl) {
                purged++
                continue
            }
            mergedEntries.add(winner)
        }
        mergedEntries.sortBy { it.id }

        return MergeResult(
            merged = local.copy(entries = mergedEntries),
            stats = MergeStats(
                localOnly = localOnly,
                remoteOnly = remoteOnly,
                localWon = localWon,
                remoteWon = remoteWon,
                tied = tied,
                purged = purged,
            ),
        )
    }

    private fun pickWinner(l: VaultEntry?, r: VaultEntry?, now: Instant, ttl: Duration): VaultEntry {
        if (l == null) return r!!
        if (r == null) return l

        val lTombstoned = l.deletedAt != null && (now - l.deletedAt!!) <= ttl
        val rTombstoned = r.deletedAt != null && (now - r.deletedAt!!) <= ttl

        // Tombstones beat live edits within the TTL window: a delete on one device is preserved
        // even if another device edited the entry without seeing the delete. After TTL, the
        // tombstone is purged and edits are treated normally on the next merge.
        return when {
            lTombstoned && !rTombstoned -> l
            rTombstoned && !lTombstoned -> r
            l.updatedAt >= r.updatedAt -> l
            else -> r
        }
    }
}

data class MergeResult(val merged: VaultPayload, val stats: MergeStats)

data class MergeStats(
    val localOnly: Int,
    val remoteOnly: Int,
    val localWon: Int,
    val remoteWon: Int,
    val tied: Int,
    val purged: Int,
) {
    val total: Int get() = localOnly + remoteOnly + localWon + remoteWon + tied + purged
}
