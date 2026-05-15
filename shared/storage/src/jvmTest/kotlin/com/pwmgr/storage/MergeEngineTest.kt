package com.pwmgr.storage

import com.pwmgr.core.model.EntryType
import com.pwmgr.core.model.VaultEntry
import com.pwmgr.core.model.VaultPayload
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class MergeEngineTest {

    private val now: Instant = Instant.parse("2026-05-15T12:00:00Z")

    private fun entry(
        id: String,
        title: String,
        updatedAt: Instant = now,
        createdAt: Instant = updatedAt,
        deletedAt: Instant? = null,
        username: String? = null,
    ) = VaultEntry(
        id = id,
        type = EntryType.LOGIN,
        title = title,
        username = username,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun payload(vararg entries: VaultEntry) = VaultPayload(entries.toList())

    @Test
    fun empty_with_empty_yields_empty() {
        val res = MergeEngine.merge(payload(), payload(), now)
        assertEquals(0, res.merged.entries.size)
        assertEquals(0, res.stats.total)
    }

    @Test
    fun local_only_entry_is_kept() {
        val a = entry("a", "Local A")
        val res = MergeEngine.merge(payload(a), payload(), now)
        assertEquals(listOf(a), res.merged.entries)
        assertEquals(1, res.stats.localOnly)
    }

    @Test
    fun remote_only_entry_is_taken() {
        val a = entry("a", "Remote A")
        val res = MergeEngine.merge(payload(), payload(a), now)
        assertEquals(listOf(a), res.merged.entries)
        assertEquals(1, res.stats.remoteOnly)
    }

    @Test
    fun conflict_local_newer_wins() {
        val older = entry("a", "Remote", updatedAt = Instant.parse("2026-05-15T11:00:00Z"))
        val newer = entry("a", "Local Newer", updatedAt = Instant.parse("2026-05-15T11:30:00Z"))
        val res = MergeEngine.merge(payload(newer), payload(older), now)
        assertEquals("Local Newer", res.merged.entries.single().title)
        assertEquals(1, res.stats.localWon)
    }

    @Test
    fun conflict_remote_newer_wins() {
        val older = entry("a", "Local Old", updatedAt = Instant.parse("2026-05-15T11:00:00Z"))
        val newer = entry("a", "Remote Newer", updatedAt = Instant.parse("2026-05-15T11:30:00Z"))
        val res = MergeEngine.merge(payload(older), payload(newer), now)
        assertEquals("Remote Newer", res.merged.entries.single().title)
        assertEquals(1, res.stats.remoteWon)
    }

    @Test
    fun tombstone_within_ttl_beats_live_edit_on_other_side() {
        // Local deleted the entry at T1. Remote edited it later at T2 without seeing the delete.
        // Tombstones win for 30 days, so the deletion is preserved.
        val tombstone = entry("a", "Deleted", updatedAt = Instant.parse("2026-05-15T11:00:00Z"), deletedAt = Instant.parse("2026-05-15T11:00:00Z"))
        val laterEdit = entry("a", "Edited later", updatedAt = Instant.parse("2026-05-15T11:30:00Z"))
        val res = MergeEngine.merge(payload(tombstone), payload(laterEdit), now)
        val winner = res.merged.entries.single()
        assertNotNull(winner.deletedAt, "tombstone within TTL should win over live edit")
        assertEquals(1, res.stats.localWon)
    }

    @Test
    fun tombstone_purged_after_ttl_expires() {
        // Both sides agree the entry is deleted, but the delete happened > 30 days ago.
        val ancientDeleteTs = now - 31.days
        val tombstone = entry("a", "Deleted long ago", updatedAt = ancientDeleteTs, deletedAt = ancientDeleteTs)
        val res = MergeEngine.merge(payload(tombstone), payload(tombstone), now)
        assertEquals(0, res.merged.entries.size, "expired tombstone must be purged")
        assertEquals(1, res.stats.purged)
    }

    @Test
    fun fresh_tombstone_kept_after_purge_scan() {
        val freshDelete = entry("a", "Just deleted", updatedAt = now, deletedAt = now)
        val res = MergeEngine.merge(payload(freshDelete), payload(), now)
        assertEquals(1, res.merged.entries.size)
        assertNotNull(res.merged.entries.single().deletedAt)
        assertEquals(0, res.stats.purged)
    }

    @Test
    fun both_sides_tombstoned_keeps_one() {
        val tA = entry("a", "del A", updatedAt = now, deletedAt = now)
        val tB = entry("a", "del B", updatedAt = now, deletedAt = now)
        val res = MergeEngine.merge(payload(tA), payload(tB), now)
        assertEquals(1, res.merged.entries.size)
    }

    @Test
    fun disjoint_entries_are_both_kept_and_sorted_by_id() {
        val a = entry("a", "A")
        val b = entry("b", "B")
        val c = entry("c", "C")
        val res = MergeEngine.merge(payload(a, c), payload(b), now)
        assertEquals(listOf("a", "b", "c"), res.merged.entries.map { it.id })
    }

    @Test
    fun output_is_deterministic_regardless_of_input_order() {
        val a = entry("a", "A")
        val b = entry("b", "B")
        val r1 = MergeEngine.merge(payload(a, b), payload(b, a), now)
        val r2 = MergeEngine.merge(payload(b, a), payload(a, b), now)
        assertEquals(r1.merged.entries.map { it.id }, r2.merged.entries.map { it.id })
    }

    @Test
    fun simultaneous_edit_and_unrelated_addition() {
        val edited = entry("a", "Edited", updatedAt = Instant.parse("2026-05-15T11:30:00Z"))
        val original = entry("a", "Original", updatedAt = Instant.parse("2026-05-15T11:00:00Z"))
        val newRemote = entry("b", "Brand new on remote", updatedAt = Instant.parse("2026-05-15T11:30:00Z"))
        val res = MergeEngine.merge(payload(edited), payload(original, newRemote), now)
        assertEquals(2, res.merged.entries.size)
        assertEquals("Edited", res.merged.entries.first { it.id == "a" }.title)
        assertEquals("Brand new on remote", res.merged.entries.first { it.id == "b" }.title)
    }

    @Test
    fun tombstone_vs_tombstone_one_purged_one_fresh() {
        val freshDelete = entry("a", "fresh", updatedAt = now, deletedAt = now)
        val staleDelete = entry("a", "stale", updatedAt = now - 31.days, deletedAt = now - 31.days)
        val res = MergeEngine.merge(payload(freshDelete), payload(staleDelete), now)
        // Fresh wins on updatedAt; it's tombstoned but within TTL, so it stays.
        assertEquals(1, res.merged.entries.size)
        assertEquals(now, res.merged.entries.single().deletedAt)
    }

    @Test
    fun custom_tombstone_ttl_is_respected() {
        val deletedYesterday = entry("a", "deleted yesterday", updatedAt = now - 1.days, deletedAt = now - 1.days)
        val res = MergeEngine.merge(
            payload(deletedYesterday),
            payload(deletedYesterday),
            now,
            tombstoneTtl = kotlin.time.Duration.ZERO,
        )
        assertEquals(0, res.merged.entries.size)
        assertEquals(1, res.stats.purged)
    }

    @Test
    fun equal_updatedAt_local_wins_by_convention() {
        // When timestamps are exactly equal we prefer local — deterministic, and the local
        // device is more likely to have the freshest user intent.
        val same = Instant.parse("2026-05-15T11:00:00Z")
        val local = entry("a", "Local version", updatedAt = same)
        val remote = entry("a", "Remote version", updatedAt = same)
        val res = MergeEngine.merge(payload(local), payload(remote), now)
        assertEquals("Local version", res.merged.entries.single().title)
    }
}
