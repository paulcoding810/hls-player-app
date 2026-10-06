package com.paulcoding.hlsplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcoding.hlsplayer.BackHandler
import com.paulcoding.hlsplayer.FullscreenEffect
import com.paulcoding.hlsplayer.KeepScreenOn
import com.paulcoding.hlsplayer.LocalDeps
import com.paulcoding.hlsplayer.LocalNavigator
import com.paulcoding.hlsplayer.playback.PlayTarget
import com.paulcoding.hlsplayer.playback.PlayerController
import com.paulcoding.hlsplayer.playback.SkipWindow
import com.paulcoding.hlsplayer.ui.Banner
import com.paulcoding.hlsplayer.ui.GhostButton
import com.paulcoding.hlsplayer.ui.IconAction
import com.paulcoding.hlsplayer.ui.LinkButton
import com.paulcoding.hlsplayer.ui.Icons
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.Tone
import com.paulcoding.hlsplayer.ui.focusRing
import com.paulcoding.hlsplayer.util.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the controls stay after the last touch while playing. */
private const val IDLE_MILLIS = 2500L

private enum class Panel { Streams, Subtitles, Playback }

/**
 * The extension's `player.html`. The engine only plays; [PlayerController]
 * decides what. Controls fade on idle, the episodes panel does not.
 */
