package com.pwmgr.desktop.ipc

import com.pwmgr.core.model.EntryType
import com.pwmgr.ui.AppState

/**
 * Bridges the IPC server's request/response surface to the live [AppState].
 *
 * Lock state is checked on each call — the server is alive for the whole app lifetime,
 * but it only returns credentials when the vault is currently unlocked.
 *
 * Host matching is suffix-aware: a saved entry for `google.com` matches a request for
 * `accounts.google.com` AND vice versa. Cross-domain matches (`google.com` vs
 * `evilgoogle.com`) are refused — the suffix has to align on a dot.
 */
class IpcAdapter(private val state: AppState) : IpcCommandHandler {

    override fun isUnlocked(): Boolean = state.session != null

    override fun match(host: String): List<Candidate> {
        val payload = state.payload ?: return emptyList()
        val target = host.normalizeHost()
        if (target.isEmpty()) return emptyList()
        return payload.entries.asSequence()
            .filter { it.deletedAt == null }
            .filter { it.type == EntryType.LOGIN }
            .filter { entry -> entry.urls.any { hostMatches(target, extractHost(it)) } }
            .map { Candidate(it.id, it.title, it.username) }
            .toList()
    }

    override fun reveal(entryId: String): Pair<String?, String>? {
        val entry = state.payload?.entries?.firstOrNull { it.id == entryId && it.deletedAt == null }
            ?: return null
        val password = entry.password ?: return null
        return entry.username to password
    }

    private fun hostMatches(target: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate == target) return true
        // Suffix must align on a dot so "google.com" does NOT match "evilgoogle.com".
        return candidate.endsWith(".$target") || target.endsWith(".$candidate")
    }

    private fun extractHost(url: String): String = url.normalizeHost()

    private fun String.normalizeHost(): String {
        var s = this.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        return s
    }
}
