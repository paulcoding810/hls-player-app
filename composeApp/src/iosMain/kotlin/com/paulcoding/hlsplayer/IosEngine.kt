package com.paulcoding.hlsplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.paulcoding.hlsplayer.playback.EngineEvent
import com.paulcoding.hlsplayer.playback.EngineState
import com.paulcoding.hlsplayer.playback.LoadRequest
import com.paulcoding.hlsplayer.proxy.ProxyServer
import com.paulcoding.hlsplayer.proxy.ProxySession
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.AVFoundation.setRate
import platform.AVFoundation.setVolume
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol

/** How often the position is read: AVPlayer reports it on request. */
private const val TICK_MILLIS = 250L

/**
 * AVPlayer as the engine. It has no hook for headers, playlists or segment
 * bytes, so everything plays through the local proxy: the player is handed a
 * loopback URL and the proxy sends the `Referer`, cuts the ads and unwraps
 * disguised segments on its way.
 */
@OptIn(ExperimentalForeignApi::class)
class IosEngine(private val proxy: ProxyServer) : PlatformEngine {
    private val player = AVPlayer()
    private val scope = MainScope()
    private var token: String? = null
    private var readyFired = false
    private var failedFired = false
    private var endObserver: NSObjectProtocol? = null
    private var rate = 1.0
    private var volume = 1.0
    private var muted = false

    override val state = MutableStateFlow(EngineState())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)

    init {
        // Plays with the ring/silent switch off, as video apps do.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
        AVAudioSession.sharedInstance().setActive(true, null)
        currentEngine = this
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    private fun seconds(time: CValue<CMTime>): Double = CMTimeGetSeconds(time)

    private fun tick() {
        val item = player.currentItem ?: return
        if (item.status == AVPlayerItemStatusReadyToPlay && !readyFired) {
            readyFired = true
            events.tryEmit(EngineEvent.Ready)
        }
        if (item.status == AVPlayerItemStatusFailed && !failedFired) {
            failedFired = true
            events.tryEmit(EngineEvent.Failed(item.error?.localizedDescription ?: "Playback failed."))
        }
        val reported = seconds(item.duration)
        // An indefinite duration once the item is ready is a live stream.
        val duration = if (reported.isNaN() && item.status == AVPlayerItemStatusReadyToPlay) Double.POSITIVE_INFINITY else reported
        state.update {
            it.copy(
                position = seconds(player.currentTime()).takeUnless(Double::isNaN) ?: 0.0,
                duration = duration,
                playing = player.rate > 0f,
                buffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            )
        }
    }

    internal fun stripped(removed: Int, total: Int) {
        events.tryEmit(EngineEvent.Stripped(removed, total))
    }

    override fun load(request: LoadRequest) {
        scope.launch {
            readyFired = false
            failedFired = false
            token?.let(proxy.sessions::close)
            val handler = proxy.handler()
            val session = proxy.sessions.open(ProxySession(request.referer, request.adPattern))
            token = session
            val url = handler.urlFor(session, request.url) ?: request.url
            val item = AVPlayerItem(uRL = NSURL(string = url))
            endObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
            endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
            ) { _ -> events.tryEmit(EngineEvent.Ended) }
            player.replaceCurrentItemWithPlayerItem(item)
            request.startAt?.let { seekTo(it) }
            state.value = EngineState(position = request.startAt ?: 0.0, playing = request.playWhenReady)
            if (request.playWhenReady) play()
        }
    }

    override fun play() {
        player.play()
        player.setRate(rate.toFloat())
    }

    override fun pause() {
        player.pause()
        events.tryEmit(EngineEvent.Paused)
    }

    override fun seekTo(seconds: Double) {
        player.seekToTime(CMTimeMakeWithSeconds(seconds, 600))
        state.update { it.copy(position = seconds) }
    }

    override fun setRate(rate: Double) {
        this.rate = rate
        if (player.rate > 0f) player.setRate(rate.toFloat())
    }

    override fun setVolume(volume: Double) {
        this.volume = volume
        player.setVolume(volume.toFloat())
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        player.setMuted(muted)
    }

    /** AVPlayer picks renditions itself and offers no list to pin one from. */
    override fun selectLevel(id: String?) {}

    override fun stop() {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
    }

    override fun release() {
        stop()
        token?.let(proxy.sessions::close)
        endObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        if (currentEngine === this) currentEngine = null
        scope.cancel()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        UIKitView(
            factory = { PlayerView(player) },
            modifier = modifier,
        )
    }

    companion object {
        /** Where the proxy reports stripped segments: the engine playing now. */
        internal var currentEngine: IosEngine? = null
    }
}

/** A view whose layer follows its size, holding the player's video. */
@OptIn(ExperimentalForeignApi::class)
private class PlayerView(player: AVPlayer) : UIView(frame = CGRectZero.readValue()) {
    private val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player).apply {
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}
