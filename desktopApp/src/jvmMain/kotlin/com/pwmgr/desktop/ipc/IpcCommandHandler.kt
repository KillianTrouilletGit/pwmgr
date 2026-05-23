package com.pwmgr.desktop.ipc

/**
 * Application-side handler for IPC commands. The desktop app implements this against its
 * [com.pwmgr.ui.AppState] so the IPC server stays decoupled from UI/state plumbing.
 *
 * All methods may be called from non-UI threads. Implementations must be thread-safe.
 */
interface IpcCommandHandler {
    fun isUnlocked(): Boolean
    fun match(host: String): List<Candidate>
    fun reveal(entryId: String): Pair<String?, String>?     // (username, password) or null on not-found
}
