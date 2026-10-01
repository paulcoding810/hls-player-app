package com.paulcoding.hlsplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcoding.hlsplayer.model.Episode
import com.paulcoding.hlsplayer.model.Position
import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.playback.Level
import com.paulcoding.hlsplayer.playback.SubtitleTrack
import com.paulcoding.hlsplayer.settings.PLAYBACK_RATES
import com.paulcoding.hlsplayer.settings.SUBTITLE_POSITIONS
import com.paulcoding.hlsplayer.settings.SUBTITLE_SIZES
import com.paulcoding.hlsplayer.settings.Settings
import com.paulcoding.hlsplayer.stremio.FoundStream
import com.paulcoding.hlsplayer.ui.Choices
import com.paulcoding.hlsplayer.ui.GhostButton
import com.paulcoding.hlsplayer.ui.Help
import com.paulcoding.hlsplayer.ui.IconAction
import com.paulcoding.hlsplayer.ui.Icons
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.PrimaryButton
import com.paulcoding.hlsplayer.ui.Radius
import com.paulcoding.hlsplayer.ui.SectionLabel
import com.paulcoding.hlsplayer.ui.Sheet
import com.paulcoding.hlsplayer.ui.SwitchRow
import com.paulcoding.hlsplayer.util.formatTime
import kotlin.math.roundToInt

private val shape = RoundedCornerShape(Radius)

/** The addon's other streams; the playing one is marked rather than disabled, so the list reads as a choice. */
@Composable
fun StreamPicker(streams: List<FoundStream>, current: String?, onChoose: (FoundStream) -> Unit, onDismiss: () -> Unit) {
    Sheet("Stream source", onDismiss) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            streams.forEach { stream ->
                val active = stream.url == current
                Column(
                    Modifier.fillMaxWidth().clip(shape).border(1.dp, if (active) Palette.primary else Palette.line, shape)
                        .clickable { onChoose(stream); onDismiss() }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(stream.name.ifEmpty { "Stream" }, color = if (active) Palette.primary else Palette.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (stream.description.isNotEmpty()) Text(stream.description, color = Palette.inkFaint, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Which tracks show and how: a primary, a second one stacked above it, the
 * timing offset, and the global look. The look is global — it is written to
 * the settings, as the extension's control-bar popover does.
 */
@Composable
fun SubtitleSettings(
    tracks: List<SubtitleTrack>,
    primary: String?,
    secondary: String?,
    offset: Double,
    settings: Settings,
    onPrimary: (String?) -> Unit,
    onSecondary: (String?) -> Unit,
    onOffset: (Double) -> Unit,
    onSettings: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet("Subtitles", onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tracks.isEmpty()) Help("This stream has no subtitles.")
            else {
                SectionLabel("Track")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Choices(listOf<String?>(null).plus(tracks.map { it.id }).map { id -> id to (tracks.firstOrNull { it.id == id }?.label ?: "Off") }, primary, onPrimary)
                }
                SectionLabel("Second track")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Choices(listOf<String?>(null).plus(tracks.filter { it.id != primary }.map { it.id }).map { id -> id to (tracks.firstOrNull { it.id == id }?.label ?: "Off") }, secondary, onSecondary)
                }
                SectionLabel("Timing")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconAction(Icons.Minus, "Show subtitles earlier", { onOffset(offset - 0.25) }, size = 36)
                    Text("${if (offset > 0) "+" else ""}${(offset * 100).roundToInt() / 100.0} s", fontSize = 14.sp)
                    IconAction(Icons.Plus, "Show subtitles later", { onOffset(offset + 0.25) }, size = 36)
                    if (offset != 0.0) GhostButton("Reset", { onOffset(0.0) })
                }
            }
            SectionLabel("Size")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Choices(SUBTITLE_SIZES.map { it.value to it.label }, settings.subtitleSize, { size -> onSettings { it.copy(subtitleSize = size) } })
            }
            SectionLabel("Position")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Choices(SUBTITLE_POSITIONS.map { it.value to it.label }, settings.subtitlePosition, { spot -> onSettings { it.copy(subtitlePosition = spot) } })
            }
            SwitchRow("Shaded box", settings.subtitleBackground, { on -> onSettings { it.copy(subtitleBackground = on) } })
        }
    }
}

@Composable
fun RateAndQuality(rate: Double, levels: List<Level>, level: String?, onRate: (Double) -> Unit, onLevel: (String?) -> Unit, onDismiss: () -> Unit) {
    Sheet("Playback", onDismiss) {
        SectionLabel("Speed")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Choices(PLAYBACK_RATES.map { it to "${it}×" }, rate, onRate)
        }
        if (levels.size > 1) {
            SectionLabel("Quality")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Choices(listOf<Pair<String?, String>>(null to "Auto") + levels.map { it.id to it.label }, level, onLevel)
            }
        }
    }
}

@Composable
fun ResumePrompt(position: Position, onResume: () -> Unit, onRestart: () -> Unit) {
    val percent = if (position.duration > 0) (position.position / position.duration * 100).roundToInt() else null
    // Dismissing reads as "no, do not continue", and so starts over.
    com.paulcoding.hlsplayer.ui.Confirm(
        title = "Continue watching?",
        message = "This stream was stopped at ${formatTime(position.position)} of ${formatTime(position.duration)}${percent?.let { " ($it%)" }.orEmpty()}.",
        confirm = "Resume",
        dismiss = "Start over",
        onConfirm = onResume,
        onDismiss = onRestart,
    )
}

/** The side panel: it overlays the video and never takes its space, so playback does not stutter. */
@Composable
fun EpisodesPanel(episodes: List<Episode>, currentId: String?, positions: Map<String, Position>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(Palette.panel.copy(alpha = 0.95f)).padding(top = 56.dp)) {
        SectionLabel("Episodes (${episodes.size})", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(episodes, key = { _, it -> it.id }) { index, episode ->
                val active = episode.id == currentId
                val stored = positions[episode.key]
                Row(
                    Modifier.fillMaxWidth().background(if (active) Palette.primarySoft else Palette.panel.copy(alpha = 0f))
                        .clickable { onSelect(episode.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", color = Palette.inkFaint, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(episode.title, color = if (active) Palette.primary else Palette.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (stored != null) Text("Stopped at ${formatTime(stored.position)}", color = Palette.inkFaint, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CountdownCard(seconds: Int, onCancel: () -> Unit, onNow: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(shape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)).border(1.dp, Palette.line, shape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Next episode in $seconds", fontSize = 14.sp)
        GhostButton("Cancel", onCancel)
        PrimaryButton("Play now", onNow, icon = Icons.Next)
    }
}
