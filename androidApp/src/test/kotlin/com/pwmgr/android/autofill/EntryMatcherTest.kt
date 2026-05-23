package com.pwmgr.android.autofill

import com.pwmgr.core.model.EntryType
import com.pwmgr.core.model.VaultEntry
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryMatcherTest {

    private val ts = Instant.parse("2026-05-15T12:00:00Z")

    private fun login(id: String, title: String, vararg urls: String, username: String? = "user") = VaultEntry(
        id = id,
        type = EntryType.LOGIN,
        title = title,
        username = username,
        password = "pw",
        urls = urls.toList(),
        createdAt = ts,
        updatedAt = ts,
    )

    @Test
    fun exact_host_match() {
        val gh = login("g", "GitHub", "https://github.com")
        val matches = EntryMatcher.match(listOf(gh), "com.example", webDomain = "github.com")
        assertEquals(listOf(gh), matches)
    }

    @Test
    fun subdomain_matches_parent() {
        // Saved entry for google.com → should match the autofill request for accounts.google.com.
        val google = login("g", "Google", "https://google.com")
        val matches = EntryMatcher.match(listOf(google), "com.android.chrome", webDomain = "accounts.google.com")
        assertEquals(listOf(google), matches)
    }

    @Test
    fun parent_matches_subdomain() {
        // Saved entry for accounts.google.com → should match the autofill request for google.com.
        val google = login("g", "Google", "https://accounts.google.com")
        val matches = EntryMatcher.match(listOf(google), "com.android.chrome", webDomain = "google.com")
        assertEquals(listOf(google), matches)
    }

    @Test
    fun cross_domain_does_not_match() {
        // evilgoogle.com is NOT a subdomain of google.com. Suffix-without-dot must not slip through.
        val google = login("g", "Google", "https://google.com")
        val matches = EntryMatcher.match(listOf(google), "com.android.chrome", webDomain = "evilgoogle.com")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun tombstoned_entries_are_skipped() {
        val deleted = login("d", "Old Google", "https://google.com").copy(deletedAt = ts)
        val matches = EntryMatcher.match(listOf(deleted), "com.android.chrome", webDomain = "google.com")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun non_login_entries_are_skipped() {
        val note = VaultEntry(
            id = "n",
            type = EntryType.SECURE_NOTE,
            title = "Google API key",
            urls = listOf("https://google.com"),
            createdAt = ts,
            updatedAt = ts,
        )
        val matches = EntryMatcher.match(listOf(note), "com.android.chrome", webDomain = "google.com")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun package_token_match_fallback() {
        // No webDomain → fall through to package-name token match against title.
        val gh = login("g", "GitHub Mobile", "https://github.com")
        val matches = EntryMatcher.match(listOf(gh), "com.github.android", webDomain = null)
        assertEquals(listOf(gh), matches)
    }

    @Test
    fun package_match_ignores_generic_tokens() {
        // "com", "android" are generic; only "example" remains, which doesn't match.
        val gh = login("g", "GitHub", "https://github.com")
        val matches = EntryMatcher.match(listOf(gh), "com.example.android", webDomain = null)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun empty_input_returns_empty() {
        assertEquals(emptyList(), EntryMatcher.match(emptyList(), "com.foo", webDomain = "foo.com"))
    }

    @Test
    fun hostMatches_helper_rejects_obvious_cousins() {
        assertTrue(EntryMatcher.hostMatches("google.com", "google.com"))
        assertTrue(EntryMatcher.hostMatches("google.com", "accounts.google.com"))
        assertFalse(EntryMatcher.hostMatches("google.com", "evilgoogle.com"))
        assertFalse(EntryMatcher.hostMatches("google.com", ""))
    }

    @Test
    fun extractHost_strips_scheme_and_path() {
        assertEquals("example.com", EntryMatcher.extractHost("https://example.com/login?next=foo"))
        assertEquals("example.com", EntryMatcher.extractHost("http://example.com:8080/x"))
        assertEquals("example.com", EntryMatcher.extractHost("example.com"))
    }
}
