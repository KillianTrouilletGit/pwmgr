package com.pwmgr.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pwmgr.core.model.EntryType
import com.pwmgr.core.model.VaultEntry
import com.pwmgr.ui.AppState
import com.pwmgr.ui.SyncStatus

@Composable
fun VaultListScreen(state: AppState) {
    val payload = state.payload ?: return
    var query by remember { mutableStateOf("") }

    val entries = remember(payload, query) {
        payload.entries
            .filter { it.deletedAt == null }
            .filter { it.matches(query) }
            .sortedBy { it.title.lowercase() }
    }

    Scaffold(
        topBar = {
            TopBar(
                count = entries.size,
                totalCount = payload.entries.count { it.deletedAt == null },
                revision = state.session?.meta?.revision ?: 0L,
                syncStatus = state.syncStatus,
                syncAvailable = state.syncAvailable,
                query = query,
                onQueryChange = { query = it },
                onLock = { state.lock() },
                onSyncClick = { state.openDriveSetup() },
                onSyncNow = { state.syncNow() },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { state.openEditor(null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add entry") },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            if (entries.isEmpty()) {
                EmptyState(isFiltered = query.isNotBlank())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        EntryRow(entry, onClick = { state.openEditor(entry.id) })
                    }
                }
            }
        }
    }
}

private fun VaultEntry.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return title.lowercase().contains(q) ||
        (username?.lowercase()?.contains(q) == true) ||
        urls.any { it.lowercase().contains(q) }
}

@Composable
private fun TopBar(
    count: Int,
    totalCount: Int,
    revision: Long,
    syncStatus: SyncStatus,
    syncAvailable: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onLock: () -> Unit,
    onSyncClick: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val titleText = if (query.isBlank()) {
                    "Vault — $count entries"
                } else {
                    "Vault — $count of $totalCount entries"
                }
                Text(titleText, style = MaterialTheme.typography.titleLarge)
                Text(
                    "revision $revision",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.width(240.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (syncAvailable) {
                SyncStatusButton(syncStatus, onClick = onSyncClick, onSyncNow = onSyncNow)
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onLock) {
                Icon(Icons.Filled.Lock, contentDescription = "Lock vault")
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SyncStatusButton(status: SyncStatus, onClick: () -> Unit, onSyncNow: () -> Unit) {
    val (icon, tint, description) = when (status) {
        SyncStatus.NotConfigured -> Triple(Icons.Filled.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, "Configure Drive sync")
        SyncStatus.Idle -> Triple(Icons.Filled.Cloud, MaterialTheme.colorScheme.onSurfaceVariant, "Drive idle")
        SyncStatus.Syncing -> Triple(Icons.Filled.Sync, MaterialTheme.colorScheme.primary, "Syncing")
        is SyncStatus.Ready -> Triple(Icons.Filled.CloudDone, MaterialTheme.colorScheme.primary, "Sync up to date")
        is SyncStatus.Failed -> Triple(Icons.Filled.CloudOff, MaterialTheme.colorScheme.error, "Sync failed: ${status.message}")
    }
    // Single click → open setup screen (gives access to manual sync, disconnect, status detail).
    // For a connected state, single click on Ready could also sync-now, but separating "manage"
    // from "act" is clearer. Long-press → sync now is a future polish.
    IconButton(onClick = if (status is SyncStatus.Ready) onSyncNow else onClick) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

@Composable
private fun EmptyState(isFiltered: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (isFiltered) "No entries match." else "This vault is empty.",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (!isFiltered) {
                Text(
                    "Tap \"Add entry\" to create your first one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: VaultEntry, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            entry.username?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            val urlText = entry.urls.firstOrNull()
            if (urlText != null) {
                Text(
                    urlText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.type.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun EntryType.label(): String = when (this) {
    EntryType.LOGIN -> "Login"
    EntryType.SECURE_NOTE -> "Secure note"
    EntryType.CARD -> "Card"
    EntryType.IDENTITY -> "Identity"
}
