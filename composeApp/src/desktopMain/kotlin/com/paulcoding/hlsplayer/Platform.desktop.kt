package com.paulcoding.hlsplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

/** The window the app draws in, for the player to take fullscreen. */
val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }

/** Set while the window is fullscreen: Escape leaves that before it goes back. */
internal var exitFullscreen: (() -> Unit)? = null

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = BackStack.Register(enabled, onBack)

@Composable
actual fun FullscreenEffect(enabled: Boolean, onExit: () -> Unit) {
    val window = LocalWindowState.current ?: return
    val exit by rememberUpdatedState(onExit)
    DisposableEffect(enabled) {
        if (enabled) {
            window.placement = WindowPlacement.Fullscreen
            exitFullscreen = { exit() }
        }
        onDispose {
            if (enabled) {
                window.placement = WindowPlacement.Floating
                exitFullscreen = null
            }
        }
    }
    // macOS's own green button and menu leave fullscreen without asking.
    LaunchedEffect(enabled) {
        if (enabled) snapshotFlow { window.placement }.collect { if (it != WindowPlacement.Fullscreen) exit() }
    }
}

@Composable
actual fun KeepScreenOn(enabled: Boolean) {}
