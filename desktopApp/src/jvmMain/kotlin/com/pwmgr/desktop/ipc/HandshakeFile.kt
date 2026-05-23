package com.pwmgr.desktop.ipc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The handshake file is how the native messaging host (a short-lived child of the browser)
 * discovers the running PwMgr's IPC port and the shared-secret token it needs to authenticate.
 *
 * Path: `%LOCALAPPDATA%\PwMgr\ipc.handshake`. Written when PwMgr starts the IPC server,
 * deleted when PwMgr shuts down cleanly. NTFS ACLs already restrict it to the current user;
 * we also try a POSIX 0600 chmod (no-op on Windows) for paranoia on dev machines.
 *
 * On-disk JSON:
 * ```
 * { "version": 1, "port": 36963, "token": "<base64-32B>" }
 * ```
 */
class HandshakeFile(private val path: Path) {

    @Serializable
    data class Contents(val version: Int, val port: Int, val token: String)

    fun write(port: Int, token: String) {
        val body = json.encodeToString(Contents.serializer(), Contents(1, port, token))
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
            it.write(body)
        }
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    fun delete() {
        Files.deleteIfExists(path)
    }

    companion object {
        private val json = Json { encodeDefaults = true }
    }
}
