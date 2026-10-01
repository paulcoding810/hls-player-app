package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.subtitles.activeCues
import com.paulcoding.hlsplayer.subtitles.labelFor
import com.paulcoding.hlsplayer.subtitles.parseVtt
import com.paulcoding.hlsplayer.subtitles.readSubtitles
import com.paulcoding.hlsplayer.subtitles.shiftCues
import com.paulcoding.hlsplayer.subtitles.toVtt
import com.paulcoding.hlsplayer.util.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitlesTest {
    private val srt = "1\n00:00:01,000 --> 00:00:04,000\nHello\n\n2\n0:00:05,500 --> 0:00:07,250\nWorld"

    @Test fun convertsSrt() {
        val out = toVtt(srt)
        assertTrue(out.startsWith("WEBVTT\n\n"))
        assertTrue("00:00:01.000 --> 00:00:04.000" in out)
        assertTrue("0:00:05.500 --> 0:00:07.250" in out, "single-digit hours convert too")
    }

    @Test fun passesWebVttThroughAndDropsABom() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nAlready fine"
        assertEquals(vtt, toVtt(vtt))
        assertEquals(vtt, toVtt("﻿$vtt"))
    }

    @Test fun fixesSrtTimesUnderAWebVttHeader() {
        // One comma makes a parser reject every cue — the bug the extension hit.
        val out = toVtt("WEBVTT\n\n1\n00:05:38,310 --> 00:05:40,070\nComma")
        assertTrue(out.startsWith("WEBVTT\n\n") && !out.startsWith("WEBVTT\n\nWEBVTT"))
        assertTrue("00:05:38.310 --> 00:05:40.070" in out)
        assertEquals(338.31, parseVtt(out).single().start, 0.0001)
    }

    @Test fun yieldsNothingForEmptyInput() {
        listOf("", null, "  \n ").forEach { assertEquals("", toVtt(it)) }
    }

    @Test fun parsesCuesWithIdentifiersSettingsAndTags() {
        val cues = parseVtt(
            "WEBVTT\n\nNOTE skipped\n\nintro\n00:01.000 --> 00:02.500 line:90%\n<i>Hi</i> &amp; bye\nsecond line\n\n" +
                "00:00:03.000 --> 00:00:04.000\n<c.yellow>There</c>",
        )
        assertEquals(2, cues.size)
        assertEquals(1.0, cues[0].start)
        assertEquals("Hi & bye\nsecond line", cues[0].text)
        assertEquals("There", cues[1].text)
    }

    @Test fun shiftsFromTheOriginalTimes() {
        val cues = parseVtt("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nA")
        val early = shiftCues(cues, -5.0)
        assertEquals(0.0, early.single().start, "clamped at zero")
        // Back to zero offset from the originals, not from the clamped copy.
        assertEquals(1.0, shiftCues(cues, 0.0).single().start)
        assertEquals(1, activeCues(shiftCues(cues, 2.0), 3.5).size)
    }

    @Test fun readsSubtitleEntries() {
        val json = AppJson.parseToJsonElement(
            """["https://x.test/subs/English.vtt", {"url":"https://x.test/b.srt","label":" Mine ","lang":"vi"}, {"src":"javascript:x"}]""",
        )
        val subtitles = readSubtitles(json)
        assertEquals(2, subtitles.size)
        assertEquals("English", subtitles[0].label)
        assertEquals("Mine", subtitles[1].label)
        assertEquals("vi", subtitles[1].lang)
    }

    @Test fun labelsFromTheFileName() {
        assertEquals("English", labelFor("https://x.test/subs/English.vtt", 0))
        assertEquals("Viet sub", labelFor("https://x.test/Viet%20sub.srt", 0))
        assertEquals("Subtitles 2", labelFor("https://x.test/", 1))
    }
}
