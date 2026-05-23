package com.pwmgr.desktop.extension

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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.pwmgr.ui.AppState

@Composable
fun BrowserExtensionScreen(state: AppState, installer: ExtensionInstaller) {
    var extensionIdInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<ExtensionInstaller.InstallResult?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { state.closeBrowserExtension() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Browser extension", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier.padding(32.dp).widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "PwMgr's Chromium-compatible extension talks to this desktop app over a local " +
                        "secure channel. To wire them together you need the extension's ID — load the " +
                        "unpacked extension once, copy its ID from chrome://extensions, and paste it below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Step-by-step", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "1. Open chrome://extensions (or edge://extensions).\n" +
                                "2. Enable \"Developer mode\".\n" +
                                "3. Click \"Load unpacked\" and pick the browser-extension/dist folder.\n" +
                                "4. Copy the generated extension ID.\n" +
                                "5. Paste it here and click \"Install native host\".",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                OutlinedTextField(
                    value = extensionIdInput,
                    onValueChange = { extensionIdInput = it; status = null },
                    label = { Text("Extension ID") },
                    singleLine = true,
                    placeholder = { Text("32 lowercase letters, e.g. abcdefghijklmnopqrstuvwxyzabcdef") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = extensionIdInput.matches(Regex("^[a-p]{32}$")),
                        onClick = {
                            status = "Installing…"
                            val result = installer.install(listOf(extensionIdInput.trim()))
                            lastResult = result
                            status = describe(result)
                        },
                    ) {
                        Text("Install native host")
                    }
                    OutlinedButton(onClick = {
                        status = "Removing…"
                        val result = installer.uninstall()
                        lastResult = result
                        status = describe(result, uninstalled = true)
                    }) {
                        Text("Uninstall")
                    }
                }

                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(status!!, style = MaterialTheme.typography.bodyMedium)
                }
                lastResult?.let { res ->
                    if (res.failures.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        res.failures.forEach { (browser, msg) ->
                            Text(
                                "${browser.displayName}: $msg",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun describe(result: ExtensionInstaller.InstallResult, uninstalled: Boolean = false): String {
    val verb = if (uninstalled) "Unregistered" else "Registered"
    val ok = result.installedBrowsers.joinToString(", ") { it.displayName }
    return when {
        result.ok -> "$verb in: $ok."
        result.installedBrowsers.isNotEmpty() -> "$verb partially. Successful: $ok. See errors below."
        else -> "Failed. See errors below."
    }
}
