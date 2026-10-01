package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.settings.DEFAULT_SETTINGS
import com.paulcoding.hlsplayer.settings.SUBTITLE_POSITIONS
import com.paulcoding.hlsplayer.settings.SUBTITLE_SIZES
import com.paulcoding.hlsplayer.settings.readSettings
import com.paulcoding.hlsplayer.util.AppJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTest {
    private fun read(json: String) = readSettings(AppJson.parseToJsonElement(json).jsonObject)

    @Test fun clampsAStoredVolume() {
        assertEquals(0.4, read("""{"volume":0.4}""").volume)
        assertEquals(0.0, read("""{"volume":0}""").volume, "silence is a real choice")
        assertEquals(1.0, read("""{"volume":5}""").volume)
        assertEquals(0.0, read("""{"volume":-1}""").volume)
        assertEquals(1.0, read("""{"volume":"nonsense"}""").volume)
        assertEquals(1.0, readSettings(null).volume)
    }

    @Test fun keepsTheSubtitleSettingsInRange() {
        assertEquals(2.0, read("""{"subtitleSize":99}""").subtitleSize)
        assertEquals(0.75, read("""{"subtitleSize":0}""").subtitleSize)
        assertEquals(1.0, read("""{"subtitleSize":"huge"}""").subtitleSize)
        assertEquals("en-gb", read("""{"subtitleLang":"  EN-gb "}""").subtitleLang)
        assertEquals(true, read("""{"subtitlesOn":"yes"}""").subtitlesOn)
        assertEquals(true, read("""{}""").subtitleBackground, "the box stays unless turned off")
        assertEquals(false, read("""{"subtitleBackground":false}""").subtitleBackground)
        assertEquals(12.0, read("""{"subtitlePosition":99}""").subtitlePosition)
        assertEquals(4.5, read("""{"subtitlePosition":"up"}""").subtitlePosition)
    }

    @Test fun coercesTheRest() {
        val clean = read("""{"referer":"  https://x/  ","playbackRate":0,"skipLeading":-5,"autoSkip":"yes","gallerySort":"bogus"}""")
        assertEquals("https://x/", clean.referer)
        assertEquals(1.0, clean.playbackRate)
        assertEquals(0, clean.skipLeading)
        assertEquals(true, clean.autoSkip)
        assertEquals("watched", clean.gallerySort)
    }

    @Test fun offersTheDefaultsAmongItsChoices() {
        assertTrue(SUBTITLE_SIZES.any { it.value == DEFAULT_SETTINGS.subtitleSize })
        assertTrue(SUBTITLE_POSITIONS.any { it.value == DEFAULT_SETTINGS.subtitlePosition })
        SUBTITLE_SIZES.forEach { assertEquals(it.value, read("""{"subtitleSize":${it.value}}""").subtitleSize) }
    }
}
