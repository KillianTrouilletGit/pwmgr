package com.pwmgr.desktop.ipc

/**
 * Wire protocol for the browser-extension ↔ desktop IPC channel.
 *
 * Authentication: every connection MUST send `{ "op": "auth", "token": "<handshake>" }`
 * as its first frame and receive `{ "op": "auth_ok" }` before any other op is accepted.
 *
 * Subsequent ops:
 *   → { "id": 1, "op": "ping" }
 *   ← { "id": 1, "op": "pong" }
 *
 *   → { "id": 2, "op": "status" }
 *   ← { "id": 2, "op": "status_ok", "unlocked": true }
 *
 *   → { "id": 3, "op": "match", "host": "github.com" }
 *   ← { "id": 3, "op": "match_ok",
 *        "candidates": [{ "id": "<uuid>", "title": "GitHub", "username": "octocat" }] }
 *
 *   → { "id": 4, "op": "reveal", "entryId": "<uuid>" }
 *   ← { "id": 4, "op": "reveal_ok", "username": "octocat", "password": "hunter2" }
 *
 *   ← { "id": N, "op": "error", "code": "<see IpcErrors>", "message": "..." }
 *
 * The split between `match` (cheap, metadata only) and `reveal` (returns the password)
 * minimizes the window where secrets cross the IPC boundary: the browser asks `match`,
 * shows the user a picker, and only calls `reveal` once they've made a choice.
 */
data class Candidate(
    val id: String,
    val title: String,
    val username: String?,
)

/** Error codes returned in `{ "op": "error", "code": ... }`. */
object IpcErrors {
    const val LOCKED = "locked"
    const val UNKNOWN_ENTRY = "unknown_entry"
    const val BAD_AUTH = "bad_auth"
    const val BAD_REQUEST = "bad_request"
    const val INTERNAL = "internal"
}
