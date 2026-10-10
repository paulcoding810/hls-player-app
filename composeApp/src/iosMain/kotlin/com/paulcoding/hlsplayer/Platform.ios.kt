package com.paulcoding.hlsplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * iOS has no back button, so back comes from the swipe the scene delegate
 * listens for and hands to [goBack]. Handlers are kept in the order they
 * appeared, and the newest enabled one answers — the screen in front, and in
 * it the sheet before the panel before the screen itself.
 */
private class BackEntry(var enabled: Boolean, var onBack: () -> Unit)

private val backEntries = mutableListOf<BackEntry>()

/** Called from Swift for the edge swipe; false when nothing wanted it. */
fun goBack(): Boolean {
    val entry = backEntries.lastOrNull { it.enabled } ?: return false
    entry.onBack()
    return true
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val entry = remember { BackEntry(enabled, onBack) }
    SideEffect {
        entry.enabled = enabled
        entry.onBack = onBack
    }
    DisposableEffect(entry) {
        backEntries += entry
        onDispose { backEntries -= entry }
    }
}

@Composable
actual fun FullscreenEffect(enabled: Boolean) {}

@Composable
actual fun KeepScreenOn(enabled: Boolean) {}
