package com.paulcoding.hlsplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Back for platforms with no dispatcher of their own: iOS's edge swipe and the
 * desktop's Escape. Handlers are kept in the order they appeared, and the
 * newest enabled one answers — the screen in front, and in it the sheet
 * before the panel before the screen itself.
 */
internal object BackStack {
    private class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    /** False when nothing wanted it. */
    fun goBack(): Boolean {
        val entry = entries.lastOrNull { it.enabled } ?: return false
        entry.onBack()
        return true
    }

    @Composable
    fun Register(enabled: Boolean, onBack: () -> Unit) {
        val entry = remember { Entry(enabled, onBack) }
        SideEffect {
            entry.enabled = enabled
            entry.onBack = onBack
        }
        DisposableEffect(entry) {
            entries += entry
            onDispose { entries -= entry }
        }
    }
}
