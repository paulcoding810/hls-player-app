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
    /** Offers [text] as a file named [name]; false when the viewer backed out. */
    suspend fun export(name: String, text: String): Boolean

    /** The text of a file the viewer picks, or null when they backed out. */
    suspend fun import(): String?
}

@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Landscape with the system bars hidden while [enabled]; the screen stays on. */
@Composable
expect fun FullscreenEffect(enabled: Boolean)

/** Keeps the display awake while video plays. */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
