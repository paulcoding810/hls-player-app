@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.model.Subtitle
import com.paulcoding.hlsplayer.playback.EngineEvent
import com.paulcoding.hlsplayer.playback.EngineState
import com.paulcoding.hlsplayer.playback.LoadRequest
import com.paulcoding.hlsplayer.playback.PlayTarget
import com.paulcoding.hlsplayer.playback.PlayerController
import com.paulcoding.hlsplayer.playback.SkipWindow
import com.paulcoding.hlsplayer.playback.TextFetcher
import com.paulcoding.hlsplayer.playback.VideoEngine
import com.paulcoding.hlsplayer.stremio.StremioClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plays nothing; records what it was told and lets the test move time and fire events. */
class FakeEngine : VideoEngine {
    override val state = MutableStateFlow(EngineState())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    val loads = mutableListOf<LoadRequest>()
    val seeks = mutableListOf<Double>()
    var plays = 0

    override fun load(request: LoadRequest) {
        loads += request
        state.value = EngineState(position = request.startAt ?: 0.0, playing = request.playWhenReady)
    }
    override fun play() { plays += 1; state.update { it.copy(playing = true) } }
    override fun pause() { state.update { it.copy(playing = false) } }
    override fun seekTo(seconds: Double) { seeks += seconds; state.update { it.copy(position = seconds) } }
    override fun setRate(rate: Double) {}
    override fun setVolume(volume: Double) {}
    override fun setMuted(muted: Boolean) {}
    override fun selectLevel(id: String?) {}
    override fun stop() {}

    fun at(position: Double, duration: Double = 2400.0) = state.update { it.copy(position = position, duration = duration) }
}

private val ADDON = Plugin(id = "p1", name = "Addon", url = "https://addon.test/manifest.json", referer = "")

private fun streamsFor(episode: String) = """{"streams":[
  {"url":"https://cdn.test/$episode/a.m3u8","name":"A","behaviorHints":{"bingeGroup":"a"}},
  {"url":"https://cdn.test/$episode/b.m3u8","name":"B","behaviorHints":{"bingeGroup":"b","proxyHeaders":{"request":{"Referer":"https://b.test/"}}},
   "subtitles":[{"url":"https://subs.test/$episode.vi.srt","lang":"vie"},{"url":"https://subs.test/$episode.en.srt","lang":"eng"}]}
]}"""

private class Rig(scope: TestScope) {
    val fixture = Fixture()
    val engine = FakeEngine()
    val asked = mutableListOf<String>()
    val subtitleReferers = mutableListOf<String>()
    val client = StremioClient(
        HttpClient(MockEngine(MockEngineConfig().apply {
            // On the test's clock, so a request costs no real time and no timeout fires early.
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
                val url = request.url.toString()
                asked += url
                val episode = Regex("tt1%3A1%3A(\\d)").find(url)?.groupValues?.get(1)
                if (episode != null) respond(streamsFor("e$episode"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                else respond("{}", HttpStatusCode.NotFound)
            }
        })),
    )
    /**
     * Its own job on the test's clock: `backgroundScope` work is skipped by
     * `advanceUntilIdle`, and a child of the test would keep it from finishing.
     */
    val playerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
    val controller = PlayerController(
        fixture.data, client, engine, playerScope,
        fetchText = TextFetcher { url, referer ->
            subtitleReferers += referer
            "1\n00:00:01,000 --> 00:00:02,000\n${url.substringAfterLast('/')}"
        },
        now = { fixture.time },
    )

    suspend fun show(episodes: Int = 3): String {
        fixture.data.savePlugins(listOf(ADDON))
        return fixture.data.addMovie(
            "Show",
            (1..episodes).map { EpisodeDraft("E$it", stream = StreamRef("p1", "series", "tt1:1:$it")) },
        ).id
    }

    val streamAsks get() = asked.filter { "/stream/" in it }
}

class PlayerControllerTest {
    @Test fun resolvesAnAddonEpisodeAndPlaysTheFirstStream() = runTest {
        val rig = Rig(this)
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()

        val load = rig.engine.loads.single()
        assertEquals("https://cdn.test/e1/a.m3u8", load.url)
        assertEquals("", load.referer)
        assertTrue(load.playWhenReady, "autoplay with nothing to resume")
        assertEquals(2, rig.controller.state.value.streams.size)
        assertEquals(movieId, rig.fixture.data.library.value.lastPlayed?.movieId)
    }

