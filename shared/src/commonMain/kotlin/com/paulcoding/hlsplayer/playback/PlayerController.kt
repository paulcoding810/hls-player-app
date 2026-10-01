package com.paulcoding.hlsplayer.playback

import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.library.PlaybackConfig
import com.paulcoding.hlsplayer.library.findEpisode
import com.paulcoding.hlsplayer.library.findMovie
import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.library.resolveConfig
import com.paulcoding.hlsplayer.library.resumeEpisodeId
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Position
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.model.Subtitle
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.stremio.FoundStream
import com.paulcoding.hlsplayer.stremio.StremioClient
import com.paulcoding.hlsplayer.stremio.StremioException
import com.paulcoding.hlsplayer.stremio.pickStream
import com.paulcoding.hlsplayer.subtitles.Cue
import com.paulcoding.hlsplayer.subtitles.parseVtt
import com.paulcoding.hlsplayer.subtitles.toVtt
import com.paulcoding.hlsplayer.util.fileNameOf
import com.paulcoding.hlsplayer.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Something to play: an episode of a stored movie, or a URL opened outside the library. */
sealed interface PlayTarget {
    data class Episode(val movieId: String, val episodeId: String?) : PlayTarget
    data class Url(val url: String) : PlayTarget
}

/** A subtitle track drawn by the app; cues are kept as born so an offset is never lossy. */
data class SubtitleTrack(val id: String, val label: String, val lang: String, val cues: List<Cue>)

data class PlayerUiState(
    val movie: Movie? = null,
    val episodeId: String? = null,
    val title: String = "",
    /** A URL being played outside the library, until it is kept. */
    val grabbed: String? = null,
    val savedGrabbed: Boolean = false,
    val resolving: Boolean = false,
    val error: String? = null,
    val streams: List<FoundStream> = emptyList(),
    val current: String? = null,
    /** A stored position waiting for Resume or Start over; playback holds until then. */
    val resume: Position? = null,
    /** Which skip button the position calls for, when auto skip is off. */
    val skip: SkipWindow? = null,
    /** Seconds before the outro advances, or null. */
    val countdown: Int? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val primarySubtitle: String? = null,
    val secondarySubtitle: String? = null,
    /** Seconds the cues are shifted by; positive shows them later. */
    val subtitleOffset: Double = 0.0,
    val stripped: StripResult? = null,
) {
    val episodeIndex get() = movie?.episodes?.indexOfFirst { it.id == episodeId } ?: -1
    val hasPrevious get() = episodeIndex > 0
    val hasNext get() = episodeIndex > -1 && episodeIndex + 1 < (movie?.episodes?.size ?: 0)
}

/**
 * Fetches a subtitle file's text with the stream's `Referer` — the extension's
 * tab-scoped header rule sends it on every request the player makes, and a
 * host that guards its video often guards its subtitles the same way.
 */
fun interface TextFetcher {
    suspend fun fetch(url: String, referer: String): String
}

/**
 * Every rule about playing, ported from the extension's `Player.jsx`: resolving
 * an addon episode's streams, resume, progress, intro and outro skipping, the
 * outro countdown, preloading the next episode and the subtitle tracks. The
 * [engine] only plays what it is told.
 */
