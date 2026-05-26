package com.pwmgr.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * OAuth client identifiers issued by Google Cloud Console for the user's GCP project.
 *
 * Despite the name, "client secret" is not actually secret for Desktop OAuth clients — it's
 * embedded in distributed desktop apps. PKCE provides the real security against authorization
 * code interception. We still send it because Google's token endpoint requires it for Desktop
 * client types.
 *
 * Shared across desktop and Android (jvmAndAndroidMain) — both OAuth providers use the same
 * format. Both call [loadFromFile] with their platform-appropriate path:
 *  - Desktop: `%LOCALAPPDATA%\PwMgr\drive-config.json`
 *  - Android: `filesDir/drive-config.json`
 */
data class DriveOAuthConfig(val clientId: String, val clientSecret: String) {
    companion object {
        fun loadFromFile(path: Path): DriveOAuthConfig? {
            if (!Files.exists(path)) return null
            // Files.readString requires API 33+ on Android — readAllBytes works on API 26+.
            val text = Files.readAllBytes(path).decodeToString()
            val parsed = configJson.decodeFromString(FileFormat.serializer(), text)
            return DriveOAuthConfig(parsed.clientId, parsed.clientSecret)
        }

        private val configJson = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class FileFormat(val clientId: String, val clientSecret: String)
    }
}
