package com.pwmgr.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import com.pwmgr.storage.AndroidOAuth
import com.pwmgr.storage.DriveOAuthConfig
import com.pwmgr.storage.FileTokenStore
import com.pwmgr.storage.OAuthProvider
import com.pwmgr.storage.TokenStore
import com.pwmgr.ui.AndroidBiometricGate
import com.pwmgr.ui.AppState
import com.pwmgr.ui.PwMgrApp
import com.pwmgr.ui.VaultStorage
import com.pwmgr.ui.settings.SettingsStore

/**
 * Android entry point. Extends [FragmentActivity] so [AndroidBiometricGate] can show a
 * [androidx.biometric.BiometricPrompt] — it requires a FragmentActivity, not the plainer
 * ComponentActivity from androidx.activity.
 *
 * Drive sync is wired the same way as on desktop:
 *  - `filesDir/drive-config.json` (if present) provides the OAuth client_id + client_secret
 *    from the user's GCP project (same Desktop OAuth client used by the desktop app).
 *  - When present, an [AndroidOAuth] provider is constructed against application context
 *    and passed to [AppState] along with a [FileTokenStore] at `filesDir/oauth.tok`.
 *  - If `drive-config.json` is missing, the cloud sync UI simply doesn't appear (same
 *    behaviour as desktop without the file).
 */
class MainActivity : FragmentActivity() {

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pwmgrDir = filesDir.toPath()
        val vaultPath = pwmgrDir.resolve("vault.enc")
        val biometricWrapPath = pwmgrDir.resolve("biometric.wrap")
        val settingsPath = pwmgrDir.resolve("settings.json")
        val tokenPath = pwmgrDir.resolve("oauth.tok")
        val driveConfigPath = pwmgrDir.resolve("drive-config.json")

        val storage = VaultStorage(vaultPath)
        val biometricGate = AndroidBiometricGate(this, biometricWrapPath)
        val settingsStore = SettingsStore(settingsPath)

        val driveConfig = DriveOAuthConfig.loadFromFile(driveConfigPath)
        val oauth: OAuthProvider? = driveConfig?.let { AndroidOAuth(applicationContext, it) }
        val tokenStore: TokenStore? = oauth?.let { FileTokenStore(tokenPath) }

        setContent {
            val state = remember {
                AppState(
                    storage,
                    biometricGate = biometricGate,
                    settingsStore = settingsStore,
                    oauthProvider = oauth,
                    tokenStore = tokenStore,
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
