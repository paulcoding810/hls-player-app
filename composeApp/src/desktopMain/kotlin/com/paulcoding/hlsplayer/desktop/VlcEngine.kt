package com.paulcoding.hlsplayer.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.paulcoding.hlsplayer.PlatformEngine
import com.paulcoding.hlsplayer.playback.EngineEvent
import com.paulcoding.hlsplayer.playback.EngineState
import com.paulcoding.hlsplayer.playback.LoadRequest
import com.paulcoding.hlsplayer.proxy.ProxyServer
import com.paulcoding.hlsplayer.proxy.ProxySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import kotlin.math.min

/** libVLC, found once from the installed VLC; null when there is none. */
object Vlc {
    val factory: MediaPlayerFactory? by lazy {
        runCatching { if (NativeDiscovery().discover()) MediaPlayerFactory("--quiet", "--no-video-title-show") else null }.getOrNull()
    }
}

/** One decoded frame; the buffer is padded to the codec's block size, so only [width]×[height] is picture. */
private class Frame(val image: Image, val width: Int, val height: Int) {
    val aspect get() = width.toFloat() / height
}

/**
 * libVLC as the engine, through the same local proxy as iOS: VLC is handed a
 * loopback URL and the proxy sends the `Referer`, cuts the ads and unwraps
 * disguised segments. Frames come back as pixels and are drawn by Compose,
 * since a native view cannot sit under Compose's own controls.
 */
class VlcEngine(private val proxy: ProxyServer) : PlatformEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player: EmbeddedMediaPlayer? = Vlc.factory?.mediaPlayers()?.newEmbeddedMediaPlayer()
    private val frame = MutableStateFlow<Frame?>(null)
    private var token: String? = null
    private var readyFired = false
    private var rate = 1.0
    private var volume = 1.0
    private var muted = false

    override val state = MutableStateFlow(EngineState())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)

    init {
        current = this
        player?.let { it.videoSurface().set(Vlc.factory!!.videoSurfaces().newVideoSurface(Formats(), Renderer(), true)) }
        player?.events()?.addMediaPlayerEventListener(Listener())
    }

    private inner class Formats : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat = RV32BufferFormat(sourceWidth, sourceHeight)
        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {}
        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
    }

    private inner class Renderer : RenderCallback {
        private var bytes = ByteArray(0)

        override fun lock(mediaPlayer: MediaPlayer) {}
        override fun unlock(mediaPlayer: MediaPlayer) {}

        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, format: BufferFormat, displayWidth: Int, displayHeight: Int) {
            val width = format.width
            val height = format.height
            val buffer = nativeBuffers[0]
            if (bytes.size != buffer.capacity()) bytes = ByteArray(buffer.capacity())
            buffer.rewind()
            buffer.get(bytes)
            // RV32 is BGRA in memory; makeRaster copies, so the array is reused next frame.
            val image = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, width * 4)
            frame.value = Frame(image, displayWidth.takeIf { it > 0 } ?: width, displayHeight.takeIf { it > 0 } ?: height)
        }
    }

    private inner class Listener : MediaPlayerEventAdapter() {
        override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
            if (readyFired) return
            readyFired = true
            // A stream with no length that cannot seek is live.
            if (mediaPlayer.status().length() <= 0 && !mediaPlayer.status().isSeekable) {
                state.update { it.copy(duration = Double.POSITIVE_INFINITY) }
            }
            events.tryEmit(EngineEvent.Ready)
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            state.update { it.copy(playing = true, buffering = false) }
            // Volume, mute and rate only stick once audio is running.
            mediaPlayer.submit {
                mediaPlayer.audio().setVolume((volume * 100).toInt())
                mediaPlayer.audio().setMute(muted)
                mediaPlayer.controls().setRate(rate.toFloat())
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) = state.update { it.copy(playing = false) }
        override fun stopped(mediaPlayer: MediaPlayer) = state.update { it.copy(playing = false, buffering = false) }
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = state.update { it.copy(buffering = newCache < 100f) }
        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = state.update { it.copy(position = newTime / 1000.0) }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (newLength > 0) state.update { it.copy(duration = newLength / 1000.0) }
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            state.update { it.copy(playing = false) }
            events.tryEmit(EngineEvent.Ended)
        }

        override fun error(mediaPlayer: MediaPlayer) {
            events.tryEmit(EngineEvent.Failed("VLC couldn't open this stream."))
        }
    }

    internal fun stripped(removed: Int, total: Int) {
        events.tryEmit(EngineEvent.Stripped(removed, total))
    }

    override fun load(request: LoadRequest) {
        val player = player ?: run {
            events.tryEmit(EngineEvent.Failed("Playback needs VLC. Install it from videolan.org and reopen the app."))
            return
        }
        scope.launch {
            readyFired = false
            frame.value = null
            token?.let(proxy.sessions::close)
            val handler = proxy.handler()
            val session = proxy.sessions.open(ProxySession(request.referer, request.adPattern))
            token = session
            val url = handler.urlFor(session, request.url) ?: request.url
            val options = listOfNotNull(request.startAt?.let { ":start-time=$it" }).toTypedArray()
            state.value = EngineState(position = request.startAt ?: 0.0, playing = request.playWhenReady)
            if (request.playWhenReady) player.media().play(url, *options) else player.media().startPaused(url, *options)
        }
    }

    override fun play() {
        player?.controls()?.play()
    }

    override fun pause() {
        player?.controls()?.setPause(true)
        events.tryEmit(EngineEvent.Paused)
    }

    override fun seekTo(seconds: Double) {
        player?.controls()?.setTime((seconds * 1000).toLong())
        state.update { it.copy(position = seconds) }
    }

    override fun setRate(rate: Double) {
        this.rate = rate
        player?.controls()?.setRate(rate.toFloat())
    }

    override fun setVolume(volume: Double) {
        this.volume = volume
        player?.audio()?.setVolume((volume * 100).toInt())
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        player?.audio()?.setMute(muted)
    }

    /** VLC picks renditions itself and offers no list to pin one from. */
    override fun selectLevel(id: String?) {}

    override fun stop() {
        player?.controls()?.stop()
    }

    override fun release() {
        token?.let(proxy.sessions::close)
        if (current === this) current = null
        scope.cancel()
        player?.let {
            it.controls().stop()
            it.release()
        }
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        val current by frame.collectAsState()
        Canvas(modifier.background(Color.Black)) {
            val shown = current ?: return@Canvas
            val scale = min(size.width / shown.aspect, size.height)
            val width = scale * shown.aspect
            val left = (size.width - width) / 2
            val top = (size.height - scale) / 2
            drawIntoCanvas {
                it.nativeCanvas.drawImageRect(
                    shown.image,
                    Rect.makeWH(shown.width.toFloat(), shown.height.toFloat()),
                    Rect.makeXYWH(left, top, width, scale),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                    null,
                    true,
                )
            }
        }
    }

    companion object {
        /** Where the proxy reports stripped segments: the engine playing now. */
        internal var current: VlcEngine? = null
    }
}