class PlayerController(
    private val data: AppData,
    private val client: StremioClient,
    val engine: VideoEngine,
    private val scope: CoroutineScope,
    private val fetchText: TextFetcher,
    private val now: () -> Long = ::nowMillis,
    /** Where stored writes run: leaving the player cancels [scope], and the last position must still land. */
    private val persist: CoroutineScope = scope,
) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val preloader = Preloader<List<FoundStream>>(scope, PRELOAD_TTL_MILLIS, now)
    private val longest = LongestDuration()

    /** The `bingeGroup` of a stream chosen by hand, which the next episode prefers. */
    private var bingeGroup = ""
    private var key = ""
    private var savedAt = 0.0
    private var skipped = false
    private var preloaded = false
    private var chosenTrack = false
    /** Set while the resume prompt owns the starting position. */
    private var holding = false
    private var loadJob: Job? = null
    private var countdownJob: Job? = null
    private var subtitleJob: Job? = null

    private val config: PlaybackConfig
        get() = resolveConfig(_state.value.movie, data.settings.value)

    init {
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch { engine.state.collect(::onTick) }
        // Edits elsewhere — the form, a refresh — must reach what is playing.
        scope.launch {
            data.library.collect { library ->
                val movie = _state.value.movie ?: return@collect
                _state.update { it.copy(movie = findMovie(library, movie.id) ?: it.movie) }
            }
        }
    }

    fun open(target: PlayTarget) {
        when (target) {
            is PlayTarget.Url -> {
                _state.value = PlayerUiState(grabbed = target.url, title = fileNameOf(target.url))
                startLoad(StreamSource(src = target.url, stream = null, subtitles = emptyList()))
            }
            is PlayTarget.Episode -> {
                val movie = findMovie(data.library.value, target.movieId) ?: return fail("That movie is no longer in the library.")
                val episodeId = findEpisode(movie, target.episodeId)?.id ?: resumeEpisodeId(movie) ?: return fail("This movie has no episodes.")
                goTo(movie, episodeId)
            }
        }
    }

    fun goToEpisode(episodeId: String) {
        val movie = _state.value.movie ?: return
        saveNow()
        goTo(movie, episodeId)
    }

    fun next() {
        val state = _state.value
        val movie = state.movie ?: return
        if (state.hasNext) goToEpisode(movie.episodes[state.episodeIndex + 1].id) else engine.pause()
    }

    fun previous() {
        val state = _state.value
        val movie = state.movie ?: return
        if (state.hasPrevious) goToEpisode(movie.episodes[state.episodeIndex - 1].id)
    }

    private fun goTo(movie: Movie, episodeId: String) {
        val episode = findEpisode(movie, episodeId) ?: return
        _state.value = PlayerUiState(movie = movie, episodeId = episodeId, title = episode.title)
        persist.launch { data.setLastPlayed(movie.id, episodeId) }
        startLoad(StreamSource(episode.src, episode.stream, episode.subtitles.orEmpty(), episode.key))
    }

    private data class StreamSource(val src: String?, val stream: StreamRef?, val subtitles: List<Subtitle>, val key: String = src.orEmpty())

    private fun fail(message: String) = _state.update { it.copy(error = message, resolving = false) }

    private fun startLoad(source: StreamSource) {
        loadJob?.cancel()
        countdownJob?.cancel()
        // Progress is stored under the episode's key, not the URL: an addon
        // hands out a different one on every play.
        key = source.key
        savedAt = 0.0
        skipped = false
        preloaded = false
        chosenTrack = false
        holding = false
        longest.reset()
        loadJob = scope.launch {
            val playable = if (source.stream != null) {
                _state.update { it.copy(resolving = true) }
                try {
                    resolve(source.stream)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: StremioException) {
                    return@launch fail(error.message.orEmpty())
                } finally {
                    _state.update { it.copy(resolving = false) }
                }
            } else {
                listOf(FoundStream(source.src.orEmpty(), "", "", "", "", emptyList()))
            }

            val stored = data.getProgress(key)
            holding = stored != null
            _state.update { it.copy(streams = if (source.stream != null) playable else emptyList(), resume = stored) }
            val chosen = pickStream(playable, bingeGroup) ?: return@launch
            apply(chosen, source.subtitles, startAt = null, autoplay = stored == null && config.autoplay)
        }
    }

    private suspend fun resolve(stream: StreamRef): List<FoundStream> {
        val plugin = data.plugins.value.firstOrNull { it.id == stream.pluginId }
            ?: throw StremioException("The source this episode came from is gone.")
        val held = preloader.take("stremio:${stream.pluginId}:${stream.videoId}")
        // A preload that failed is simply asked again, and reports as usual.
        return held?.let {
            try {
                it.await()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        } ?: client.listStreams(plugin, stream)
    }

    /** Puts one stream on the engine; [startAt] resumes where playback already was. */
    private fun apply(found: FoundStream, own: List<Subtitle>, startAt: Double?, autoplay: Boolean) {
        val config = config
        chosenTrack = false
        engine.load(
            LoadRequest(
                url = found.url,
                referer = found.referer.ifEmpty { config.referer },
                adPattern = compilePattern(config.adPattern),
                startAt = startAt,
                playWhenReady = autoplay || startAt != null,
            ),
        )
        engine.setVolume(config.volume)
        engine.setMuted(config.muted)
        engine.setRate(config.playbackRate)
        _state.update { it.copy(current = found.url, error = null) }
        loadSubtitles(own + found.subtitles, found.referer.ifEmpty { config.referer })
    }

    /** Swapping stream keeps the position: progress is keyed by episode, not URL. */
    fun chooseStream(found: FoundStream) {
        val state = _state.value
        if (found.url == state.current) return
        bingeGroup = found.bingeGroup
        val episode = findEpisode(state.movie, state.episodeId)
        apply(found, episode?.subtitles.orEmpty(), startAt = engine.state.value.position, autoplay = true)
    }

    fun resume() {
        val position = _state.value.resume?.position ?: 0.0
        _state.update { it.copy(resume = null) }
        startAt(position)
    }

    fun restart() {
        _state.update { it.copy(resume = null) }
        persist.launch { data.clearProgress(key) }
        val config = config
        startAt(if (config.autoSkip) config.skipLeading.toDouble() else 0.0)
    }

    private fun startAt(seconds: Double) {
        holding = false
        savedAt = seconds
        engine.seekTo(seconds)
        engine.play()
    }

    fun togglePlay() = if (engine.state.value.playing) engine.pause() else engine.play()

    fun seekTo(seconds: Double) = engine.seekTo(seconds)

    fun skipIntro() {
        engine.seekTo(config.skipLeading.toDouble())
        _state.update { it.copy(skip = null) }
    }

    fun skipOutro() = next()

    /** Declines the pending outro jump; the window is not offered again this play. */
    fun cancelCountdown() {
        countdownJob?.cancel()
        _state.update { it.copy(countdown = null) }
    }

    fun setRate(rate: Double) {
        engine.setRate(rate)
        persist.launch { data.updateSettings { it.copy(playbackRate = rate) } }
    }

    fun setVolume(volume: Double, muted: Boolean) {
        engine.setVolume(volume)
        engine.setMuted(muted)
        persist.launch { data.updateSettings { it.copy(volume = volume, muted = muted) } }
    }

    fun selectLevel(id: String?) = engine.selectLevel(id)

    /** Stored before leaving, since leaving disposes the player. */
    fun saveNow() {
        val position = engine.state.value
        if (key.isNotEmpty() && !holding) {
            persist.launch { data.saveProgress(key, position.position, longest.update(position.duration)) }
        }
    }

    suspend fun keepGrabbed() {
        val url = _state.value.grabbed ?: return
        data.addMovie(fileNameOf(url), listOf(EpisodeDraft(fileNameOf(url), src = url)))
        _state.update { it.copy(savedGrabbed = true) }
    }

    // ---- engine callbacks

    private fun onEvent(event: EngineEvent) {
        when (event) {
            EngineEvent.Ready -> {
                _state.update { it.copy(error = null) }
                val config = config
                val duration = longest.update(engine.state.value.duration)
                if (config.autoSkip && !holding && config.skipLeading > 0 && duration.isFinite() &&
                    config.skipLeading < duration && engine.state.value.position < config.skipLeading
                ) {
                    engine.seekTo(config.skipLeading.toDouble())
                }
            }
            EngineEvent.Paused -> saveNow()
            EngineEvent.Ended -> {
                if (endedEarly(longest.value, engine.state.value.position)) {
                    _state.update { it.copy(error = "The stream ended early.") }
                    return
                }
                persist.launch { data.clearProgress(key) }
                next()
            }
            is EngineEvent.Failed -> _state.update { it.copy(error = event.message) }
            is EngineEvent.Stripped -> _state.update { it.copy(stripped = StripResult("", event.removed, event.total)) }
        }
    }

    private fun onTick(engineState: EngineState) {
        if (_state.value.current == null) return
        val time = engineState.position
        val duration = longest.update(engineState.duration)
        if (key.isNotEmpty() && !holding && kotlin.math.abs(time - savedAt) >= PROGRESS_INTERVAL) {
            savedAt = time
            persist.launch { data.saveProgress(key, time, duration) }
        }

        val config = config
        val window = skipAt(time, duration, config.skipLeading, config.skipTrailing)
        if (!preloaded && preloadDue(time, duration, config.skipTrailing, PRELOAD_LEAD)) {
            preloaded = true
            preloadNext()
        }
        if (!config.autoSkip) {
            // Turning auto skip off mid-countdown hands the choice back over.
            cancelCountdown()
            _state.update { it.copy(skip = window) }
            return
        }
        _state.update { it.copy(skip = null) }
        // Seeking back out of the outro takes the pending jump with it.
        if (window != SkipWindow.Outro && _state.value.countdown != null) cancelCountdown()
        // Nothing to jump to on the last episode, so it plays out instead.
        if (window == SkipWindow.Outro && _state.value.hasNext && !skipped) {
            skipped = true
            startCountdown()
        }
    }

    /** Held while paused: counting on while someone reads the credits would jump them. */
    private fun startCountdown() {
        countdownJob?.cancel()
        _state.update { it.copy(countdown = OUTRO_COUNTDOWN) }
        countdownJob = scope.launch {
            while (true) {
                val left = _state.value.countdown ?: return@launch
                if (left <= 0) {
                    _state.update { it.copy(countdown = null) }
                    next()
                    return@launch
                }
                delay(1000)
                if (engine.state.value.playing) _state.update { it.copy(countdown = (it.countdown ?: 1) - 1) }
            }
        }
    }

    /** A hand-added episode already has its URL; there is nothing to resolve. */
    private fun preloadNext() {
        val state = _state.value
        val next = state.movie?.episodes?.getOrNull(state.episodeIndex + 1) ?: return
        val stream = next.stream ?: return
        if (state.episodeIndex < 0) return
        preloader.start(next.key) {
            val plugin = data.plugins.value.firstOrNull { it.id == stream.pluginId }
                ?: throw StremioException("The source this episode came from is gone.")
            client.listStreams(plugin, stream)
        }
    }

    // ---- subtitles

    private fun loadSubtitles(list: List<Subtitle>, referer: String) {
        subtitleJob?.cancel()
        _state.update { it.copy(subtitles = emptyList(), primarySubtitle = null, secondarySubtitle = null) }
        if (list.isEmpty()) return
        subtitleJob = scope.launch {
            val tracks = list.mapIndexed { index, entry ->
                async {
                    try {
                        val cues = parseVtt(toVtt(fetchText.fetch(entry.src, referer)))
                        if (cues.isEmpty()) null else SubtitleTrack("sub-$index", entry.label, entry.lang.orEmpty(), cues)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // One bad file must not cost the others, or the video.
                        null
                    }
                }
            }.awaitAll().filterNotNull()
            _state.update { it.copy(subtitles = tracks) }
            chooseDefaultTrack(tracks)
        }
    }

    /** Turns a track on once per stream when the setting asks for it. */
    private fun chooseDefaultTrack(tracks: List<SubtitleTrack>) {
        val settings = data.settings.value
        if (chosenTrack || !settings.subtitlesOn || tracks.isEmpty()) return
        chosenTrack = true
        val wanted = settings.subtitleLang
        val pick = tracks.firstOrNull { wanted.isNotEmpty() && it.lang.lowercase().startsWith(wanted) } ?: tracks.first()
        _state.update { it.copy(primarySubtitle = pick.id) }
    }

    fun choosePrimarySubtitle(id: String?) = _state.update {
        // Picking the second track as the first leaves nothing stacked above it.
        it.copy(primarySubtitle = id, secondarySubtitle = if (id == it.secondarySubtitle) null else it.secondarySubtitle)
    }

    fun chooseSecondarySubtitle(id: String?) = _state.update { state ->
        state.copy(secondarySubtitle = id?.takeIf { it != state.primarySubtitle })
    }

    fun setSubtitleOffset(seconds: Double) = _state.update { it.copy(subtitleOffset = seconds.coerceIn(-30.0, 30.0)) }
}
