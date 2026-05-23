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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.pwmgr.ui.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * Consolidates per-device user preferences:
 *  - biometric enrolment (toggle uses [AppState.unlockWithBiometric] flow)
 *  - auto-lock timeout
 *  - clipboard auto-clear timeout
 *  - manual "Lock now" shortcut
 */
@Composable
fun SettingsScreen(state: AppState) {
    val settings = state.settings

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { state.closeSettings() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BiometricSection(state)
                AutoLockSection(settings = settings) { state.updateSettings(it) }
                ClipboardSection(settings = settings) { state.updateSettings(it) }
                if (state.exportAvailable) ExportSection(state)
                LockNowSection { state.lock() }
            }
        }
    }
}

@Composable
private fun BiometricSection(state: AppState) {
    val scope = rememberCoroutineScope()
    if (!state.biometricAvailable && !state.biometricEnrolled) return

    SectionCard(title = "Biometric unlock") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.padding(start = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.biometricEnrolled) "Enabled on this device" else "Use biometrics to unlock",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Wraps your vault key under a hardware-bound key. Master password is still " +
                        "required after a reboot or biometric re-enrollment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.biometricEnrolled,
                onCheckedChange = { wantOn ->
                    scope.launch {
                        if (wantOn) state.enableBiometric() else state.disableBiometric()
                    }
                },
            )
        }
        if (state.biometricError != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                state.biometricError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AutoLockSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SectionCard(title = "Auto-lock") {
        DurationDropdown(
            label = "Lock vault when idle for",
            currentMs = settings.autoLockMs,
            presets = AppSettings.AUTOLOCK_PRESETS_MS,
            onSelect = { onChange(settings.copy(autoLockMs = it)) },
        )
        Text(
            "Pointer activity (clicks, taps) resets the timer. Locking discards the in-memory " +
                "vault key — re-entering the master password (or biometric) is required afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClipboardSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SectionCard(title = "Clipboard") {
        DurationDropdown(
            label = "Clear copied passwords after",
            currentMs = settings.clipboardClearMs,
            presets = AppSettings.CLIPBOARD_PRESETS_MS,
            onSelect = { onChange(settings.copy(clipboardClearMs = it)) },
        )
        Text(
            "We only clear the clipboard if its contents are still what we copied — if you copied " +
                "something else in the meantime, your selection is preserved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportSection(state: AppState) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionCard(title = "Export") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Export vault to file", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Writes the encrypted vault bytes verbatim to a file you choose. The export " +
                        "is unlockable with the same master password — treat it like any backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        val dest = state.exportVault()
                        status = dest?.let { "Saved to $it" } ?: "Cancelled"
                        busy = false
                    }
                },
            ) {
                Text(if (busy) "Exporting…" else "Export")
            }
        }
        if (status != null) {
            Spacer(Modifier.height(4.dp))
            Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LockNowSection(onLockNow: () -> Unit) {
    SectionCard(title = "Lock vault") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Lock now", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Discards the in-memory vault key immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onLockNow) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Spacer(Modifier.padding(start = 8.dp))
                Text("Lock")
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DurationDropdown(
    label: String,
    currentMs: Long,
    presets: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = presets.firstOrNull { it.first == currentMs }?.second ?: "Custom (${currentMs}ms)"
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { open = true }) {
            Text(currentLabel)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((ms, displayLabel) in presets) {
                DropdownMenuItem(
                    text = { Text(displayLabel) },
                    onClick = {
                        onSelect(ms)
                        open = false
                    },
                )
            }
        }
    }
}
