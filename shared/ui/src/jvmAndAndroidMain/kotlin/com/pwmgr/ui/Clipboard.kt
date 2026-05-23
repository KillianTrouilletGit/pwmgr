package com.pwmgr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay

/**
 * Drives copy-with-auto-clear from Compose screens. Pattern:
 *
 * ```
 * val clip = rememberClipboardController(autoClearMs = state.settings.clipboardClearMs)
 * Button(onClick = { clip.copy("hunter2") }) { Text("Copy") }
 * ```
 *
 * The clear is best-effort. If the user copied something else in the meantime, we don't
 * overwrite — we only clear if the clipboard still contains exactly what we wrote.
 */
class ClipboardController internal constructor(
    private val clipboard: ClipboardManager,
    private val autoClearMs: Long,
    private val scheduleClear: (text: String) -> Unit,
) {
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        if (autoClearMs > 0L) scheduleClear(text)
    }
}

@Composable
fun rememberClipboardController(autoClearMs: Long): ClipboardController {
    val clipboard = LocalClipboardManager.current
    var pendingClearAt by remember { mutableLongStateOf(0L) }
    var pendingExpected by remember { mutableStateOf("") }
    val controller = remember(clipboard, autoClearMs) {
        ClipboardController(
            clipboard = clipboard,
            autoClearMs = autoClearMs,
        ) { text ->
            pendingClearAt = System.currentTimeMillis() + autoClearMs
            pendingExpected = text
        }
    }
    LaunchedEffect(pendingClearAt) {
        if (pendingClearAt == 0L) return@LaunchedEffect
        val toWait = pendingClearAt - System.currentTimeMillis()
        if (toWait > 0L) delay(toWait)
        val current = clipboard.getText()?.text
        if (current == pendingExpected) {
            clipboard.setText(AnnotatedString(""))
        }
        pendingExpected = ""
    }
    // If this composable leaves composition before the clear fires, also try to clear
    // immediately — for cases like screen change while a copied password is still hot.
    DisposableEffect(controller) {
        onDispose {
            if (pendingExpected.isNotEmpty()) {
                val current = clipboard.getText()?.text
                if (current == pendingExpected) {
                    clipboard.setText(AnnotatedString(""))
                }
            }
        }
    }
    return controller
}
