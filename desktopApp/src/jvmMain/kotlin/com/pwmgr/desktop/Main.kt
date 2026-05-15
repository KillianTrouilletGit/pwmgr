package com.pwmgr.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pwmgr.storage.DesktopOAuth
import com.pwmgr.storage.DriveOAuthConfig
import com.pwmgr.storage.FileTokenStore
import com.pwmgr.storage.OAuthProvider
import com.pwmgr.storage.TokenStore
import com.pwmgr.ui.AppState
import com.pwmgr.ui.PwMgrApp
import com.pwmgr.ui.VaultStorage
import java.nio.file.Path

private fun pwmgrDir(): Path {
    val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    return Path.of(base, "PwMgr")
}

private fun defaultVaultPath(): Path = pwmgrDir().resolve("vault.enc")
private fun defaultTokenPath(): Path = pwmgrDir().resolve("oauth.tok")
private fun defaultDriveConfigPath(): Path = pwmgrDir().resolve("drive-config.json")

fun main() = application {
    val storage = remember { VaultStorage(defaultVaultPath()) }
    val driveConfig = remember { DriveOAuthConfig.loadFromFile(defaultDriveConfigPath()) }
    val oauth: OAuthProvider? = remember(driveConfig) { driveConfig?.let { DesktopOAuth(it) } }
    val tokenStore: TokenStore? = remember(oauth) { oauth?.let { FileTokenStore(defaultTokenPath()) } }
    val state = remember { AppState(storage, oauthProvider = oauth, tokenStore = tokenStore) }
    val windowState = rememberWindowState(size = DpSize(960.dp, 720.dp))

    Window(
        onCloseRequest = {
            state.lock()
            exitApplication()
        },
        title = "PwMgr",
        state = windowState,
    ) {
        PwMgrApp(state)
    }
}
