package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.playback.LongestDuration
import com.paulcoding.hlsplayer.playback.Preloader
import com.paulcoding.hlsplayer.playback.SkipWindow
import com.paulcoding.hlsplayer.playback.endedEarly
import com.paulcoding.hlsplayer.playback.preloadDue
import com.paulcoding.hlsplayer.playback.skipAt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTest {
    @Test fun handsTheStreamsOverOnceAndOnlyOnce() = runTest {
        val preloader = Preloader<String>(backgroundScope, ttlMillis = 1000, now = { 0 })
        preloader.start("a") { "streams" }
        assertEquals("streams", preloader.take("a")?.await())
        assertNull(preloader.take("a"))
    }

    @Test fun missesAnotherEpisodeAndEmptiesTheSlot() = runTest {
        val preloader = Preloader<String>(backgroundScope, 1000, now = { 0 })
        preloader.start("a") { "x" }
        assertNull(preloader.take("b"))
        assertNull(preloader.take("a"), "jumping elsewhere must not leave the old entry")
    }

    @Test fun refusesAnEntryPastItsTtl() = runTest {
        var time = 0L
        val preloader = Preloader<String>(backgroundScope, 1000, now = { time })
        preloader.start("a") { "x" }
        time = 1001
        assertNull(preloader.take("a"))
    }

    @Test fun dropsALoadThatFailed() = runTest {
        val preloader = Preloader<String>(backgroundScope, 1000, now = { 0 })
        preloader.start("a") { error("down") }
        // Background work does not count for idleness, so it is run explicitly.
        testScheduler.runCurrent()
        assertNull(preloader.take("a"))
    }

    @Test fun loadsAKeyOnce() = runTest {
        var calls = 0
        val preloader = Preloader<Int>(backgroundScope, 1000, now = { 0 })
        preloader.start("a") { ++calls }
        preloader.start("a") { ++calls }
        preloader.take("a")?.await()
        assertEquals(1, calls)
    }

    @Test fun preloadsNearTheEndOnly() {
        listOf(Double.POSITIVE_INFINITY, Double.NaN, 0.0).forEach { assertFalse(preloadDue(10.0, it, 0, 120.0)) }
        assertFalse(preloadDue(2000.0, 2400.0, 0, 120.0))
        assertTrue(preloadDue(2280.0, 2400.0, 0, 120.0))
        assertTrue(preloadDue(2100.0, 2400.0, 300, 120.0), "an outro longer than the lead starts it")
        assertFalse(preloadDue(100.0, 2400.0, 5000, 120.0), "an outro longer than the episode is ignored")
    }

    @Test fun findsTheSkipWindow() {
        assertEquals(SkipWindow.Intro, skipAt(10.0, 1000.0, 30, 60))
        assertNull(skipAt(500.0, 1000.0, 30, 60))
        assertEquals(SkipWindow.Outro, skipAt(940.0, 1000.0, 30, 60))
        assertNull(skipAt(10.0, Double.POSITIVE_INFINITY, 30, 60), "a live stream has no windows")
        assertNull(skipAt(10.0, 20.0, 30, 0), "an intro longer than the episode is ignored")
    }

    @Test fun keepsTheLongestDuration() {
        val longest = LongestDuration()
        longest.update(2400.0)
        assertEquals(2400.0, longest.update(900.0), "a cut-short stream must not shrink it")
    }

    @Test fun tellsAnEarlyEndFromARealOne() {
        assertTrue(endedEarly(2400.0, 900.0))
        assertFalse(endedEarly(2400.0, 2397.0))
        assertFalse(endedEarly(0.0, 10.0), "nothing to compare with")
    }
}
