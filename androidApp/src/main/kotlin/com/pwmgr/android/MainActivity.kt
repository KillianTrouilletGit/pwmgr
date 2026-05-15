package com.pwmgr.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.pwmgr.ui.AppState
import com.pwmgr.ui.PwMgrApp
import com.pwmgr.ui.VaultStorage

class MainActivity : ComponentActivity() {

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vaultPath = filesDir.toPath().resolve("vault.enc")
        val storage = VaultStorage(vaultPath)
        setContent {
            val state = remember { AppState(storage).also { appState = it } }
            PwMgrApp(state)
        }
    }

    override fun onStop() {
        // Lock the vault when the activity is no longer visible. Keeps VK out of memory
        // when the user backgrounds the app — Phase 9 will add a configurable inactivity timeout.
        appState?.lock()
        super.onStop()
    }
}
