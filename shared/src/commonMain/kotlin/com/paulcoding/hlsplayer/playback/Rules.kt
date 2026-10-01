package com.paulcoding.hlsplayer.playback

/** How often the position is written while watching, in seconds. */
const val PROGRESS_INTERVAL = 5.0

/** Seconds of grace before an auto-skipped outro moves on, long enough to stop it. */
const val OUTRO_COUNTDOWN = 5

/** How far short of the end `ended` may fire and still count as finished. */
const val ENDED_TOLERANCE = 5.0

/** Seconds before the end that the next episode's streams are asked for. */
const val PRELOAD_LEAD = 300.0
const val PRELOAD_TTL_MILLIS = 10 * 60 * 1000L

/** How many times a playlist may fail before playback is given up on. */
const val MAX_PLAYLIST_RETRIES = 3

enum class SkipWindow { Intro, Outro }

/**
 * Which window the position falls in. Both need a real end: a live stream has
 * none, and an intro longer than the episode would seek past it.
 */
fun skipAt(time: Double, duration: Double, skipLeading: Int, skipTrailing: Int): SkipWindow? {
    if (!duration.isFinite() || duration <= 0) return null
    if (skipLeading > 0 && skipLeading < duration && time < skipLeading) return SkipWindow.Intro
    if (skipTrailing > 0 && duration > skipTrailing && time >= duration - skipTrailing) return SkipWindow.Outro
    return null
}

/**
 * The longest duration a source has reported. A stream cut short ends its media
 * at whatever was buffered, which shrinks the reported duration and would drag
 * the outro window into the middle of the episode.
 */
class LongestDuration {
    var value = 0.0
        private set

    fun update(reported: Double): Double {
        if (!reported.isFinite() || reported <= 0) return reported
        if (reported > value) value = reported
        return value
    }

    fun reset() {
        value = 0.0
    }
}

/** A stream that fails mid-episode ends too, so `ended` is not proof it finished. */
fun endedEarly(expected: Double, position: Double): Boolean =
    expected > 0 && expected - position > ENDED_TOLERANCE
