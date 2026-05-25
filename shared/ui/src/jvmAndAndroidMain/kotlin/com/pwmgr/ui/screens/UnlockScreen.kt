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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pwmgr.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UnlockScreen(state: AppState) {
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 1Hz tick used to refresh the "Locked for Xs" countdown display below.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lockedOutUntilEpochMs) {
        while (state.lockedOutUntilEpochMs > System.currentTimeMillis()) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
        nowMs = System.currentTimeMillis()
    }
    val lockoutRemainingMs = (state.lockedOutUntilEpochMs - nowMs).coerceAtLeast(0L)
    val isLockedOut = lockoutRemainingMs > 0L

    // Auto-fire biometric on first display IF the user enrolled it AND the previous lock
    // wasn't an auto-lock (which would make the unlock instant and invisible on Windows DPAPI).
    LaunchedEffect(state.biometricEnrolled, state.requireExplicitUnlock) {
        if (state.biometricEnrolled && state.session == null && !state.requireExplicitUnlock) {
            state.unlockWithBiometric()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.height(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Unlock vault", style = MaterialTheme.typography.headlineMedium)

            if (state.requireExplicitUnlock) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Locked due to inactivity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Master password") },
                singleLine = true,
                enabled = !isLockedOut,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                isError = state.unlockError != null && !isLockedOut,
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (reveal) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.widthIn(min = 360.dp, max = 480.dp).fillMaxWidth(),
            )

            // Status zone: lockout countdown takes priority over plain error / attempt counter.
            when {
                isLockedOut -> {
                    Spacer(Modifier.height(12.dp))
                    val seconds = ((lockoutRemainingMs + 999) / 1000).toInt()
                    Text(
                        "Too many failed attempts. Try again in ${seconds}s.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.unlockError != null -> {
                    Spacer(Modifier.height(12.dp))
                    Text(state.unlockError!!, color = MaterialTheme.colorScheme.error)
                    if (state.consecutiveFailures in 1 until AppState.BACKOFF_THRESHOLD) {
                        Spacer(Modifier.height(4.dp))
                        val remaining = AppState.BACKOFF_THRESHOLD - state.consecutiveFailures
                        Text(
                            "$remaining attempt(s) before rate limiting kicks in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !state.busy && password.isNotEmpty() && !isLockedOut,
                onClick = {
                    val pw = password.toCharArray()
                    password = ""
                    state.unlock(pw)
                },
            ) {
                Text(if (state.busy) "Unlocking…" else "Unlock")
            }

            if (state.biometricEnrolled) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    enabled = !state.busy && !isLockedOut,
                    onClick = { scope.launch { state.unlockWithBiometric() } },
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Text("  Use biometric")
                }
            }
            if (state.biometricError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.biometricError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Vault: ${state.storage.displayPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
