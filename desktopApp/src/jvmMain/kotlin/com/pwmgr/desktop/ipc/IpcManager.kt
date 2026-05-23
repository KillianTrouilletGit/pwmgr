package com.pwmgr.desktop.ipc

import com.pwmgr.crypto.secureRandomBytes
import com.pwmgr.ui.AppState
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Owns the lifecycle of the desktop-side IPC pieces:
 *  - generates a fresh shared-secret token on each app start
 *  - boots the [LocalIpcServer] on a random localhost port
 *  - writes the [HandshakeFile] that the native messaging host reads to find both
 *
 * The handshake file is rewritten on every PwMgr launch, so a stale native-host process
 * from a previous run can never connect — the token won't match.
 */
@OptIn(ExperimentalEncodingApi::class)
class IpcManager(state: AppState, private val handshakePath: Path) {

    private val adapter = IpcAdapter(state)
    private val token: String = Base64.encode(secureRandomBytes(32))
    private val server = LocalIpcServer(adapter, token)
    private val handshake = HandshakeFile(handshakePath)

    fun start() {
        val port = server.start()
        handshake.write(port, token)
    }

    fun stop() {
        runCatching { handshake.delete() }
        server.stop()
    }
}
