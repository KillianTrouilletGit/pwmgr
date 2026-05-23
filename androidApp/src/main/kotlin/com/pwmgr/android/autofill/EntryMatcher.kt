package com.pwmgr.android.autofill

import com.pwmgr.core.model.EntryType
import com.pwmgr.core.model.VaultEntry

/**
 * Picks vault entries that look like credentials for the form we're filling.
 *
 * Matching strategy:
 *
 * - **Web domain match.** If we have a host like `accounts.google.com`, we accept any entry
 *   whose URL host equals or is a suffix-match (`google.com` matches `accounts.google.com`).
 *   We refuse cross-domain matches (`google.com` MUST NOT match `evilgoogle.com`).
 * - **Package match.** Failing a webDomain, we fall back to substring/token matching on the
 *   app's package name vs. each entry's title and URLs. This is intentionally generous —
 *   the user is the only one who'll see the suggestions, so false positives are cheap.
 *
 * Login-type entries are preferred; secure notes / cards / identities are ignored.
 */
object EntryMatcher {

    fun match(entries: List<VaultEntry>, packageName: String, webDomain: String?): List<VaultEntry> {
        val candidates = entries.asSequence()
            .filter { it.deletedAt == null }
            .filter { it.type == EntryType.LOGIN }
            .toList()

        if (!webDomain.isNullOrBlank()) {
            val host = webDomain.lowercase()
            return candidates.filter { entry ->
                entry.urls.any { hostMatches(host, extractHost(it)) }
            }
        }

        val tokens = packageName.lowercase().split('.').filter { it.length >= 3 && it !in COMMON_PKG_TOKENS }
        if (tokens.isEmpty()) return emptyList()
        return candidates.filter { entry ->
            val haystack = (entry.title + " " + entry.urls.joinToString(" ")).lowercase()
            tokens.any { token -> haystack.contains(token) }
        }
    }

    /**
     * True if [candidate] is the same host as [target] OR a sub-domain of [target] (so
     * `accounts.google.com` matches the saved entry for `google.com`, but not `evilgoogle.com`).
     */
    internal fun hostMatches(target: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate == target) return true
        // Suffix match must align on a dot to prevent "evilgoogle.com" matching "google.com".
        return candidate.endsWith(".$target") || target.endsWith(".$candidate")
    }

    internal fun extractHost(url: String): String {
        var s = url.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        return s
    }

    /** Skip generic package-name tokens that would match almost anything. */
    private val COMMON_PKG_TOKENS: Set<String> = setOf(
        "com", "org", "net", "io", "app", "android", "mobile", "client", "user",
    )
}
