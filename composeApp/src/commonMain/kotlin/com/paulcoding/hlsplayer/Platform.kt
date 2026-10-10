package com.paulcoding.hlsplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.paulcoding.hlsplayer.playback.VideoEngine

/** The platform player, with the surface it draws into. */
interface PlatformEngine : VideoEngine {
    @Composable
    fun Surface(modifier: Modifier)

    fun release()
}

/** Moving a file in and out: the backup, through the platform's own picker. */
interface FileExchange {
    /** False on a device with no document picker, as many TVs are. */
    val available: Boolean

    /** Offers [text] as a file named [name]; false when the viewer backed out. */
    suspend fun export(name: String, text: String): Boolean

    /** The text of a file the viewer picks, or null when they backed out. */
    suspend fun import(): String?
}

/** True on a TV: driven by a remote, so focusing a field must not open the keyboard. */
val LocalIsTv = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Back, from wherever the platform sends it: the system button on Android, a
 * swipe in from the leading edge on iOS — which has no button — and Escape on
 * the desktop. The innermost enabled handler takes it, as Android's dispatcher
 * decides.
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Landscape with the system bars hidden while [enabled]; [onExit] runs when the platform leaves it itself. */
@Composable
expect fun FullscreenEffect(enabled: Boolean, onExit: () -> Unit)

/** Keeps the display awake while video plays. */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
