package com.pwmgr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pwmgr.ui.screens.CreateVaultScreen
import com.pwmgr.ui.screens.DriveSetupScreen
import com.pwmgr.ui.screens.EntryEditorScreen
import com.pwmgr.ui.screens.SettingsScreen
import com.pwmgr.ui.screens.UnlockScreen
import com.pwmgr.ui.screens.VaultListScreen

/**
 * Root composable shared between desktop and Android. Both platforms wrap this in their
 * own top-level shell (Window on desktop, Activity content on Android) and pass an
 * [AppState] constructed with the platform-appropriate VaultStorage.
 *
 * [extraRoute] lets a platform render screens that don't make sense in shared code —
 * notably [Screen.BrowserExtension], which is Windows-only.
 *
 * Screen transitions: a short cross-fade + subtle scale via [AnimatedContent] so screen
 * changes don't feel like teleportation. Tuned to 160 ms — long enough to register, short
 * enough to not feel sluggish.
 *
 * Auto-lock activity hook: a top-level [pointerInput] block observes every pointer event
 * (touch on Android, mouse/touchpad on desktop) before children handle it and calls
 * [AppState.recordActivity].
 */
@Composable
fun PwMgrApp(
    state: AppState,
    extraRoute: @Composable (Screen) -> Boolean = { false },
) {
    PwMgrTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            state.recordActivity()
                        }
                    }
                },
        ) {
            AnimatedContent(
                targetState = state.screen,
                transitionSpec = { defaultTransition() },
                label = "screen",
            ) { current ->
                when (current) {
                    Screen.CreateVault -> CreateVaultScreen(state)
                    Screen.Unlock -> UnlockScreen(state)
                    Screen.VaultList -> VaultListScreen(state)
                    is Screen.EntryEditor -> EntryEditorScreen(state, current.entryId)
                    Screen.DriveSetup -> DriveSetupScreen(state)
                    Screen.Settings -> SettingsScreen(state)
                    else -> if (!extraRoute(current)) NotAvailableOnThisPlatform()
                }
            }
        }
    }
}

private fun defaultTransition(): ContentTransform {
    val durationMs = 160
    return (fadeIn(animationSpec = tween(durationMs)) + scaleIn(initialScale = 0.98f, animationSpec = tween(durationMs)))
        .togetherWith(fadeOut(animationSpec = tween(durationMs / 2)) + scaleOut(targetScale = 1.02f, animationSpec = tween(durationMs)))
}

@Composable
private fun NotAvailableOnThisPlatform() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
            Text("This feature is not available on this platform.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
