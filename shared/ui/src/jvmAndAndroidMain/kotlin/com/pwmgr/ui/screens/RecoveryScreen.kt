package com.pwmgr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pwmgr.ui.AppState

private const val MIN_PASSWORD_LEN = 12

/**
 * Forgot-master-password recovery flow. The user enters their recovery code (shown once
 * at vault creation) plus a NEW master password. Behind the scenes:
 *  - VK is unwrapped via the recovery block
 *  - A new master-key Argon2id lineage is derived from [newPassword]
 *  - The main wrap is re-encrypted under that new MK
 *  - A NEW recovery code is generated (the OLD code is invalidated by the new wrap)
 *  - The user lands on [Screen.RecoveryCodeDisplay] to save the new code
 */
@Composable
fun RecoveryScreen(state: AppState) {
    var recoveryCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { codeFocus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { state.closeRecovery() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Recover vault", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Enter the recovery code shown when you created your vault. We'll use it " +
                            "to unwrap your data and let you set a new master password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))

                    OutlinedTextField(
                        value = recoveryCode,
                        onValueChange = { recoveryCode = it.uppercase() },
                        label = { Text("Recovery code") },
                        placeholder = { Text("ABCD-EFGH-JKLM-NPQR-STUV-WXYZ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New master password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("$MIN_PASSWORD_LEN characters minimum") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = confirm.isNotEmpty() && confirm != newPassword,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    state.unlockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        Button(
                            enabled = !state.busy &&
                                recoveryCode.length >= 24 &&
                                newPassword.length >= MIN_PASSWORD_LEN &&
                                newPassword == confirm,
                            onClick = {
                                val pw = newPassword.toCharArray()
                                newPassword = ""
                                confirm = ""
                                state.recoverWithCode(recoveryCode.trim(), pw)
                            },
                        ) {
                            Text(if (state.busy) "Recovering…" else "Recover")
                        }
                    }
                }
            }
        }
    }
}