@Composable
fun PlayerScreen(target: PlayTarget) {
    val deps = LocalDeps.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val engine = remember { deps.newEngine() }
    val controller = remember { PlayerController(deps.data, deps.client, engine, scope, deps.fetchText, persist = deps.persistScope) }

    val state by controller.state.collectAsState()
    val engineState by engine.state.collectAsState()
    val settings by deps.data.settings.collectAsState()
    val positions by deps.data.progress.collectAsState()

    var touched by remember { mutableStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    var episodesOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Panel?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    /** The seek bar's value while dragging; the engine is only told on release. */
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    /** The video itself: where a remote's focus rests while the controls are away. */
    val stage = remember { FocusRequester() }
    /**
     * Where up and down land. The video fills the screen, so the bars are not
     * below or above it to a focus search; the stage sends focus there itself.
     */
    val playButton = remember { FocusRequester() }
    val topButton = remember { FocusRequester() }
    /** True while focus is in either bar, so Back returns to the video instead of leaving. */
    var inBars by remember { mutableStateOf(false) }

    LaunchedEffect(target) { controller.open(target) }
    DisposableEffect(Unit) {
        onDispose {
            controller.saveNow()
            engine.release()
        }
    }
    LaunchedEffect(touched, engineState.playing, scrubbing, sheet) {
        idle = false
        if (engineState.playing && scrubbing == null && sheet == null) {
            delay(IDLE_MILLIS)
            idle = true
        }
    }

    FullscreenEffect(fullscreen)
    KeepScreenOn(engineState.playing)
    BackHandler {
        when {
            sheet != null -> sheet = null
            episodesOpen -> episodesOpen = false
            inBars -> { runCatching { stage.requestFocus() } }
            else -> { controller.saveNow(); navigator.back() }
        }
    }

    // A control that faded out takes the focus with it, so it comes back to the video.
    LaunchedEffect(idle, engineState.playing) {
        if (idle && engineState.playing) runCatching { stage.requestFocus() }
    }
    LaunchedEffect(Unit) { runCatching { stage.requestFocus() } }

    fun seekBy(seconds: Double) {
        val duration = engineState.duration
        val target = (engineState.position + seconds).coerceAtLeast(0.0)
        controller.seekTo(if (duration.isFinite() && duration > 0) target.coerceAtMost(duration) else target)
    }

    val primary = state.subtitles.firstOrNull { it.id == state.primarySubtitle }
    val secondary = state.subtitles.firstOrNull { it.id == state.secondarySubtitle }
    val showControls = !idle || !engineState.playing
    val skipAction = if (state.skip == SkipWindow.Outro && !state.hasNext) null else state.skip

    Column(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // Any key wakes the controls, as a touch does.
            touched++
            when (event.key) {
                Key.MediaPlayPause -> { controller.togglePlay(); true }
                Key.MediaPlay -> { if (!engineState.playing) controller.togglePlay(); true }
                Key.MediaPause -> { if (engineState.playing) controller.togglePlay(); true }
                Key.MediaFastForward -> { seekBy(10.0); true }
                Key.MediaRewind -> { seekBy(-10.0); true }
                Key.MediaNext -> { controller.next(); true }
                Key.MediaPrevious -> { controller.previous(); true }
                else -> false
            }
        },
    ) {
        state.error?.let { Banner("$it Streams that reject the request usually need a matching Referer.", Tone.Danger) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            engine.Surface(Modifier.fillMaxSize())

            // The whole frame toggles the controls; a tap while they show plays or pauses.
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(stage)
                    // With the video focused, a remote plays, pauses and seeks; up and down
                    // reach the bars once they show, so the first press only wakes them.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { controller.togglePlay(); true }
                            Key.DirectionLeft -> { seekBy(-10.0); true }
                            Key.DirectionRight -> { seekBy(10.0); true }
                            // The first press only wakes the bars; the next one moves into them.
                            Key.DirectionDown -> { if (showControls) runCatching { playButton.requestFocus() }; true }
                            Key.DirectionUp -> { if (showControls) runCatching { topButton.requestFocus() }; true }
                            else -> false
                        }
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (showControls && engineState.playing) idle = true else touched++
                    },
            )

            SubtitleOverlay(primary, secondary, engineState.position, state.subtitleOffset, settings,
                liftedBy = if (showControls) 72 else 0)

            if (state.resolving) {
                Text("Finding a stream…", color = Palette.inkMuted, modifier = Modifier.align(Alignment.Center))
            } else if (!engineState.playing && state.current != null && state.resume == null) {
                IconAction(Icons.Play, "Play", { controller.togglePlay(); touched++ }, Modifier.align(Alignment.Center), tint = Color.White, size = 72)
            }

            Row(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (showControls) 112.dp else 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.countdown?.let { left ->
                    CountdownCard(left, onCancel = controller::cancelCountdown, onNow = controller::next)
                }
                if (state.countdown == null && skipAction != null) {
                    GhostButton(if (skipAction == SkipWindow.Intro) "Skip intro" else "Skip outro",
                        { if (skipAction == SkipWindow.Intro) controller.skipIntro() else controller.skipOutro() }, icon = Icons.Next)
                }
            }

            Fade(showControls || episodesOpen, Modifier.align(Alignment.TopCenter).onFocusChanged { inBars = it.hasFocus }) {
                TopControls(
                    focus = topButton,
                    title = state.movie?.let { "${it.title} · ${state.title}" } ?: state.title,
                    counter = state.movie?.takeIf { it.episodes.size > 1 && state.episodeIndex > -1 }?.let { "${state.episodeIndex + 1} / ${it.episodes.size}" },
                    grabbed = state.grabbed != null,
                    saved = state.savedGrabbed,
                    episodesOpen = episodesOpen,
                    onBack = { controller.saveNow(); navigator.back() },
                    onEpisodes = { episodesOpen = !episodesOpen },
                    onKeep = { scope.launch { controller.keepGrabbed(); navigator.warn("Added to the library.") } },
                )
            }

            if (episodesOpen && state.movie != null) {
                EpisodesPanel(
                    state.movie!!.episodes, state.episodeId, positions, onSelect = controller::goToEpisode,
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp).widthIn(max = maxWidth * 0.85f),
                )
            }

            Fade(showControls, Modifier.align(Alignment.BottomCenter).onFocusChanged { inBars = it.hasFocus }) {
                BottomControls(
                    playFocus = playButton,
                    position = scrubbing?.toDouble() ?: engineState.position,
                    duration = engineState.duration,
                    playing = engineState.playing,
                    muted = settings.muted,
                    hasPrevious = state.hasPrevious,
                    hasNext = state.hasNext,
                    streams = state.streams.size,
                    subtitles = state.subtitles.isNotEmpty(),
                    subtitlesOn = state.primarySubtitle != null,
                    fullscreen = fullscreen,
                    onScrub = { scrubbing = it; touched++ },
                    onScrubEnd = { scrubbing?.let { controller.seekTo(it.toDouble()) }; scrubbing = null },
                    onToggle = { controller.togglePlay(); touched++ },
                    onPrevious = controller::previous,
                    onNext = controller::next,
                    onMute = { controller.setVolume(settings.volume, !settings.muted) },
                    onStreams = { sheet = Panel.Streams },
                    onSubtitles = { sheet = Panel.Subtitles },
                    onPlayback = { sheet = Panel.Playback },
                    onFullscreen = { fullscreen = !fullscreen },
                )
            }
        }
    }

    state.resume?.let { stored -> ResumePrompt(stored, controller::resume, controller::restart) }

    when (sheet) {
        Panel.Streams -> StreamPicker(state.streams, state.current, controller::chooseStream) { sheet = null }
        Panel.Subtitles -> SubtitleSettings(
            state.subtitles, state.primarySubtitle, state.secondarySubtitle, state.subtitleOffset, settings,
            onPrimary = controller::choosePrimarySubtitle,
            onSecondary = controller::chooseSecondarySubtitle,
            onOffset = controller::setSubtitleOffset,
            onSettings = { change -> scope.launch { deps.data.updateSettings(change) } },
            onDismiss = { sheet = null },
        )
        Panel.Playback -> RateAndQuality(settings.playbackRate, engineState.levels, engineState.level, controller::setRate, controller::selectLevel) { sheet = null }
        null -> {}
    }
}

