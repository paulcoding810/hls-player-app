package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.stremio.CatalogRef
import com.paulcoding.hlsplayer.stremio.FoundStream
import com.paulcoding.hlsplayer.stremio.StremioClient
import com.paulcoding.hlsplayer.stremio.StremioException
import com.paulcoding.hlsplayer.stremio.describeManifest
import com.paulcoding.hlsplayer.stremio.detailsOf
import com.paulcoding.hlsplayer.stremio.pickStream
import com.paulcoding.hlsplayer.stremio.searchAll
import com.paulcoding.hlsplayer.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ADDON = Plugin(id = "p1", name = "Addon", url = "https://addon.test/manifest.json")

private val MANIFEST = """
{"id":"test.addon","name":"Addon","version":"1.2.0","resources":["catalog","meta","stream"],"types":["series","movie"],
 "catalogs":[
   {"type":"series","id":"top","name":"Series","extra":[{"name":"search"},{"name":"skip"}]},
   {"type":"movie","id":"top","name":"Movies","extra":[{"name":"search"}]},
   {"type":"movie","id":"new","name":"New"}
 ]}
"""

/** A client whose every request is answered from [routes] by the first path fragment it contains. */
private class Server(private val routes: Map<String, String>) {
    val asked = mutableListOf<String>()
    val client = StremioClient(
        HttpClient(MockEngine { request ->
            val url = request.url.toString()
            asked += url
            val match = routes.keys.firstOrNull { it in url }
            if (match == null) respond("{}", HttpStatusCode.NotFound)
            else respond(routes.getValue(match), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }),
    )
}

/**
 * The mock engine answers on a real thread while `runTest` skips virtual time,
 * so the client's request timeout would fire at once; these run in real time.
 */
private fun realTime(block: suspend () -> Unit): TestResult = runTest { withContext(Dispatchers.Default) { block() } }

class StremioTest {
    @Test fun listsEveryPlayableStreamInTheAddonsOrder() = realTime {
        val server = Server(
            mapOf(
                "/stream/" to """{"streams":[
                    {"infoHash":"abc"},
                    {"url":"https://cdn.test/a.m3u8","name":"Provider A","title":"1080p\n2 GB",
                     "behaviorHints":{"bingeGroup":"a-1080","proxyHeaders":{"request":{"Referer":"https://site.test/"}}},
                     "subtitles":[{"id":"1","url":"https://subs.test/en.srt","lang":"eng"}]},
                    {"ytId":"xyz"},
                    {"url":"https://cdn.test/b.m3u8","name":"Provider B","description":"720p"}
                ]}""",
            ),
        )
        val streams = server.client.listStreams(ADDON, StreamRef("p1", "series", "tt1:1:1"))
        assertEquals(listOf("https://cdn.test/a.m3u8", "https://cdn.test/b.m3u8"), streams.map { it.url })
        assertEquals("https://site.test/", streams[0].referer)
        assertEquals("1080p\n2 GB", streams[0].description, "title is the deprecated spelling")
        assertEquals("a-1080", streams[0].bingeGroup)
        assertEquals("eng", streams[0].subtitles.single().label)
        assertTrue(server.asked.single().endsWith("/stream/series/tt1%3A1%3A1.json"))
    }

    @Test fun saysWhyNothingCanPlay() = realTime {
        val torrents = Server(mapOf("/stream/" to """{"streams":[{"infoHash":"abc"}]}"""))
        val error = assertFailsWith<StremioException> { torrents.client.listStreams(ADDON, StreamRef("p1", "movie", "tt9")) }
        assertEquals("Addon offered no stream this player can open — torrents and external links need another app.", error.message)

        val empty = Server(mapOf("/stream/" to """{"streams":[]}"""))
        val none = assertFailsWith<StremioException> { empty.client.listStreams(ADDON, StreamRef("p1", "movie", "tt9")) }
        assertEquals("Addon found no stream for this episode.", none.message)
    }

    @Test fun picksByBingeGroupElseFirst() {
        val a = FoundStream("https://a", "", "A", "", "", emptyList())
        val b = FoundStream("https://b", "", "B", "", "b-720", emptyList())
        assertEquals(b, pickStream(listOf(a, b), "b-720"))
        assertEquals(a, pickStream(listOf(a, b), "gone"))
        assertEquals(a, pickStream(listOf(a, b), ""), "an empty group must not match an unmarked stream")
        assertNull(pickStream(emptyList(), "x"))
    }

    @Test fun searchesEachSearchableCatalog() = realTime {
        val server = Server(
            mapOf(
                "manifest.json" to MANIFEST,
                "/catalog/series/top/search=" to """{"metas":[{"id":"tt1","name":"Show","type":"series"},{"id":"tt1","name":"Show"}]}""",
                "/catalog/movie/top/search=" to """{"metas":[{"id":"tt9","name":"Film"},{"id":"x"}]}""",
            ),
        )
        val groups = server.client.search(ADDON, "the show")
        assertEquals(listOf("Series", "Movies"), groups.map { it.catalog?.name })
        assertEquals(1, groups[0].results.size, "a duplicate id is dropped")
        assertEquals(listOf("tt9"), groups[1].results.map { it.id }, "a result without a name is dropped")
        assertTrue(server.asked.any { "search=the%20show.json" in it })
    }