    @Test fun holdsForTheResumePromptAndResumesThere() = runTest {
        val rig = Rig(this)
        val movieId = rig.show()
        rig.fixture.data.saveProgress("stremio:p1:tt1:1:1", 600.0, 2400.0)
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()

        assertEquals(600.0, rig.controller.state.value.resume?.position)
        assertEquals(false, rig.engine.loads.single().playWhenReady)
        rig.engine.at(0.0); runCurrent()
        rig.engine.at(30.0); runCurrent()
        assertEquals(600.0, rig.fixture.data.getProgress("stremio:p1:tt1:1:1")?.position, "nothing saved while held")

        rig.controller.resume()
        assertEquals(600.0, rig.engine.seeks.last())
        assertTrue(rig.engine.state.value.playing)
        assertNull(rig.controller.state.value.resume)
    }

    @Test fun savesProgressUnderTheEpisodeKey() = runTest {
        val rig = Rig(this)
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(100.0); advanceUntilIdle()
        assertEquals(100.0, rig.fixture.data.getProgress("stremio:p1:tt1:1:1")?.position)
    }

    @Test fun skipsTheIntroWhenReady() = runTest {
        val rig = Rig(this)
        rig.fixture.data.updateSettings { it.copy(skipLeading = 45) }
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(0.0)
        rig.engine.events.emit(EngineEvent.Ready); advanceUntilIdle()
        assertEquals(45.0, rig.engine.seeks.last())
    }

    @Test fun countsTheOutroDownAndAdvances() = runTest {
        val rig = Rig(this)
        rig.fixture.data.updateSettings { it.copy(skipTrailing = 90) }
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(2320.0); runCurrent()
        assertEquals(5, rig.controller.state.value.countdown)

        advanceTimeBy(3_100)
        assertEquals(2, rig.controller.state.value.countdown)
        rig.engine.pause()
        advanceTimeBy(5_000)
        assertEquals(2, rig.controller.state.value.countdown, "held while paused")

        rig.engine.play()
        advanceTimeBy(3_100); advanceUntilIdle()
        assertEquals("https://cdn.test/e2/a.m3u8", rig.engine.loads.last().url)
        assertEquals("E2", rig.controller.state.value.title)
    }

    @Test fun offersAButtonInsteadWhenAutoSkipIsOff() = runTest {
        val rig = Rig(this)
        rig.fixture.data.updateSettings { it.copy(skipTrailing = 90, autoSkip = false) }
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(2320.0); runCurrent()
        assertEquals(SkipWindow.Outro, rig.controller.state.value.skip)
        assertNull(rig.controller.state.value.countdown)
    }

    @Test fun offersTheIntroJumpOncePerEpisode() = runTest {
        val rig = Rig(this)
        rig.fixture.data.updateSettings { it.copy(introLength = 90) }
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(120.0); runCurrent()
        assertTrue(rig.controller.state.value.jumpable)

        assertTrue(rig.controller.skipOffered(), "S takes the jump")
        assertEquals(210.0, rig.engine.seeks.last())
        assertFalse(rig.controller.state.value.jumpable)

        rig.engine.at(130.0); runCurrent()
        assertFalse(rig.controller.state.value.jumpable, "not offered again after seeking back")
        assertFalse(rig.controller.skipOffered())
    }

    @Test fun tellsAnEarlyEndFromARealOne() = runTest {
        val rig = Rig(this)
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(900.0); runCurrent()
        rig.engine.events.emit(EngineEvent.Ended); advanceUntilIdle()
        assertEquals("The stream ended early.", rig.controller.state.value.error)
        assertEquals(1, rig.engine.loads.size, "a failed stream does not advance")

        rig.engine.at(2399.0); runCurrent()
        rig.engine.events.emit(EngineEvent.Ended); advanceUntilIdle()
        assertEquals(2, rig.engine.loads.size)
        assertNull(rig.fixture.data.getProgress("stremio:p1:tt1:1:1"))
    }

