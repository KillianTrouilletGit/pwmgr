package com.pwmgr.desktop

import com.formdev.flatlaf.FlatDarkLaf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.pwmgr.desktop.extension.BrowserExtensionScreen
import com.pwmgr.desktop.extension.ExtensionInstaller
import com.pwmgr.desktop.ipc.IpcManager
import com.pwmgr.storage.DesktopOAuth
import com.pwmgr.storage.DriveOAuthConfig
import com.pwmgr.storage.FileTokenStore
import com.pwmgr.storage.OAuthProvider
import com.pwmgr.storage.TokenStore
import com.pwmgr.ui.AppState
import com.pwmgr.ui.BiometricGate
import com.pwmgr.ui.PwMgrApp
import com.pwmgr.ui.Screen
import com.pwmgr.ui.VaultStorage
import com.pwmgr.ui.WindowsDpapiGate
import com.pwmgr.ui.settings.SettingsStore
import java.nio.file.Path

private fun pwmgrDir(): Path {
    val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    return Path.of(base, "PwMgr")
}

private fun defaultVaultPath(): Path = pwmgrDir().resolve("vault.enc")
private fun defaultTokenPath(): Path = pwmgrDir().resolve("oauth.tok")
private fun defaultDriveConfigPath(): Path = pwmgrDir().resolve("drive-config.json")
private fun defaultBiometricWrapPath(): Path = pwmgrDir().resolve("hbk.dpapi")
private fun defaultHandshakePath(): Path = pwmgrDir().resolve("ipc.handshake")
private fun defaultSettingsPath(): Path = pwmgrDir().resolve("settings.json")
private fun defaultNativeHostManifestPath(): Path = pwmgrDir().resolve("com.pwmgr.host.json")

/**
 * Locate the native-host `.bat` produced by `:nativeHost:installDist`. The path is relative
 * to the desktopApp project dir (which is the working dir when running `gradlew :desktopApp:run`).
 */
private fun nativeHostBatPath(): Path {
    val cwd = Path.of(System.getProperty("user.dir"))
    return cwd.resolve("../nativeHost/build/install/pwmgr-native-host/bin/pwmgr-native-host.bat")
        .toAbsolutePath().normalize()
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

interface Dwmapi : Library {
    companion object {
        val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java)
    }
    fun DwmSetWindowAttribute(hwnd: HWND, dwAttribute: Int, pvAttribute: IntByReference, cbAttribute: Int): HRESULT
}

fun main() {
    System.setProperty("flatlaf.useWindowDecorations", "true")
    FlatDarkLaf.setup()
    
    application {
    val storage = remember { VaultStorage(defaultVaultPath()) }
    val driveConfig = remember { DriveOAuthConfig.loadFromFile(defaultDriveConfigPath()) }
    val oauth: OAuthProvider? = remember(driveConfig) { driveConfig?.let { DesktopOAuth(it) } }
    val tokenStore: TokenStore? = remember(oauth) { oauth?.let { FileTokenStore(defaultTokenPath()) } }
    val biometricGate: BiometricGate? = remember {
        if (isWindows()) WindowsDpapiGate(defaultBiometricWrapPath()) else null
    }
    val settingsStore = remember { SettingsStore(defaultSettingsPath()) }
    val exportSink = remember { DesktopExportSink() }
    val state = remember {
        AppState(
            storage,
            oauthProvider = oauth,
            tokenStore = tokenStore,
            biometricGate = biometricGate,
            settingsStore = settingsStore,
            exportSink = exportSink,
            browserExtensionAvailable = isWindows(),
            saveDriveConfig = { id, secret ->
                DriveOAuthConfig.saveToFile(defaultDriveConfigPath(), DriveOAuthConfig(id, secret))
            }
        )
    }
    val ipcManager = remember(state) { IpcManager(state, defaultHandshakePath()) }
    DisposableEffect(ipcManager) {
        ipcManager.start()
        onDispose { ipcManager.stop() }
    }
    val extensionInstaller = remember {
        ExtensionInstaller(
            manifestPath = defaultNativeHostManifestPath(),
            nativeHostBatPath = nativeHostBatPath(),
        )
    }
    val windowState = rememberWindowState(size = DpSize(960.dp, 720.dp))

    Window(
        onCloseRequest = {
            ipcManager.stop()
            state.lock()
            exitApplication()
        },
        title = "BlackHole",
        icon = androidx.compose.ui.res.painterResource("icon.png"),
        state = windowState,
    ) {
        val windowHandle = this.window.windowHandle
        LaunchedEffect(windowHandle) {
            if (isWindows()) {
                val hwnd = HWND(Pointer(windowHandle))
                val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
                val attr = IntByReference(1)
                Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, attr, 4)
            }
        }
        PwMgrApp(state) { screen ->
            when (screen) {
                Screen.BrowserExtension -> {
                    BrowserExtensionScreen(state, extensionInstaller)
                    true
                }
                else -> false
            }
        }
    }
}
}
