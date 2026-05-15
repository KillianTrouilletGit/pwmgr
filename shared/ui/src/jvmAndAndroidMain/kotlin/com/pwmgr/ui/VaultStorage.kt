package com.pwmgr.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Reads and writes the encrypted vault blob from a single file. Atomic write strategy:
 * write to a sibling .tmp, fsync, then atomic-rename over the target. Prevents a torn write
 * from corrupting the vault if the process dies mid-save.
 *
 * The vault path is platform-specific (Windows: %LOCALAPPDATA%\PwMgr\vault.enc; Android:
 * context.filesDir / vault.enc). It's chosen by the entry point and passed in here.
 */
class VaultStorage(val path: Path) {

    val displayPath: String get() = path.toString()

    fun exists(): Boolean = path.exists()

    fun read(): ByteArray = path.readBytes()

    fun write(bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newOutputStream(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        ).use { out -> out.write(bytes) }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
