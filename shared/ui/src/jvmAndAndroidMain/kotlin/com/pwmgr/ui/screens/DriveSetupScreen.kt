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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pwmgr.ui.AppState
import com.pwmgr.ui.SyncStatus
import kotlinx.coroutines.launch

@Composable
fun DriveSetupScreen(state: AppState) {
    val status = state.syncStatus
    val scope = rememberCoroutineScope()
    var lastResultMessage by remember { mutableStateOf<String?>(null) }
    var inProgress by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { state.closeDriveSetup() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Google Drive sync", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatusHero(status)

                    when (status) {
                        SyncStatus.NotConfigured -> NotConfiguredBlock(
                            available = state.syncAvailable,
                            inProgress = inProgress,
                            onConnect = {
                                if (inProgress) return@NotConfiguredBlock
                                inProgress = true
                                lastResultMessage = "Opening your browser…"
                                scope.launch {
                                    val result = state.setupDrive()
                                    inProgress = false
                                    lastResultMessage = result.fold(
                                        onSuccess = { "Connected to Google Drive." },
                                        onFailure = { it.message ?: "Connection failed." },
                                    )
                                }
                            },
                            saveDriveConfig = state.saveDriveConfig
                        )
                        is SyncStatus.Ready -> ConnectedBlock(
                            email = status.email,
                            onDisconnect = { state.disconnectDrive() },
                            onSyncNow = { state.syncNow() },
                        )
                        is SyncStatus.Failed -> FailedBlock(
                            email = status.email,
                            message = status.message,
                            onRetry = {
                                scope.launch { state.setupDrive() }
                            },
                            onDisconnect = { state.disconnectDrive() },
                        )
                        SyncStatus.Idle, SyncStatus.Syncing -> Text(
                            "Working on it…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    lastResultMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                    SetupHint()
                }
            }
        }
    }
}

@Composable
private fun StatusHero(status: SyncStatus) {
    val (icon, label, color) = when (status) {
        SyncStatus.NotConfigured -> Triple(Icons.Filled.CloudOff, "Not connected", MaterialTheme.colorScheme.onSurfaceVariant)
        SyncStatus.Idle -> Triple(Icons.Filled.Cloud, "Idle", MaterialTheme.colorScheme.onSurfaceVariant)
        SyncStatus.Syncing -> Triple(Icons.Filled.Cloud, "Syncing…", MaterialTheme.colorScheme.primary)
        is SyncStatus.Ready -> Triple(Icons.Filled.CloudDone, "Connected", MaterialTheme.colorScheme.primary)
        is SyncStatus.Failed -> Triple(Icons.Filled.CloudOff, "Error", MaterialTheme.colorScheme.error)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.height(48.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun NotConfiguredBlock(
    available: Boolean,
    inProgress: Boolean,
    onConnect: () -> Unit,
    saveDriveConfig: ((String, String) -> Unit)?
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (available) {
            Text(
                "Connect a Google account to back up your vault to Drive's hidden app folder. " +
                    "Your vault stays encrypted — Google never sees its contents.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConnect, enabled = !inProgress) {
                Text(if (inProgress) "Waiting for browser…" else "Connect Google Drive")
            }
        } else if (saveDriveConfig != null) {
            var clientId by remember { mutableStateOf("") }
            var clientSecret by remember { mutableStateOf("") }
            var isSaved by remember { mutableStateOf(false) }

            if (isSaved) {
                Text(
                    "Configuration saved! Please restart the application to connect to Google Drive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "Drive sync is not configured. Enter your Google Cloud OAuth credentials below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Client Secret") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                            saveDriveConfig(clientId.trim(), clientSecret.trim())
                            isSaved = true
                        }
                    },
                    enabled = clientId.isNotBlank() && clientSecret.isNotBlank()
                ) {
                    Text("Save & Restart")
                }
            }
        } else {
            Text(
                "Drive sync is not enabled on this build. See docs/DRIVE-SETUP.md.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ConnectedBlock(email: String?, onDisconnect: () -> Unit, onSyncNow: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (email != null) {
            Text(email, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSyncNow) { Text("Sync now") }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun FailedBlock(email: String?, message: String, onRetry: () -> Unit, onDisconnect: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (email != null) {
            Text(email, style = MaterialTheme.typography.bodyMedium)
        }

        // Scrollable + selectable error block so long Drive responses (HTML error pages,
        // verbose JSON bodies) can be copied for debugging. The full message also goes to
        // %LOCALAPPDATA%\PwMgr\drive.log via GoogleDriveClient's failure logger.
        androidx.compose.foundation.text.selection.SelectionContainer {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "Full request/response log: %LOCALAPPDATA%\\PwMgr\\drive.log",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Reconnect") }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun SetupHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("First-time setup checklist", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. Create a Google Cloud project and enable the Drive API.\n" +
                    "2. Create OAuth 2.0 credentials of type \"Desktop app\".\n" +
                    "3. Add yourself as a test user on the OAuth consent screen.\n" +
                    "4. Save the client_id and client_secret to drive-config.json next to your vault.\n" +
                    "Full walkthrough in docs/DRIVE-SETUP.md.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
