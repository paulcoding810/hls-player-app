package com.paulcoding.hlsplayer.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the platform player is asked to open. */
data class LoadRequest(
    val url: String,
    /** Sent on every playlist and segment request; blank sends none. */
    val referer: String,
    /** Media playlists have the segments matching this cut before parsing. */
    val adPattern: Regex?,
    val startAt: Double? = null,
    val playWhenReady: Boolean = false,
)

/** One video rendition the viewer may pin instead of adaptive selection. */
data class Level(val id: String, val label: String, val height: Int)

data class EngineState(
    val position: Double = 0.0,
    val duration: Double = Double.NaN,
    val buffered: Double = 0.0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val levels: List<Level> = emptyList(),
    /** Null is adaptive. */
    val level: String? = null,
)

sealed interface EngineEvent {
    /** Metadata is in: duration and renditions are known. */
    data object Ready : EngineEvent
    data object Ended : EngineEvent
    data object Paused : EngineEvent
    data class Failed(val message: String) : EngineEvent
    /** Segments of a media playlist were cut by the ad pattern. */
    data class Stripped(val removed: Int, val total: Int) : EngineEvent
}

/**
 * The platform player — Media3 on Android, AVPlayer on iOS. It only plays;
 * every rule about what to play and when lives in `PlayerController`.
 */
interface VideoEngine {
    val state: StateFlow<EngineState>
    val events: Flow<EngineEvent>

    fun load(request: LoadRequest)
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    fun setRate(rate: Double)
    fun setVolume(volume: Double)
    fun setMuted(muted: Boolean)
    fun selectLevel(id: String?)
    fun stop()
}
