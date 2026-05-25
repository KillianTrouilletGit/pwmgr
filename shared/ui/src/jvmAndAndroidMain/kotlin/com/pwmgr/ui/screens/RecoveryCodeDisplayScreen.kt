package com.pwmgr.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pwmgr.ui.AppState
import com.pwmgr.ui.rememberClipboardController

/**
 * Shown ONCE after vault creation (or after a successful recovery, in which case the code
 * has been rotated). The user must explicitly acknowledge they've saved the code before
 * they can proceed to the vault list — defending against the failure mode where they
 * close the app and never see it again.
 *
 * The code is selectable + has a copy button. It is NOT persisted anywhere in the app.
 * Losing it means losing the only way to recover from a forgotten master password.
 */
@Composable
fun RecoveryCodeDisplayScreen(state: AppState) {
    val code = state.pendingRecoveryCode ?: run {
        // Defensive: if there's no pending code, this screen shouldn't be reachable. Bounce back.
        state.acknowledgeRecoveryCode()
        return
    }
    val clipboard = rememberClipboardController(autoClearMs = 60_000L)
    var acknowledged by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Save your recovery code",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If you forget your master password, this is the ONLY way to get back into your vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 480.dp),
            )

            Spacer(Modifier.height(28.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        code,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { clipboard.copy(code) }) {
                    Text("Copy code")
                }
            }

            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Where to keep it",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "• Print it and store it somewhere physical (a safe, a sealed envelope, etc.)\n" +
                            "• Save it in a different password manager you trust\n" +
                            "• Email it to yourself (less ideal but better than nothing)\n" +
                            "Do NOT keep it on the same device as PwMgr.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                )
                Text(
                    "I have saved my recovery code in a safe place.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                enabled = acknowledged,
                onClick = { state.acknowledgeRecoveryCode() },
            ) {
                Text("Continue to vault")
            }
        }
    }
}