    @Test fun switchingStreamKeepsThePositionAndTheProviderFollows() = runTest {
        val rig = Rig(this)
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(321.0); runCurrent()

        rig.controller.chooseStream(rig.controller.state.value.streams[1]); advanceUntilIdle()
        val switched = rig.engine.loads.last()
        assertEquals("https://cdn.test/e1/b.m3u8", switched.url)
        assertEquals(321.0, switched.startAt, "resumes at the same second")
        assertEquals("https://b.test/", switched.referer, "the stream's own Referer")

        rig.controller.next(); advanceUntilIdle()
        assertEquals("https://cdn.test/e2/b.m3u8", rig.engine.loads.last().url, "the chosen provider follows")
    }

    @Test fun preloadsTheNextEpisodeOnce() = runTest {
        val rig = Rig(this)
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.engine.at(2200.0); advanceUntilIdle()
        rig.engine.at(2210.0); advanceUntilIdle()
        assertEquals(2, rig.streamAsks.size, "one preload for the next episode")
        assertTrue("tt1%3A1%3A2" in rig.streamAsks.last())

        rig.controller.next(); advanceUntilIdle()
        assertEquals(2, rig.streamAsks.size, "advancing uses the preloaded list")
        assertEquals("https://cdn.test/e2/a.m3u8", rig.engine.loads.last().url)
    }

    @Test fun turnsOnTheSubtitleInThePreferredLanguage() = runTest {
        val rig = Rig(this)
        rig.fixture.data.updateSettings { it.copy(subtitlesOn = true, subtitleLang = "en") }
        val movieId = rig.show()
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.controller.chooseStream(rig.controller.state.value.streams[1]); advanceUntilIdle()

        val state = rig.controller.state.value
        assertEquals(2, state.subtitles.size)
        assertEquals("eng", state.subtitles.first { it.id == state.primarySubtitle }.lang)
        assertEquals("e1.en.srt", state.subtitles.first { it.id == state.primarySubtitle }.cues.single().text)
        assertTrue(rig.subtitleReferers.all { it == "https://b.test/" }, "fetched with the stream's own Referer")
    }

    @Test fun prefersTheMoviesOwnSubtitleLanguage() = runTest {
        val rig = Rig(this)
        rig.fixture.data.updateSettings { it.copy(subtitlesOn = true, subtitleLang = "en") }
        val movieId = rig.show()
        rig.fixture.data.updateMovie(movieId) { it.copy(subtitleLang = "vi") }
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        rig.controller.chooseStream(rig.controller.state.value.streams[1]); advanceUntilIdle()

        val state = rig.controller.state.value
        assertEquals("vie", state.subtitles.first { it.id == state.primarySubtitle }.lang)
    }

    @Test fun keepsAMovieSubtitleAlongsideTheStreams() = runTest {
        val rig = Rig(this)
        val movie = rig.fixture.data.addMovie(
            "Film",
            listOf(EpisodeDraft("Film", src = "https://cdn.test/film.m3u8", subtitles = listOf(Subtitle("Mine", "https://subs.test/mine.vtt")))),
        )
        rig.controller.open(PlayTarget.Episode(movie.id, null))
        advanceUntilIdle()
        assertEquals("https://cdn.test/film.m3u8", rig.engine.loads.single().url)
        assertEquals(listOf("Mine"), rig.controller.state.value.subtitles.map { it.label })
        assertNotNull(rig.controller.state.value.current)
    }

    @Test fun reportsAnAddonThatIsGone() = runTest {
        val rig = Rig(this)
        val movieId = rig.show()
        rig.fixture.data.savePlugins(emptyList())
        rig.controller.open(PlayTarget.Episode(movieId, null))
        advanceUntilIdle()
        assertEquals("The source this episode came from is gone.", rig.controller.state.value.error)
        assertTrue(rig.engine.loads.isEmpty())
    }
}
