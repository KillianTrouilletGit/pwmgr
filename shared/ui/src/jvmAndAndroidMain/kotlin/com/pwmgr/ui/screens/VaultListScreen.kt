package com.pwmgr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pwmgr.core.model.EntryType
import com.pwmgr.core.model.VaultEntry
import com.pwmgr.ui.AppState
import com.pwmgr.ui.SyncStatus
import kotlinx.coroutines.delay

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
                browserExtensionAvailable = state.browserExtensionAvailable,
                query = query,
                onQueryChange = { query = it },
                onLock = { state.lock() },
                onSyncClick = { state.openDriveSetup() },
                onSyncNow = { state.syncNow() },
                onSettingsClick = { state.openSettings() },
                onBrowserExtensionClick = { state.openBrowserExtension() },
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
                EmptyState(isFiltered = query.isNotBlank(), onAdd = { state.openEditor(null) })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
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
    browserExtensionAvailable: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onLock: () -> Unit,
    onSyncClick: () -> Unit,
    onSyncNow: () -> Unit,
    onSettingsClick: () -> Unit,
    onBrowserExtensionClick: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "revision $revision",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (syncAvailable) {
                        Text(
                            " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SyncStatusLabel(syncStatus)
                    }
                }
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
            if (browserExtensionAvailable) {
                IconButton(onClick = onBrowserExtensionClick) {
                    Icon(Icons.Filled.Extension, contentDescription = "Browser extension")
                }
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
            IconButton(onClick = onLock) {
                Icon(Icons.Filled.Lock, contentDescription = "Lock vault")
            }
        }
        // Animated linear progress bar appears only while syncing — a thin visual cue at the
        // very top of the content area that something async is happening without occupying
        // permanent layout space.
        AnimatedVisibility(
            visible = syncStatus is SyncStatus.Syncing,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        HorizontalDivider()
    }
}

/**
 * Text label next to "revision N" showing the live sync state. "Synced 2 min ago" reads
 * more useful than a green icon alone. Re-evaluates every 30 s so the relative time stays
 * fresh — cheap, only when the screen is composed.
 */
@Composable
private fun SyncStatusLabel(status: SyncStatus) {
    val text = when (status) {
        SyncStatus.NotConfigured -> "Not synced"
        SyncStatus.Idle -> "Idle"
        SyncStatus.Syncing -> "Syncing…"
        is SyncStatus.Ready -> {
            // Force recomposition every 30s so the relative time stays current.
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(status.lastSyncEpochMs) {
                while (true) {
                    delay(30_000L)
                    now = System.currentTimeMillis()
                }
            }
            "Synced ${relativeTime(status.lastSyncEpochMs, now)}"
        }
        is SyncStatus.Failed -> "Sync failed"
    }
    val color = when (status) {
        is SyncStatus.Failed -> MaterialTheme.colorScheme.error
        is SyncStatus.Ready, SyncStatus.Syncing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

private fun relativeTime(thenMs: Long, nowMs: Long): String {
    val diffSec = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        diffSec < 45 -> "just now"
        diffSec < 90 -> "1 min ago"
        diffSec < 60 * 60 -> "${diffSec / 60} min ago"
        diffSec < 90 * 60 -> "1 hour ago"
        diffSec < 24 * 60 * 60 -> "${diffSec / 3600} hours ago"
        else -> "${diffSec / 86400} days ago"
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
    // Rotate the sync icon while syncing — clear visual feedback over the same icon footprint.
    val rotation = if (status is SyncStatus.Syncing) {
        val transition = rememberInfiniteTransition(label = "sync-spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
            label = "sync-spin-angle",
        ).value
    } else {
        0f
    }
    IconButton(onClick = if (status is SyncStatus.Ready) onSyncNow else onClick) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun EmptyState(isFiltered: Boolean, onAdd: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isFiltered) Icons.Filled.SearchOff else Icons.Filled.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (isFiltered) "No matching entries" else "Your vault is empty",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (isFiltered) "Try a different search term, or clear the search to see everything."
                else "Start by adding your first login. Passwords stay encrypted on disk.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isFiltered) {
                Spacer(Modifier.height(4.dp))
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add your first entry") },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: VaultEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .graphicsLayer { alpha = 1f },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, hoveredElevation = 4.dp, focusedElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(colorForType(entry.type), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.title.take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    TypeChip(entry.type)
                }
                entry.username?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val urlText = entry.urls.firstOrNull()
                if (urlText != null) {
                    Text(
                        urlText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Small colored leading-letter avatar in lieu of favicons (cross-platform image loading is
 * worth a future polish pass). Color derived deterministically from the entry type so the
 * list is scannable at a glance.
 */
@Composable
private fun colorForType(type: EntryType) = when (type) {
    EntryType.LOGIN -> MaterialTheme.colorScheme.primary
    EntryType.SECURE_NOTE -> MaterialTheme.colorScheme.tertiary
    EntryType.CARD -> MaterialTheme.colorScheme.secondary
    EntryType.IDENTITY -> MaterialTheme.colorScheme.primary
}

@Composable
private fun TypeChip(type: EntryType) {
    Text(
        type.label().uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun EntryType.label(): String = when (this) {
    EntryType.LOGIN -> "Login"
    EntryType.SECURE_NOTE -> "Note"
    EntryType.CARD -> "Card"
    EntryType.IDENTITY -> "ID"
}
