package com.paulcoding.hlsplayer.android

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.paulcoding.hlsplayer.PlatformEngine
import com.paulcoding.hlsplayer.playback.EngineEvent
import com.paulcoding.hlsplayer.playback.EngineState
import com.paulcoding.hlsplayer.playback.Level
import com.paulcoding.hlsplayer.playback.LoadRequest
import com.paulcoding.hlsplayer.playback.MAX_PLAYLIST_RETRIES
import com.paulcoding.hlsplayer.playback.SCAN_LIMIT
import com.paulcoding.hlsplayer.playback.decoyPrefixLength
import com.paulcoding.hlsplayer.playback.isPngWrapped
import com.paulcoding.hlsplayer.playback.PNG_SIGNATURE
import com.paulcoding.hlsplayer.playback.stripAdSegments
import com.paulcoding.hlsplayer.util.isDash
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** How often the position is read: Media3 reports it on request, not continuously. */
private const val TICK_MILLIS = 250L

/**
 * Media3 as the engine. On a phone `Referer` is an ordinary request header,
 * so it is set on the HTTP source directly — no header-rewriting machinery.
 */
@OptIn(UnstableApi::class)
class Media3Engine(context: Context) : PlatformEngine {
    private val player = ExoPlayer.Builder(context).build()
    private val scope = MainScope()
    private var readyFired = false
    private var muted = false
    private var volume = 1.0

    override val state = MutableStateFlow(EngineState())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_READY && !readyFired) {
                    readyFired = true
                    tick()
                    events.tryEmit(EngineEvent.Ready)
                }
                if (playbackState == Player.STATE_ENDED) {
                    tick()
                    events.tryEmit(EngineEvent.Ended)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.update { it.copy(playing = player.playWhenReady) }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                state.update { it.copy(playing = playWhenReady) }
                if (!playWhenReady) events.tryEmit(EngineEvent.Paused)
            }

            override fun onPlayerError(error: PlaybackException) {
                events.tryEmit(EngineEvent.Failed(describe(error)))
            }

            override fun onTracksChanged(tracks: Tracks) {
                state.update { it.copy(levels = levelsOf(tracks)) }
            }
        })
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    private fun tick() {
        val duration = when {
            player.isCurrentMediaItemLive -> Double.POSITIVE_INFINITY
            player.duration == C.TIME_UNSET -> Double.NaN
            else -> player.duration / 1000.0
        }
        state.update {
            it.copy(position = player.currentPosition / 1000.0, duration = duration, buffered = player.bufferedPosition / 1000.0)
        }
    }

    override fun load(request: LoadRequest) {
        readyFired = false
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(if (request.referer.isEmpty()) emptyMap() else mapOf("Referer" to request.referer))
        val upstream = DecoyStrippingDataSource.Factory(http)
        val retries = DefaultLoadErrorHandlingPolicy(MAX_PLAYLIST_RETRIES)
        val item = MediaItem.fromUri(request.url)
        val source = if (isDash(request.url)) {
            DashMediaSource.Factory(upstream).setLoadErrorHandlingPolicy(retries).createMediaSource(item)
        } else {
            HlsMediaSource.Factory(upstream)
                .setPlaylistParserFactory(AdStrippingParserFactory(request.adPattern) { removed, total ->
                    events.tryEmit(EngineEvent.Stripped(removed, total))
                })
                .setLoadErrorHandlingPolicy(retries)
                .createMediaSource(item)
        }
        player.setMediaSource(source, ((request.startAt ?: 0.0) * 1000).toLong())
        player.prepare()
        player.playWhenReady = request.playWhenReady
        state.update { EngineState(position = request.startAt ?: 0.0, playing = request.playWhenReady) }
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }
    override fun seekTo(seconds: Double) { player.seekTo((seconds * 1000).toLong()); tick() }
    override fun setRate(rate: Double) { player.setPlaybackSpeed(rate.toFloat()) }
    override fun setVolume(volume: Double) { this.volume = volume; applyVolume() }
    override fun setMuted(muted: Boolean) { this.muted = muted; applyVolume() }
    private fun applyVolume() { player.volume = if (muted) 0f else volume.toFloat() }

    override fun selectLevel(id: String?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (id != null) {
            val (group, track) = id.split(':').map(String::toInt)
            val selected = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.getOrNull(group) ?: return
            builder.setOverrideForType(TrackSelectionOverride(selected.mediaTrackGroup, track))
        }
        player.trackSelectionParameters = builder.build()
        state.update { it.copy(level = id) }
    }

    override fun stop() = player.stop()

    override fun release() {
        scope.cancel()
        player.release()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Subtitles are drawn by the app, over every engine alike.
                    subtitleView?.visibility = View.GONE
                    player = this@Media3Engine.player
                }
            },
            modifier = modifier,
        )
    }
}

