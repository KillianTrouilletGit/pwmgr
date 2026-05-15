package com.pwmgr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pwmgr.core.model.EntryType
import com.pwmgr.core.model.VaultEntry
import com.pwmgr.ui.AppState
import com.pwmgr.ui.PasswordGenerator
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun EntryEditorScreen(state: AppState, entryId: String?) {
    val existing = remember(entryId) { entryId?.let { state.findEntry(it) } }
    val isNew = existing == null

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: EntryType.LOGIN) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var url by remember { mutableStateOf(existing?.urls?.firstOrNull() ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var reveal by remember { mutableStateOf(isNew) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                titleText = if (isNew) "New entry" else "Edit entry",
                canDelete = !isNew,
                onBack = { state.closeEditor() },
                onDelete = { showDeleteConfirm = true },
            )
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TypeSelector(type, onChange = { type = it })

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("Title") },
                    singleLine = true,
                    isError = error != null && title.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (type == EntryType.LOGIN || type == EntryType.IDENTITY) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username / email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (type == EntryType.LOGIN || type == EntryType.CARD) {
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        reveal = reveal,
                        onToggleReveal = { reveal = !reveal },
                    )
                    TextButton(onClick = { showGenerator = !showGenerator }) {
                        Icon(Icons.Filled.Casino, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (showGenerator) "Hide generator" else "Generate password")
                    }
                    if (showGenerator) {
                        GeneratorPanel(onAccept = { generated ->
                            password = generated
                            reveal = true
                        })
                    }
                }

                if (type == EntryType.LOGIN) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        singleLine = true,
                        placeholder = { Text("https://example.com") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TextButton(onClick = { state.closeEditor() }) { Text("Cancel") }
                    Button(
                        enabled = title.isNotBlank() && !state.busy,
                        onClick = {
                            val result = saveEntry(
                                state, existing, title, type, username, password, url, notes,
                            )
                            result.onSuccess { state.closeEditor() }
                            result.onFailure { error = it.message ?: "Failed to save" }
                        },
                    ) { Text(if (isNew) "Create" else "Save") }
                }
            }
        }
    }

    if (showDeleteConfirm && existing != null) {
        DeleteConfirmDialog(
            entryTitle = existing.title,
            onConfirm = {
                state.deleteEntry(existing.id).onSuccess { state.closeEditor() }
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun EditorTopBar(
    titleText: String,
    canDelete: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(titleText, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (canDelete) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TypeSelector(selected: EntryType, onChange: (EntryType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EntryType.entries.forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onChange(type) },
                label = { Text(type.uiLabel()) },
            )
        }
    }
}

private fun EntryType.uiLabel(): String = when (this) {
    EntryType.LOGIN -> "Login"
    EntryType.SECURE_NOTE -> "Note"
    EntryType.CARD -> "Card"
    EntryType.IDENTITY -> "Identity"
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row {
                IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy password")
                }
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (reveal) "Hide password" else "Show password",
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GeneratorPanel(onAccept: (String) -> Unit) {
    var length by remember { mutableStateOf(20f) }
    var lower by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf("") }

    LaunchedEffect(length, lower, upper, digits, symbols) {
        preview = runCatching {
            PasswordGenerator.generate(
                PasswordGenerator.Options(
                    length = length.toInt(),
                    lowercase = lower,
                    uppercase = upper,
                    digits = digits,
                    symbols = symbols,
                ),
            )
        }.getOrElse { "" }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Password generator", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Length: ${length.toInt()}", modifier = Modifier.widthIn(min = 100.dp))
                Slider(
                    value = length,
                    onValueChange = { length = it },
                    valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..64f,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = lower, onClick = { lower = !lower }, label = { Text("a-z") })
                FilterChip(selected = upper, onClick = { upper = !upper }, label = { Text("A-Z") })
                FilterChip(selected = digits, onClick = { digits = !digits }, label = { Text("0-9") })
                FilterChip(selected = symbols, onClick = { symbols = !symbols }, label = { Text("!@#") })
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = preview.ifEmpty { "Select at least one character class." },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {
                    preview = runCatching {
                        PasswordGenerator.generate(
                            PasswordGenerator.Options(
                                length = length.toInt(),
                                lowercase = lower,
                                uppercase = upper,
                                digits = digits,
                                symbols = symbols,
                            ),
                        )
                    }.getOrElse { "" }
                }, label = { Text("Regenerate") })
                Button(
                    onClick = { if (preview.isNotEmpty()) onAccept(preview) },
                    enabled = preview.isNotEmpty(),
                ) { Text("Use this password") }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(entryTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete entry?") },
        text = { Text("\"$entryTitle\" will be removed from your vault.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun saveEntry(
    state: AppState,
    existing: VaultEntry?,
    title: String,
    type: EntryType,
    username: String,
    password: String,
    url: String,
    notes: String,
): Result<Unit> {
    val now = Clock.System.now()
    val entry = VaultEntry(
        id = existing?.id ?: Uuid.random().toString(),
        type = type,
        title = title.trim(),
        username = username.takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
        urls = if (url.isBlank()) emptyList() else listOf(url.trim()),
        notes = notes.takeIf { it.isNotBlank() },
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
    )
    return state.upsertEntry(entry)
}