    @Test fun reportsAFailingAddonInItsOwnGroup() = realTime {
        val server = Server(mapOf("addon.test/manifest.json" to MANIFEST))
        val groups = server.client.searchAll(listOf(ADDON, ADDON.copy(id = "p2", url = "https://down.test")), "x")
        assertTrue(groups.any { it.plugin.id == "p2" && it.catalog == null && it.error.isNotEmpty() })
        assertTrue(groups.filter { it.plugin.id == "p1" }.all { it.error == "Addon answered 404." })
    }

    @Test fun browsesAndPagesACatalog() = realTime {
        val server = Server(mapOf("/catalog/series/top" to """{"metas":[{"id":"tt1","name":"Show"}]}"""))
        val catalog = CatalogRef("series", "top", "Series", pageable = true)
        assertEquals("series", server.client.browseCatalog(ADDON, catalog).single().type)
        server.client.browseCatalog(ADDON, catalog, skip = 100)
        assertTrue(server.asked[0].endsWith("/catalog/series/top.json"), "no skip=0 at the start")
        assertTrue(server.asked[1].endsWith("/catalog/series/top/skip=100.json"))
    }

    @Test fun listsCatalogsAndWhichPage() = realTime {
        val catalogs = Server(mapOf("manifest.json" to MANIFEST)).client.listCatalogs(ADDON)
        assertEquals(listOf(true, false, false), catalogs.map { it.pageable })
        assertEquals(listOf(true, true, false), catalogs.map { it.searchable })
    }

    @Test fun searchesOneCatalogWithoutTheManifest() = realTime {
        val server = Server(mapOf("/catalog/movie/top/search=" to """{"metas":[{"id":"tt9","name":"Film"}]}"""))
        val found = server.client.searchCatalog(ADDON, CatalogRef("movie", "top", "Movies", searchable = true), "a b")
        assertEquals(listOf("https://addon.test/catalog/movie/top/search=a%20b.json"), server.asked)
        assertEquals("Movies", found.catalog?.name)
        assertEquals(listOf("tt9"), found.results.map { it.id })

        val failed = Server(emptyMap()).client.searchCatalog(ADDON, CatalogRef("movie", "top", "Movies"), "x")
        assertEquals("Addon answered 404.", failed.error, "a failure is carried, not thrown")
    }

    @Test fun readsAStremioInstallLinkOverHttps() = realTime {
        val server = Server(mapOf("manifest.json" to MANIFEST))
        server.client.listCatalogs(ADDON.copy(url = "stremio://addon.test/manifest.json"))
        assertEquals("https://addon.test/manifest.json", server.asked.single())
    }

    @Test fun turnsASeriesIntoEpisodesNamingTheirVideo() = realTime {
        val server = Server(
            mapOf("/meta/" to """{"meta":{"id":"tt1","name":"Show","videos":[{"id":"tt1:1:1","title":"Pilot","season":1,"episode":1},{"id":"tt1:1:2","season":1,"episode":2}]}}"""),
        )
        val episodes = server.client.fetchEpisodes(ADDON, "tt1", "series")
        assertEquals(listOf("Pilot", "S1E2"), episodes.map { it.title })
        assertEquals(StreamRef("p1", "series", "tt1:1:1"), episodes[0].stream)
        assertNull(episodes[0].src, "no URL until it is played")
    }

    @Test fun turnsAMovieIntoOneEpisode() = realTime {
        val episodes = Server(mapOf("/meta/" to """{"meta":{"id":"tt9","name":"Film"}}""")).client.fetchEpisodes(ADDON, "tt9", "movie")
        assertEquals("tt9", episodes.single().stream?.videoId)
    }

    @Test fun saysWhenAnAddonDoesNotAnswerWell() = realTime {
        val error = assertFailsWith<StremioException> { Server(mapOf("/meta/" to """{}""")).client.fetchEpisodes(ADDON, "x", "movie") }
        assertEquals("Addon returned no details for this title.", error.message)
        val notJson = assertFailsWith<StremioException> { Server(mapOf("/meta/" to "<html>")).client.fetchEpisodes(ADDON, "x", "movie") }
        assertEquals("Addon did not return JSON.", notJson.message)
    }

    @Test fun describesAManifest() {
        val summary = describeManifest(AppJson.parseToJsonElement(MANIFEST))
        assertEquals("Addon", summary.name)
        assertEquals(3, summary.catalogs)
        assertEquals(2, summary.searchable)
        assertEquals(emptyList(), summary.missing)
        val catalogOnly = describeManifest(AppJson.parseToJsonElement("""{"name":"Cinemeta","resources":["catalog",{"name":"meta"}]}"""))
        assertEquals(listOf("stream"), catalogOnly.missing, "object-form resources count too")
    }

    @Test fun readsDetails() {
        val details = detailsOf(
            AppJson.parseToJsonElement(
                """{"name":" Show ","releaseInfo":"2008–2013","imdbRating":"9.5","genres":["Drama"," ","Crime"],
                    "videos":[{"season":1},{"season":1},{"season":2},{"season":0}]}""",
            ),
        )
        assertEquals("Show", details.title)
        assertEquals(9.5, details.rating)
        assertEquals(listOf("Drama", "Crime"), details.genres)
        assertEquals(2, details.seasons, "specials are not a season")
        val links = detailsOf(AppJson.parseToJsonElement("""{"year":1999,"links":[{"name":"Comedy","category":"Genres"}]}"""))
        assertEquals(listOf("Comedy"), links.genres)
        assertEquals("1999", links.released)
    }
}
