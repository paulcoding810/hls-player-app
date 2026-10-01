package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.playback.compilePattern
import com.paulcoding.hlsplayer.playback.stripAdSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val AD = compilePattern("^/v7/[0-9a-f]+/segment_\\d+\\.ts$")

/** A break fenced between two discontinuities, as SSAI actually delivers it. */
private val WITH_ADS = """
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:6
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:3.0,
kQU7g4Qk.ts
#EXTINF:3.0,
mR2xP0aB.ts
#EXT-X-DISCONTINUITY
#EXT-X-KEY:METHOD=NONE
#EXTINF:3.72,
/v7/d61a03b1/segment_0001.ts
#EXTINF:0.16,
/v7/d61a03b1/segment_0002.ts
#EXT-X-DISCONTINUITY
#EXTINF:3.0,
zW9hT4nC.ts
#EXT-X-ENDLIST
""".trimIndent()

class PlaylistTest {
    @Test fun cutsTheAdBlockAndKeepsTheContent() {
        val result = stripAdSegments(WITH_ADS, AD)
        assertEquals(2, result.removed)
        assertEquals(5, result.total)
        assertFalse("/v7/" in result.text)
        listOf("kQU7g4Qk.ts", "mR2xP0aB.ts", "zW9hT4nC.ts").forEach { assertTrue(it in result.text) }
    }

    @Test fun keepsExtXKeyInsideTheAdBlock() {
        // A key applies to everything after it; dropping it would break the next real segment.
        assertTrue("#EXT-X-KEY:METHOD=NONE" in stripAdSegments(WITH_ADS, AD).text)
    }

    @Test fun collapsesTheFencingDiscontinuitiesToTheOneAtTheCut() {
        val text = stripAdSegments(WITH_ADS, AD).text
        assertEquals(1, Regex("#EXT-X-DISCONTINUITY").findAll(text).count())
        assertTrue(text.indexOf("mR2xP0aB") < text.indexOf("#EXT-X-DISCONTINUITY"))
        assertTrue(text.indexOf("#EXT-X-DISCONTINUITY") < text.indexOf("zW9hT4nC"))
    }

    @Test fun preservesTheHeaderAndTheEndlist() {
        val text = stripAdSegments(WITH_ADS, AD).text
        assertTrue(text.startsWith("#EXTM3U"))
        assertTrue(text.trim().endsWith("#EXT-X-ENDLIST"))
        assertTrue("#EXT-X-TARGETDURATION:6" in text)
    }

    @Test fun dropsASegmentScopedTagWithItsSegment() {
        val tagged = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z
            #EXTINF:3.0,
            /v7/aa/segment_0001.ts
            #EXTINF:4.0,
            keep.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFalse("PROGRAM-DATE-TIME" in stripAdSegments(tagged, AD).text)
    }

    @Test fun leavesThePlaylistAloneWhenThereIsNothingToDo() {
        val cases = mapOf(
            "no pattern" to (WITH_ADS to null),
            "nothing matches" to (WITH_ADS to Regex("nothing-here")),
            "every segment matches" to (WITH_ADS to Regex("\\.ts$")),
            "live" to (WITH_ADS.replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "").replace("\n#EXT-X-ENDLIST", "") to AD),
            "main playlist" to ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nindex.m3u8" to AD),
        )
        cases.forEach { (name, case) ->
            val (text, pattern) = case
            val result = stripAdSegments(text, pattern)
            assertEquals(text, result.text, name)
            assertEquals(0, result.removed, name)
        }
    }

    @Test fun compilesPatterns() {
        assertTrue(compilePattern("^ad_\\d+$") != null)
        assertNull(compilePattern(""))
        assertNull(compilePattern("[unclosed"), "a half-typed pattern is null, not a crash")
    }
}