/** Unscoped, so it can be used under any layout's receiver. */
@Composable
private fun Fade(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) { content() }

private val scrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))
private val scrimUp = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))

@Composable
private fun TopControls(
    focus: FocusRequester,
    title: String,
    counter: String?,
    grabbed: Boolean,
    saved: Boolean,
    episodesOpen: Boolean,
    onBack: () -> Unit,
    onEpisodes: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(scrim).padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconAction(Icons.Library, "Back to the library", onBack, tint = Color.White)
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
        counter?.let { Text(it, color = Palette.inkFaint, fontSize = 12.sp) }
        if (grabbed) GhostButton(if (saved) "Added to library" else "Add to library", onKeep, Modifier.focusRequester(focus), enabled = !saved, icon = Icons.Plus)
        else GhostButton("Episodes", onEpisodes, Modifier.focusRequester(focus), icon = Icons.Playlist, active = episodesOpen)
    }
}

@Composable
private fun BottomControls(
    playFocus: FocusRequester,
    position: Double,
    duration: Double,
    playing: Boolean,
    muted: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    streams: Int,
    subtitles: Boolean,
    subtitlesOn: Boolean,
    fullscreen: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMute: () -> Unit,
    onStreams: () -> Unit,
    onSubtitles: () -> Unit,
    onPlayback: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val seekable = duration.isFinite() && duration > 0
    Column(Modifier.fillMaxWidth().background(scrimUp).padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 8.dp)) {
        Slider(
            value = if (seekable) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubEnd,
            valueRange = 0f..(if (seekable) duration.toFloat() else 1f),
            enabled = seekable,
            modifier = Modifier.focusRing(),
            colors = SliderDefaults.colors(thumbColor = Palette.primary, activeTrackColor = Palette.primary, inactiveTrackColor = Palette.lineStrong),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Prev, "Previous episode", onPrevious, enabled = hasPrevious, tint = Color.White)
            IconAction(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", onToggle, Modifier.focusRequester(playFocus), tint = Color.White)
            IconAction(Icons.Next, "Next episode", onNext, enabled = hasNext, tint = Color.White)
            Text(
                "${formatTime(position)} / ${if (duration == Double.POSITIVE_INFINITY) "Live" else formatTime(duration)}",
                fontSize = 12.sp, color = Palette.inkMuted, modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            IconAction(if (muted) Icons.VolumeMuted else Icons.Volume, if (muted) "Unmute" else "Mute", onMute, tint = Color.White)
            if (streams > 1) IconAction(Icons.Layers, "Stream source", onStreams, tint = Color.White)
            IconAction(Icons.Settings, if (subtitles) "Subtitles" else "Subtitle look", onSubtitles, tint = if (subtitlesOn) Palette.primary else Color.White)
            LinkButton("Speed", onPlayback)
            IconAction(if (fullscreen) Icons.FullscreenExit else Icons.Fullscreen, if (fullscreen) "Exit fullscreen" else "Fullscreen", onFullscreen, tint = Color.White)
        }
    }
}
