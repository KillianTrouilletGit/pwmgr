package com.pwmgr.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import com.pwmgr.ui.AndroidBiometricGate
import com.pwmgr.ui.AppState
import com.pwmgr.ui.PwMgrApp
import com.pwmgr.ui.VaultStorage
import com.pwmgr.ui.settings.SettingsStore

/**
 * Android entry point. Extends [FragmentActivity] so [AndroidBiometricGate] can show a
 * [androidx.biometric.BiometricPrompt] — it requires a FragmentActivity, not the plainer
 * ComponentActivity from androidx.activity.
 */
class MainActivity : FragmentActivity() {

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pwmgrDir = filesDir.toPath()
        val vaultPath = pwmgrDir.resolve("vault.enc")
        val biometricWrapPath = pwmgrDir.resolve("biometric.wrap")
        val settingsPath = pwmgrDir.resolve("settings.json")

        val storage = VaultStorage(vaultPath)
        val biometricGate = AndroidBiometricGate(this, biometricWrapPath)
        val settingsStore = SettingsStore(settingsPath)

        setContent {
            val state = remember {
                AppState(
                    storage,
                    biometricGate = biometricGate,
                    settingsStore = settingsStore,
                ).also { appState = it }
            }
            PwMgrApp(state)
        }
    }

    override fun onStop() {
        // Lock when the activity is no longer visible. Phase 9 polish will add a
        // configurable timeout instead of locking immediately.
        appState?.lock()
        super.onStop()
    }
}