private fun levelsOf(tracks: Tracks): List<Level> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMapIndexed { group, info ->
        (0 until info.length).filter { info.isTrackSupported(it) }.map { track ->
            val format = info.getTrackFormat(track)
            Level("$group:$track", if (format.height > 0) "${format.height}p" else "${format.bitrate / 1000} kbps", format.height)
        }
    }.sortedByDescending { it.height }

private fun describe(error: PlaybackException): String {
    val cause = error.cause
    return when {
        cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> "The stream answered ${cause.responseCode}."
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "The stream could not be reached."
        else -> error.message ?: "Playback failed."
    }
}

/**
 * Cuts the ad segments out of every media playlist before Media3 parses it —
 * the counterpart of the extension's VHS response hook.
 */
@OptIn(UnstableApi::class)
private class AdStrippingParserFactory(
    private val pattern: Regex?,
    private val onStripped: (removed: Int, total: Int) -> Unit,
) : HlsPlaylistParserFactory {
    private val inner = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser() = wrap(inner.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ) = wrap(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, stream ->
        if (pattern == null) return@Parser parser.parse(uri, stream)
        val result = stripAdSegments(stream.readBytes().decodeToString(), pattern)
        if (result.removed > 0) onStripped(result.removed, result.total)
        parser.parse(uri, ByteArrayInputStream(result.text.encodeToByteArray()))
    }
}

/**
 * Drops the PNG a disguised segment is wrapped in. Only a read from the start
 * is sniffed, and only past a PNG signature; everything else passes through.
 */
@OptIn(UnstableApi::class)
private class DecoyStrippingDataSource(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = DecoyStrippingDataSource(upstream.createDataSource())
    }

    private var head = ByteArray(0)
    private var headAt = 0
    private var headEnd = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        headAt = 0
        headEnd = 0
        if (dataSpec.position != 0L) return length

        head = ByteArray(SCAN_LIMIT)
        headEnd = fill(PNG_SIGNATURE.size)
        if (!isPngWrapped(head, headEnd) && headEnd == PNG_SIGNATURE.size) return length
        headEnd += fill(SCAN_LIMIT - headEnd, headEnd)
        val prefix = decoyPrefixLength(head, headEnd)
        headAt = prefix
        return if (length == C.LENGTH_UNSET.toLong() || prefix == 0) length else length - prefix
    }

    /** Reads until [count] bytes are in or the input ends; returns how many arrived. */
    private fun fill(count: Int, offset: Int = 0): Int {
        var read = 0
        while (read < count) {
            val n = upstream.read(head, offset + read, count - read)
            if (n == C.RESULT_END_OF_INPUT) break
            read += n
        }
        return read
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (headAt < headEnd) {
            val n = minOf(length, headEnd - headAt)
            System.arraycopy(head, headAt, buffer, offset, n)
            headAt += n
            return n
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        head = ByteArray(0)
        headAt = 0
        headEnd = 0
        upstream.close()
    }
}
