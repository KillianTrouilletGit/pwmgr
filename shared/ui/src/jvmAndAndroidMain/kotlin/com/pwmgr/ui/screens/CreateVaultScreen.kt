package com.pwmgr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import com.pwmgr.ui.PasswordStrength
import com.pwmgr.ui.PasswordStrengthBar

private const val MIN_PASSWORD_LEN = 12

@Composable
fun CreateVaultScreen(state: AppState) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { passwordFocus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.height(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Create your vault", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick a strong master password. There is no recovery — losing it loses the vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; localError = null },
                label = { Text("Master password") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (reveal) "Hide password" else "Show password",
                        )
                    }
                },
                supportingText = { Text("$MIN_PASSWORD_LEN characters minimum") },
                modifier = Modifier.widthIn(min = 360.dp, max = 480.dp).fillMaxWidth().focusRequester(passwordFocus),
            )
            Spacer(Modifier.height(6.dp))
            PasswordStrengthBar(
                password = password,
                modifier = Modifier.widthIn(min = 360.dp, max = 480.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; localError = null },
                label = { Text("Confirm password") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                isError = confirm.isNotEmpty() && confirm != password,
                modifier = Modifier.widthIn(min = 360.dp, max = 480.dp).fillMaxWidth(),
            )

            if (localError != null) {
                Spacer(Modifier.height(12.dp))
                Text(localError!!, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            val strongEnough = PasswordStrength.score(password) >= PasswordStrength.MIN_ACCEPTABLE_SCORE
            Button(
                enabled = !state.busy
                    && password.length >= MIN_PASSWORD_LEN
                    && password == confirm
                    && strongEnough,
                onClick = {
                    val pw = password.toCharArray()
                    password = ""
                    confirm = ""
                    val result = state.createVault(pw)
                    result.onFailure { localError = it.message ?: "Failed to create vault" }
                },
            ) {
                Text(if (state.busy) "Creating…" else "Create vault")
            }
            if (password.length >= MIN_PASSWORD_LEN && password == confirm && !strongEnough) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Password is too weak. Add length, mix character classes, or pick something less predictable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Vault file: ${state.storage.displayPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
