package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.library.JsonInputException
import com.paulcoding.hlsplayer.library.episodeKey
import com.paulcoding.hlsplayer.library.isEpisodeKey
import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.library.mergeEpisodes
import com.paulcoding.hlsplayer.library.movieToJson
import com.paulcoding.hlsplayer.library.parseMovieJson
import com.paulcoding.hlsplayer.library.resolveConfig
import com.paulcoding.hlsplayer.library.resumeEpisodeId
import com.paulcoding.hlsplayer.library.sortMovies
import com.paulcoding.hlsplayer.library.toJsonText
import com.paulcoding.hlsplayer.model.Episode
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Position
import com.paulcoding.hlsplayer.model.SourceRef
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.settings.DEFAULT_SETTINGS
import com.paulcoding.hlsplayer.settings.SortOrder
import com.paulcoding.hlsplayer.util.AppJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTest {
    private val stream = StreamRef("p1", "series", "tt0108778:1:1")

    @Test fun keysAnEpisodeByUrlElseByTheVideoItNames() {
        assertEquals("https://x.test/a.m3u8", episodeKey("https://x.test/a.m3u8", stream))
        assertEquals("stremio:p1:tt0108778:1:1", episodeKey(null, stream))
        assertEquals("", episodeKey(null, null))
    }

    @Test fun acceptsEveryKeyEpisodeKeyProduces() {
        // Progress is stored under these, so the import filter must never reject one.
        listOf(
            episodeKey("https://x.test/a.m3u8", null),
            episodeKey(null, stream),
            episodeKey(null, StreamRef("p1", "movie", "tt9")),
        ).forEach { assertTrue(isEpisodeKey(it), it) }
        listOf("", "stremio:", "stremio:p1", "javascript:alert(1)", "ftp://x.test/a", null)
            .forEach { assertFalse(isEpisodeKey(it), it.toString()) }
    }

    @Test fun roundTripsAnAddonMovieThroughThePasteReader() {
        // What Copy as JSON hands out for a search result: no URL, a named video.
        val text = movieToJson(title = "Show", episodes = listOf(EpisodeDraft("Pilot", stream = stream)))
        assertEquals(listOf(EpisodeDraft("Pilot", stream = stream)), parseMovieJson(text).episodes)
    }

    @Test fun leavesLocalOnlyFieldsOutOfAShareableBlob() {
        val movie = Movie(
            id = "abc", title = "X", addedAt = 1, watchedAt = 2, source = SourceRef("p", "i"),
            episodes = listOf(Episode("e", "E", "https://x.test/1.m3u8")),
        )
        val shared = AppJson.parseToJsonElement(movie.toJsonText()).jsonObject
        listOf("id", "addedAt", "watchedAt", "source").forEach { assertFalse(it in shared, it) }
    }

    @Test fun refusesWhatTheExtensionRefuses() {
        val cases = mapOf(
            "not json" to "That is not valid JSON.",
            "[]" to "Expected a single movie object, like the example below.",
            """{"episodes":["https://x.test/a"]}""" to "Give the movie a \"title\".",
            """{"title":"X","episodes":[]}""" to "Add an \"episodes\" array with at least one URL.",
            """{"title":"X","episodes":[{"stream":{"pluginId":"p1"}}]}""" to "Episode 1 has no valid http(s) URL or addon stream.",
            """{"title":"X","episodes":["https://x.test/a"],"skipLeading":-1}""" to "\"skipLeading\" must be 0 or more.",
            """{"title":"X","episodes":["https://x.test/a"],"adPattern":"[x"}""" to "\"adPattern\" is not a valid regular expression.",
            """{"title":"X","episodes":["https://x.test/a"],"autoSkip":"yes"}""" to "\"autoSkip\" must be true, false or null.",
        )
        cases.forEach { (text, message) ->
            assertEquals(message, assertFailsWith<JsonInputException>(text) { parseMovieJson(text) }.message)
        }
    }

    @Test fun keepsIdsAcrossAnEditByKey() {
        val current = listOf(Episode("keep", "Old", stream = stream, subtitles = null))
        val merged = mergeEpisodes(current, listOf(EpisodeDraft("", stream = stream), EpisodeDraft("New", "https://x.test/b")), id = { "fresh" })
        assertEquals("keep", merged[0].id)
        assertEquals("Old", merged[0].title, "a blank title keeps the stored one")
        assertEquals("fresh", merged[1].id)
    }

    @Test fun sortsByEachOrder() {
        val a = Movie(id = "a", title = "b-title", addedAt = 1, lastPlayedAt = 50, episodes = listOf(Episode("1", "1", "https://x.test/a")))
        val b = Movie(id = "b", title = "A-title", addedAt = 2, updatedAt = 10)
        val c = Movie(id = "c", title = "c-title", addedAt = 3)
        assertEquals(listOf("a", "c", "b"), sortMovies(listOf(a, b, c), SortOrder.Watched).map { it.id })
        assertEquals(listOf("c", "b", "a"), sortMovies(listOf(a, b, c), SortOrder.Added).map { it.id })
        assertEquals(listOf("b", "c", "a"), sortMovies(listOf(a, b, c), SortOrder.Updated).map { it.id })
        assertEquals(listOf("b", "a", "c"), sortMovies(listOf(a, b, c), SortOrder.Title).map { it.id })
        // A movie never stamped falls back to its freshest stored position.
        val positions = mapOf("https://x.test/a" to Position(100.0, 1000.0, 99))
        assertEquals("a", sortMovies(listOf(a.copy(lastPlayedAt = null), c), SortOrder.Watched, positions).first().id)
    }

    @Test fun resumesWhereItWasLeft() {
        val movie = Movie(id = "m", episodes = listOf(Episode("1", "1", "https://x.test/1"), Episode("2", "2", "https://x.test/2")), lastEpisodeId = "2")
        assertEquals("2", resumeEpisodeId(movie))
        assertEquals("1", resumeEpisodeId(movie.copy(lastEpisodeId = "gone")))
    }

    @Test fun inheritsBlankConfigFromTheSettings() {
        val settings = DEFAULT_SETTINGS.copy(referer = "https://global.test/", skipLeading = 30)
        val config = resolveConfig(Movie(id = "m", referer = "", skipLeading = 0), settings)
        assertEquals("https://global.test/", config.referer)
        assertEquals(0, config.skipLeading, "zero is a choice, not a blank")
    }

    @Test fun episodeKeyIsWhatTheModelUses() {
        assertEquals(episodeKey(null, stream), Episode("e", "E", stream = stream).key)
    }
}
