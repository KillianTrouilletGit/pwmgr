package com.pwmgr.ui

import androidx.compose.runtime.Composable
import com.pwmgr.ui.screens.CreateVaultScreen
import com.pwmgr.ui.screens.DriveSetupScreen
import com.pwmgr.ui.screens.EntryEditorScreen
import com.pwmgr.ui.screens.UnlockScreen
import com.pwmgr.ui.screens.VaultListScreen

/**
 * Root composable shared between desktop and Android. Both platforms wrap this in their own
 * top-level shell (Window on desktop, Activity content on Android) and pass an [AppState]
 * constructed with the platform-appropriate VaultStorage.
 */
@Composable
fun PwMgrApp(state: AppState) {
    PwMgrTheme {
        when (val s = state.screen) {
            Screen.CreateVault -> CreateVaultScreen(state)
            Screen.Unlock -> UnlockScreen(state)
            Screen.VaultList -> VaultListScreen(state)
            is Screen.EntryEditor -> EntryEditorScreen(state, s.entryId)
            Screen.DriveSetup -> DriveSetupScreen(state)
        }
    }
}
