package com.paulcoding.hlsplayer.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Holds one episode's streams resolved ahead of time. One slot, because only
 * the next episode is ever wanted, and [take] empties it whatever it finds so a
 * stale entry can never be picked up twice.
 */
class Preloader<T>(
    scope: CoroutineScope,
    private val ttlMillis: Long,
    private val now: () -> Long,
) {
    private class Held<T>(val key: String, val deferred: Deferred<T>, val at: Long)

    /** Supervised, so a failed load fails only itself and not the screen's scope. */
    private val loads = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private var held: Held<T>? = null

    fun start(key: String, load: suspend () -> T) {
        if (held?.key == key) return
        val deferred = loads.async { load() }
        val entry = Held(key, deferred, now())
        held = entry
        // A failed preload costs nothing: the load resolves afresh and reports.
        deferred.invokeOnCompletion { error -> if (error != null && held === entry) held = null }
    }

    fun take(key: String): Deferred<T>? {
        val entry = held
        held = null
        if (entry == null || entry.key != key || now() - entry.at > ttlMillis) return null
        return entry.deferred
    }
}

/** Inside the outro window, or within [lead] seconds of a real end. */
fun preloadDue(time: Double, duration: Double, skipTrailing: Int, lead: Double): Boolean {
    if (!duration.isFinite() || duration <= 0 || !time.isFinite()) return false
    val outro = if (skipTrailing > 0 && duration > skipTrailing) skipTrailing.toDouble() else 0.0
    return duration - time <= maxOf(outro, lead)
}
