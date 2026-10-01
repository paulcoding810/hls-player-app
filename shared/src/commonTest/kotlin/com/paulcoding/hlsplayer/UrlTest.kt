package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.util.DASH_MIME
import com.paulcoding.hlsplayer.util.HLS_MIME
import com.paulcoding.hlsplayer.util.fileNameOf
import com.paulcoding.hlsplayer.util.isManifestUrl
import com.paulcoding.hlsplayer.util.manifestMime
import com.paulcoding.hlsplayer.util.normalizeReferer
import com.paulcoding.hlsplayer.util.normalizeSource
import com.paulcoding.hlsplayer.util.parseEpisodeLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlTest {
    @Test fun acceptsOnlyHttp() {
        assertNull(normalizeSource("javascript:alert(1)"))
        assertNull(normalizeSource("ftp://x.test/a"))
        assertNull(normalizeSource("  "))
        assertNull(normalizeSource("https://"))
        assertEquals("https://x.test/a", normalizeSource(" https://x.test/a "))
    }

    @Test fun leavesAnAlreadyNormalizedUrlUnchanged() {
        // These are keys: progress is stored under them, by the extension and by the app.
        listOf(
            "https://cdn.test/a/index.m3u8",
            "https://cdn.test/a/index.m3u8?token=a%20b&x=1#t",
            "http://user:pw@cdn.test:8080/p",
        ).forEach { assertEquals(it, normalizeSource(it)) }
    }

    @Test fun normalizesWhatTheBrowserWould() {
        assertEquals("https://x.test/", normalizeSource("HTTPS://X.Test"))
        assertEquals("https://x.test/?q=1", normalizeSource("https://x.test?q=1"))
    }

    @Test fun acceptsABareDomainAsAReferer() {
        assertEquals("https://example.com/", normalizeReferer("example.com"))
        assertEquals("", normalizeReferer(""))
    }

    @Test fun picksTheManifestTypeFromThePath() {
        assertEquals(DASH_MIME, manifestMime("https://x.test/v/manifest.mpd?t=1"))
        assertEquals(HLS_MIME, manifestMime("https://x.test/v/master.m3u8"))
        assertEquals(HLS_MIME, manifestMime("https://x.test/play?id=9"), "no extension is taken as HLS")
        assertTrue(isManifestUrl("https://x.test/a.M3U8?x=.mp4"))
        assertFalse(isManifestUrl("https://x.test/a.mp4?x=.m3u8"))
    }

    @Test fun readsEpisodeLines() {
        val parsed = parseEpisodeLines(
            "Pilot | https://x.test/1.m3u8 https://x.test/1.en.vtt\nhttps://x.test/2.m3u8\nnot a url\nhttps://x.test/2.m3u8",
        )
        assertEquals(2, parsed.episodes.size, "a duplicate is dropped")
        assertEquals(1, parsed.skipped)
        assertEquals("Pilot", parsed.episodes[0].title)
        assertEquals(1, parsed.episodes[0].subtitles.size)
    }

    @Test fun namesAFile() {
        assertEquals("index.m3u8", fileNameOf("https://x.test/a/index.m3u8?t=1"))
        assertEquals("x.test", fileNameOf("https://x.test/"))
    }
}

class TimeTest {
    @Test fun formatsLikeTheExtension() {
        mapOf(0.0 to "0:00", 65.0 to "1:05", 3723.0 to "1:02:03", Double.POSITIVE_INFINITY to "--:--", Double.NaN to "--:--", -1.0 to "--:--")
            .forEach { (seconds, expected) -> assertEquals(expected, com.paulcoding.hlsplayer.util.formatTime(seconds)) }
    }
}
