package com.pwmgr.android.autofill

import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.pwmgr.android.R
import com.pwmgr.android.autofill.PwMgrAutofillService.Companion.parsedFormFromBytes
import com.pwmgr.core.model.VaultEntry
import com.pwmgr.core.model.VaultPayload
import com.pwmgr.ui.AndroidBiometricGate
import com.pwmgr.ui.AppState
import com.pwmgr.ui.PwMgrTheme
import com.pwmgr.ui.VaultStorage
import kotlinx.coroutines.launch

/**
 * Launched when the user taps the "Unlock PwMgr" suggestion shown by the system autofill UI.
 *
 * Lifecycle: construct a transient [AppState] over the same on-disk vault, run biometric
 * unlock (or password fallback), match entries against the form context passed in via the
 * launching Intent, and return a [FillResponse] to the system via `setResult` with
 * [AutofillManager.EXTRA_AUTHENTICATION_RESULT].
 */
class AutofillUnlockActivity : FragmentActivity() {

    private var callingPackage: String = ""
    private var webDomain: String? = null
    private var usernameFieldId: AutofillId? = null
    private var passwordFieldId: AutofillId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callingPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        intent.getByteArrayExtra(EXTRA_PARSED_FORM)?.let { bytes ->
            val (u, p) = parsedFormFromBytes(bytes)
            usernameFieldId = u
            passwordFieldId = p
        }

        val pwmgrDir = filesDir.toPath()
        val storage = VaultStorage(pwmgrDir.resolve("vault.enc"))
        val biometricGate = AndroidBiometricGate(this, pwmgrDir.resolve("biometric.wrap"))
        val state = AppState(storage, biometricGate = biometricGate)

        if (!storage.exists()) {
            // No vault on this device yet. Nothing to fill; let the system clean up.
            cancel()
            return
        }

        setContent {
            PwMgrTheme {
                UnlockOverlay(
                    state = state,
                    onUnlocked = ::completeAuthentication,
                    onCancel = ::cancel,
                )
            }
        }
    }

    private fun completeAuthentication(payload: VaultPayload) {
        val matches = EntryMatcher.match(payload.entries, callingPackage, webDomain)
        val response = buildFillResponse(matches)
        val result = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun buildFillResponse(matches: List<VaultEntry>): FillResponse {
        val builder = FillResponse.Builder()
        for (entry in matches) {
            val dataset = buildDataset(entry) ?: continue
            builder.addDataset(dataset)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun buildDataset(entry: VaultEntry): Dataset? {
        val username = entry.username
        val password = entry.password ?: return null

        val presentation = RemoteViews(packageName, R.layout.autofill_entry_item).apply {
            setTextViewText(R.id.autofill_entry_title, entry.title)
            setTextViewText(R.id.autofill_entry_username, username ?: "")
        }

        val builder = Dataset.Builder()
        passwordFieldId?.let {
            builder.setValue(it, AutofillValue.forText(password), presentation)
        }
        usernameFieldId?.let { uid ->
            if (!username.isNullOrEmpty()) {
                builder.setValue(uid, AutofillValue.forText(username), presentation)
            }
        }
        return builder.build()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.pwmgr.android.autofill.PACKAGE_NAME"
        const val EXTRA_WEB_DOMAIN = "com.pwmgr.android.autofill.WEB_DOMAIN"
        const val EXTRA_PARSED_FORM = "com.pwmgr.android.autofill.PARSED_FORM"
    }
}

@Composable
private fun UnlockOverlay(
    state: AppState,
    onUnlocked: (VaultPayload) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var biometricTried by remember { mutableStateOf(false) }

    // Auto-fire biometric on first composition if enrolled.
    LaunchedEffect(state.biometricEnrolled) {
        if (state.biometricEnrolled && !biometricTried) {
            biometricTried = true
            state.unlockWithBiometric()
        }
    }

    // When the vault becomes unlocked (by either path), bubble up.
    LaunchedEffect(state.payload) {
        state.payload?.let { onUnlocked(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
            elevation = CardDefaults.elevatedCardElevation(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Unlock PwMgr", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Authenticate to autofill credentials.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.biometricEnrolled) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        onClick = { scope.launch { state.unlockWithBiometric() } },
                    ) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  Use biometric")
                    }
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Master password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = state.unlockError != null,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.unlockError != null) {
                    Text(
                        state.unlockError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.biometricError != null) {
                    Text(
                        state.biometricError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(
                        enabled = !state.busy && password.isNotEmpty(),
                        onClick = {
                            val pw = password.toCharArray()
                            password = ""
                            state.unlock(pw)
                        },
                    ) {
                        Text(if (state.busy) "Unlocking…" else "Unlock")
                    }
                }
            }
        }
    }
}
