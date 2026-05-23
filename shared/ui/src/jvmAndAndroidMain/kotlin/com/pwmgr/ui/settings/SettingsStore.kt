package com.pwmgr.ui.settings

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * JSON-backed persistence for [AppSettings]. Atomic via tmp-file + ATOMIC_MOVE, same as
 * [com.pwmgr.ui.VaultStorage]. Loads default settings when the file is missing or malformed
 * (logged-and-recovered, not crashed — settings corruption shouldn't lock you out).
 */
class SettingsStore(private val path: Path) {

    fun load(): AppSettings {
        if (!path.exists()) return AppSettings.DEFAULT
        return try {
            json.decodeFromString(AppSettings.serializer(), path.readText())
        } catch (_: Throwable) {
            AppSettings.DEFAULT
        }
    }

    fun save(settings: AppSettings) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        ).use {
            it.write(json.encodeToString(AppSettings.serializer(), settings))
        }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
