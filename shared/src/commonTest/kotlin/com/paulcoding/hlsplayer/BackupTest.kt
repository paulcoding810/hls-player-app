package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.backup.BackupException
import com.paulcoding.hlsplayer.backup.applyBackup
import com.paulcoding.hlsplayer.backup.buildBackup
import com.paulcoding.hlsplayer.backup.readBackup
import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.model.Episode
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.SourceRef
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.model.Subtitle
import com.paulcoding.hlsplayer.util.AppJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    @Test fun importsWhatTheExtensionExports() = runTest {
        val fixture = Fixture()
        val summary = applyBackup(fixture.data, readBackup(EXTENSION_BACKUP))
        assertEquals(3, summary.added)
        assertEquals(2, summary.positions)

        val library = fixture.data.library.value
        val show = library.movies.first { it.title == "Show" }
        assertEquals("tt0108778:1:2", show.episodes.first { it.id == show.lastEpisodeId }.stream?.videoId)
        // The position is stored under the addon key and found again through the episode.
        val resumed = show.episodes.first { it.id == show.lastEpisodeId }
        assertEquals(612.5, fixture.data.getProgress(resumed.key)?.position)

        // The file writes `null` for "inherit the global skip"; it must not become 0.
        assertNull(show.skipLeading)
        assertNull(show.skipTrailing)

        val film = library.movies.first { it.title == "Film" }
        assertEquals(30, film.skipLeading)
        assertEquals(false, film.autoSkip)
        assertEquals("English", film.episodes[0].subtitles?.single()?.label)
        assertEquals("Episode 2", film.episodes[1].title, "an untitled episode is numbered")
        assertTrue(library.movies.first { it.title == "Done" }.watchedAt != null)

        val settings = fixture.data.settings.value
        assertEquals(0.4, settings.volume)
        assertEquals(1.25, settings.subtitleSize)
        assertEquals("added", settings.gallerySort)
        assertEquals("Addon", fixture.data.plugins.value.single().name)
    }

    @Test fun exportsWhatItImported() = runTest {
        // Browser to phone and back must not lose anything the extension wrote.
        val first = Fixture()
        applyBackup(first.data, readBackup(EXTENSION_BACKUP))
        val again = Fixture()
        applyBackup(again.data, readBackup(buildBackup(first.data)))
        assertEquals(first.data.library.value, again.data.library.value)
        assertEquals(first.data.progress.value, again.data.progress.value)
        assertEquals(first.data.settings.value, again.data.settings.value)
        assertEquals(first.data.plugins.value, again.data.plugins.value)
    }

    @Test fun writesTheExtensionsShape() = runTest {
        val fixture = Fixture()
        applyBackup(fixture.data, readBackup(EXTENSION_BACKUP))
        val exported = AppJson.parseToJsonElement(buildBackup(fixture.data)).jsonObject
        assertEquals("hls-player-backup", exported["format"]?.jsonPrimitive?.content)
        assertEquals(1, exported["version"]?.jsonPrimitive?.content?.toInt())
        listOf("library", "settings", "progress").forEach { assertTrue(exported[it] is kotlinx.serialization.json.JsonObject, it) }
        assertTrue(exported["plugins"]?.jsonArray?.size == 1)
        // Absent stays absent, as the extension writes it.
        assertTrue("null" !in buildBackup(fixture.data).replace("\"title\": \"null\"", ""))
    }

    /** Every field of a movie, set away from its default, so a forgotten one fails here. */
    @Test fun carriesEveryMovieField() = runTest {
        val full = Movie(
            id = "m1", title = "Full", poster = "https://img.test/p.jpg", referer = "https://site.test/",
            adPattern = "^/ads/", source = SourceRef("p1", "tt1", "series"), watchedAt = 7, skipLeading = 30,
            skipTrailing = 60, autoSkip = false, lastEpisodeId = "e2",
            episodes = listOf(
                Episode("e1", "One", "https://x.test/1.m3u8", subtitles = listOf(Subtitle("English", "https://x.test/en.vtt", "en"))),
                Episode("e2", "Two", stream = StreamRef("p1", "series", "tt1:1:2")),
            ),
            addedAt = 1, updatedAt = 2, lastPlayedAt = 3,
        )
        val fixture = Fixture()
        fixture.data.store.replace("library", AppJson.encodeToJsonElement(com.paulcoding.hlsplayer.model.Library.serializer(), com.paulcoding.hlsplayer.model.Library(listOf(full))))
        fixture.data.reload()
        val back = readBackup(buildBackup(fixture.data)).movies.single()
        assertEquals(full, back)
    }

    @Test fun dropsWhatCannotBeUsed() {
        val parsed = readBackup(
            """{"format":"hls-player-backup","version":1,
                "library":{"movies":[
                  {"title":"Fine","episodes":[{"src":"https://x.test/1.m3u8"}],"source":{"pluginId":"","itemId":"x"}},
                  {"title":"No episodes","episodes":[]},
                  {"title":"Junk src","episodes":[{"src":"javascript:alert(1)"}]},
                  "not an object"]},
                "plugins":[{"name":""},{"name":"Ok","kind":"stremio","url":"https://a.test/manifest.json","enabled":false},
                           {"name":"Old","search":{"url":"https://a/?q={query}"}},null],
                "progress":{"https://x.test/1.m3u8":{"position":90,"duration":600,"updatedAt":5},
                            "javascript:alert(1)":{"position":5,"duration":10},
                            "stremio:p1:tt0108778:1:1":{"position":300,"duration":2400,"updatedAt":7},
                            "stremio:":{"position":5,"duration":10}}}""",
        )
        assertEquals(1, parsed.movies.size)
        assertNull(parsed.movies[0].source, "a half source becomes none")
        assertEquals(listOf("Ok"), parsed.plugins.map { it.name }, "nameless and retired sources are dropped")
        assertEquals(false, parsed.plugins[0].enabled)
        assertEquals(setOf("https://x.test/1.m3u8", "stremio:p1:tt0108778:1:1"), parsed.progress.keys)
    }

    @Test fun refusesTheWrongFile() {
        mapOf(
            "nope" to "That file is not valid JSON.",
            """{"format":"other"}""" to "That file is not an HLS Player export.",
            """{"format":"hls-player-backup","version":2}""" to "That file was written by a newer version of the app.",
            """{"format":"hls-player-backup","version":1}""" to "That file holds nothing to import.",
        ).forEach { (text, message) -> assertEquals(message, assertFailsWith<BackupException> { readBackup(text) }.message) }
    }

    @Test fun mergesByIdAndKeepsTheNewerPosition() = runTest {
        val fixture = Fixture()
        fixture.data.saveProgress("https://x.test/1.m3u8", 300.0, 1800.0)
        val file = """{"format":"hls-player-backup","version":1,
            "library":{"movies":[{"id":"m1","title":"A","episodes":[{"src":"https://x.test/1.m3u8"}]}]},
            "progress":{"https://x.test/1.m3u8":{"position":10,"duration":1800,"updatedAt":0}}}"""
        applyBackup(fixture.data, readBackup(file))
        val again = applyBackup(fixture.data, readBackup(file.replace("\"A\"", "\"A2\"")))
        assertEquals(0, again.added)
        assertEquals(1, again.updated)
        assertEquals("A2", fixture.data.library.value.movies.single().title)
        assertEquals(300.0, fixture.data.progress.value["https://x.test/1.m3u8"]?.position, "the older import loses")
    }
}
