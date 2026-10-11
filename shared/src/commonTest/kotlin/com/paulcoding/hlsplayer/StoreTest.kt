package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.store.MAX_POSITIONS
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    @Test fun persistsAcrossLaunches() = runTest {
        val fixture = Fixture()
        val movie = fixture.data.addMovie("Film", listOf(EpisodeDraft(src = "https://x.test/1.m3u8")))
        fixture.data.updateSettings { it.copy(volume = 0.3) }
        val reopened = fixture.reopen()
        assertEquals(movie, reopened.library.value.movies.single())
        assertEquals(0.3, reopened.settings.value.volume)
    }

    @Test fun remembersTheSourceScopeOutsideTheBackup() = runTest {
        val fixture = Fixture()
        assertEquals(emptyList(), fixture.data.sourceScope())
        fixture.data.setSourceScope(listOf("p1:movie:top", "p2:series:new"))
        assertEquals(listOf("p1:movie:top", "p2:series:new"), fixture.reopen().sourceScope())
        assertTrue("p1:movie:top" !in com.paulcoding.hlsplayer.backup.buildBackup(fixture.data), "a device convenience, not library data")
    }

    @Test fun storesOnlyAPositionWorthResuming() = runTest {
        val data = Fixture().data
        data.saveProgress("k", 5.0, 1000.0)
        assertNull(data.getProgress("k"), "too near the start")
        data.saveProgress("k", 500.0, 1000.0)
        assertEquals(500.0, data.getProgress("k")?.position)
        data.saveProgress("k", 980.0, 1000.0)
        assertNull(data.getProgress("k"), "the tail counts as finished and drops it")
        data.saveProgress("k", 500.0, Double.POSITIVE_INFINITY)
        assertNull(data.getProgress("k"), "nothing to resume on a live stream")
    }

    @Test fun capsThePositionsKeepingTheFreshest() = runTest {
        val fixture = Fixture()
        repeat(MAX_POSITIONS + 5) {
            fixture.time = it.toLong()
            fixture.data.saveProgress("k$it", 100.0, 1000.0)
        }
        assertEquals(MAX_POSITIONS, fixture.data.progress.value.size)
        assertNull(fixture.data.getProgress("k0"))
    }

    @Test fun markingWatchedDropsPositionsAndThePlace() = runTest {
        val fixture = Fixture()
        val stream = StreamRef("p1", "series", "tt1:1:1")
        val movie = fixture.data.addMovie("Show", listOf(EpisodeDraft(stream = stream)))
        val episode = movie.episodes.single()
        fixture.data.setLastPlayed(movie.id, episode.id)
        fixture.data.saveProgress(episode.key, 300.0, 1000.0)
        fixture.data.markWatched(movie.id)
        val after = fixture.data.library.value
        assertNull(fixture.data.getProgress(episode.key))
        assertNull(after.lastPlayed)
        assertNull(after.movies.single().lastEpisodeId)
        assertTrue(after.movies.single().watchedAt != null)
        fixture.data.setLastPlayed(movie.id, episode.id)
        assertNull(fixture.data.library.value.movies.single().watchedAt, "opening it again clears the mark")
    }

    @Test fun keepsEpisodeIdsThroughARefresh() = runTest {
        val fixture = Fixture()
        val stream = StreamRef("p1", "series", "tt1:1:1")
        val movie = fixture.data.addMovie("Show", listOf(EpisodeDraft("Pilot", stream = stream)))
        fixture.data.setEpisodes(movie.id, listOf(EpisodeDraft("Pilot", stream = stream), EpisodeDraft("Two", stream = stream.copy(videoId = "tt1:1:2"))))
        val episodes = fixture.data.library.value.movies.single().episodes
        assertEquals(movie.episodes.single().id, episodes[0].id)
        assertEquals(2, episodes.size)
    }

    @Test fun dropsARetiredSourceStoredWithoutAKind() = runTest {
        // The model defaults `kind`, so this is decided on the raw JSON.
        val fixture = Fixture()
        fixture.store.replace(
            "plugins",
            JsonObject(mapOf("items" to JsonArray(listOf(
                JsonObject(mapOf("id" to JsonPrimitive("old"), "name" to JsonPrimitive("My API"))),
                JsonObject(mapOf("id" to JsonPrimitive("p1"), "name" to JsonPrimitive("Addon"), "kind" to JsonPrimitive("stremio"), "url" to JsonPrimitive("https://a.test"))),
            )))),
        )
        val reopened = fixture.reopen()
        assertEquals(listOf("p1"), reopened.plugins.value.map(Plugin::id))
        reopened.dropRetiredPlugins()
        assertEquals(listOf("p1"), fixture.reopen().plugins.value.map(Plugin::id))
    }
}
