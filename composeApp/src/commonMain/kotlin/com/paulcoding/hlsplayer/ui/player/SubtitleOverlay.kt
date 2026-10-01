package com.paulcoding.hlsplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcoding.hlsplayer.playback.SubtitleTrack
import com.paulcoding.hlsplayer.settings.Settings
import com.paulcoding.hlsplayer.subtitles.activeCues

/** The extension measures the cue height in rem; a rem is 16 px there. */
private const val REM = 16

/**
 * Subtitles drawn by the app: the primary track at the bottom, a second one
 * stacked above it, both shifted by [offset] seconds. Size, height and the
 * shaded box are the global subtitle settings.
 */
@Composable
fun SubtitleOverlay(
    primary: SubtitleTrack?,
    secondary: SubtitleTrack?,
    position: Double,
    offset: Double,
    settings: Settings,
    liftedBy: Int,
    modifier: Modifier = Modifier,
) {
    // A positive offset shows cues later, so the clock is read that much earlier.
    val time = position - offset
    val lines = listOfNotNull(secondary, primary).map { track -> activeCues(track.cues, time).joinToString("\n") { it.text } }
    if (lines.all { it.isEmpty() }) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = (settings.subtitlePosition * REM).dp + liftedBy.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lines.filter { it.isNotEmpty() }.forEach { text ->
                Text(
                    text,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = (18 * settings.subtitleSize).sp,
                        lineHeight = (24 * settings.subtitleSize).sp,
                        shadow = if (settings.subtitleBackground) null else Shadow(Color.Black, blurRadius = 6f),
                    ),
                    modifier = if (settings.subtitleBackground) Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier,
                )
            }
        }
    }
}
