package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.playback.PNG_SIGNATURE
import com.paulcoding.hlsplayer.playback.compilePattern
import com.paulcoding.hlsplayer.proxy.DecoyStripper
import com.paulcoding.hlsplayer.proxy.ProxyHandler
import com.paulcoding.hlsplayer.proxy.ProxySession
import com.paulcoding.hlsplayer.proxy.ProxySessions
import com.paulcoding.hlsplayer.proxy.StripListener
import com.paulcoding.hlsplayer.proxy.proxyUrl
import com.paulcoding.hlsplayer.proxy.resolveUrl
import com.paulcoding.hlsplayer.proxy.rewritePlaylist
import com.paulcoding.hlsplayer.proxy.upstreamOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "http://127.0.0.1:8080"

class ResolveUrlTest {
    /** RFC 3986 §5.4.1, the normal examples, against its own base. */
    @Test fun resolvesTheRfcExamples() {
        val base = "http://a/b/c/d;p?q"
        mapOf(
            "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g", "//g" to "http://g/", "?y" to "http://a/b/c/d;p?y",
            "g?y" to "http://a/b/c/g?y", "#s" to "http://a/b/c/d;p?q", ";x" to "http://a/b/c/;x",
            "" to "http://a/b/c/d;p?q", "." to "http://a/b/c/", "./" to "http://a/b/c/",
            ".." to "http://a/b/", "../" to "http://a/b/", "../g" to "http://a/b/g",
            "../.." to "http://a/", "../../g" to "http://a/g", "../../../g" to "http://a/g",
        ).forEach { (reference, expected) -> assertEquals(expected, resolveUrl(base, reference), reference) }
    }

    @Test fun refusesWhatIsNotHttp() {
        assertNull(resolveUrl("https://cdn.test/a.m3u8", "skd://key-id"))
        assertNull(resolveUrl("https://cdn.test/a.m3u8", "data:text/plain,x"))
    }
}

class ProxyUrlTest {
    @Test fun mirrorsTheUpstreamPathAndRoundTrips() {
        val upstream = "https://cdn.test:8443/show/s1/index.m3u8?token=a%20b&x=1"
        val local = proxyUrl(BASE, "t1", upstream)
        assertEquals("$BASE/s/t1/https/cdn.test:8443/show/s1/index.m3u8?token=a%20b&x=1", local)
        val target = upstreamOf(local!!.removePrefix(BASE).substringBefore('?'), local.substringAfter('?'))
        assertEquals("t1", target?.token)
        assertEquals(upstream, target?.upstream)
    }

    @Test fun letsARelativeUriResolveOntoTheRightUpstreamFile() {
        // Why the path is mirrored: a DASH manifest's relative segments need no rewriting.
        val local = proxyUrl(BASE, "t1", "https://cdn.test/show/manifest.mpd")!!
        val segment = resolveUrl(local, "video/seg-1.m4s")!!
        assertEquals("https://cdn.test/show/video/seg-1.m4s", upstreamOf(segment.removePrefix(BASE), null)?.upstream)
    }

    @Test fun refusesUserInfoAndOtherSchemes() {
        assertNull(proxyUrl(BASE, "t", "https://user:pw@cdn.test/a.m3u8"), "credentials would sit in a local URL")
        assertNull(proxyUrl(BASE, "t", "ftp://cdn.test/a.m3u8"))
        assertNull(upstreamOf("/s/t/ftp/cdn.test/a", null))
        assertNull(upstreamOf("/elsewhere/a", null))
        assertNull(upstreamOf("/s/t/https/user@cdn.test/a", null))
    }
}

class RewritePlaylistTest {
    private val toProxy: (String) -> String? = { proxyUrl(BASE, "t", it) }

    @Test fun pointsEverySegmentAndTagUriAtTheProxy() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="keys/k1.bin",IV=0x1
            #EXT-X-MAP:URI="/init.mp4"
            #EXTINF:6,
            seg_0.ts
            #EXTINF:6,
            https://other.test/abs/seg_1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val text = rewritePlaylist(playlist, "https://cdn.test/show/index.m3u8", null, toProxy).text
        assertTrue("URI=\"$BASE/s/t/https/cdn.test/show/keys/k1.bin\"" in text, "a key URI, relative")
        assertTrue("URI=\"$BASE/s/t/https/cdn.test/init.mp4\"" in text, "a map URI, root-relative")
        assertTrue("$BASE/s/t/https/cdn.test/show/seg_0.ts" in text)
        assertTrue("$BASE/s/t/https/other.test/abs/seg_1.ts" in text, "another host goes through the proxy too")
        assertTrue("IV=0x1" in text, "the rest of the tag is left alone")
    }

    @Test fun rewritesAMainPlaylistsRenditions() {
        val main = "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",URI=\"subs/en.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n720p/index.m3u8"
        val text = rewritePlaylist(main, "https://cdn.test/show/master.m3u8", null, toProxy).text
        assertTrue("URI=\"$BASE/s/t/https/cdn.test/show/subs/en.m3u8\"" in text)
        assertTrue(text.endsWith("$BASE/s/t/https/cdn.test/show/720p/index.m3u8"))
    }

    @Test fun leavesAFairPlayKeyAlone() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://asset-1\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST"
        assertTrue("URI=\"skd://asset-1\"" in rewritePlaylist(playlist, "https://cdn.test/i.m3u8", null, toProxy).text)
    }

    @Test fun stripsAdsByTheUrisTheServerSentNotTheProxiedOnes() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:3,
            a.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:3,
            /ads/ad_0.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:3,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        // Written for the extension against raw URIs: "^/ads/" would never match a proxied URL.
        val result = rewritePlaylist(playlist, "https://cdn.test/show/index.m3u8", compilePattern("^/ads/"), toProxy)
        assertEquals(1, result.removed)
        assertFalse("ads" in result.text)
        assertTrue("$BASE/s/t/https/cdn.test/show/b.ts" in result.text)
    }
}

class DecoyStripperTest {
    private val png = PNG_SIGNATURE + byteArrayOf(0, 0, 0, 13, 0x47, 0x48, 0x44, 0x52) + ByteArray(40) { 7 }
    private fun ts(packets: Int) = ByteArray(188 * packets) { if (it % 188 == 0) 0x47 else 0x11 }

    @Test fun takesTheWrapperOffAcrossChunkBoundaries() {
        val wrapped = png + ts(400)
        val stripper = DecoyStripper()
        val out = wrapped.toList().chunked(1000).map { stripper.push(it.toByteArray()) }.fold(ByteArray(0)) { a, b -> a + b } + stripper.finish()
        assertContentEquals(ts(400), out)
    }

    @Test fun passesAnUnwrappedSegmentWithoutHoldingItBack() {
        val stripper = DecoyStripper()
        val first = ts(4).copyOfRange(0, 100)
        // Ruled out by the first eight bytes, so nothing waits for the 64 KB window.
        assertContentEquals(first, stripper.push(first))
    }

    @Test fun releasesAShortWrappedFileAtTheEnd() {
        val stripper = DecoyStripper()
        assertEquals(0, stripper.push(png + ts(4)).size, "still deciding")
        assertContentEquals(ts(4), stripper.finish())
    }
}

class ProxyHandlerTest {
    private class Upstream(scope: TestScope, routes: (String, Map<String, String>) -> Pair<HttpStatusCode, Pair<ByteArray, Map<String, String>>>) {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
                val headers = request.headers.entries().associate { it.key to it.value.first() }
                requests += request.url.toString() to headers
                val (status, response) = routes(request.url.toString(), headers)
                val (body, extra) = response
                respond(ByteReadChannel(body), status, headersOf(*extra.map { it.key to listOf(it.value) }.toTypedArray()))
            }
        }))
    }

    private val playlist = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:3,\na.ts\n#EXTINF:3,\n/ads/x.ts\n#EXTINF:3,\nb.ts\n#EXT-X-ENDLIST\n"
    private val png = PNG_SIGNATURE + byteArrayOf(0, 0, 0, 13, 0x47, 0x48, 0x44, 0x52) + ByteArray(40) { 7 }
    private fun ts(packets: Int) = ByteArray(188 * packets) { if (it % 188 == 0) 0x47 else 0x11 }

    private fun rig(scope: TestScope, onStripped: StripListener = StripListener { _, _ -> }): Triple<ProxyHandler, ProxySessions, Upstream> {
        val upstream = Upstream(scope) { url, headers ->
            when {
                headers[HttpHeaders.Referrer] != "https://site.test/" -> HttpStatusCode.Forbidden to (ByteArray(0) to emptyMap())
                url.endsWith("index.m3u8") -> HttpStatusCode.OK to (playlist.encodeToByteArray() to mapOf(HttpHeaders.ContentType to "application/x-mpegURL"))
                url.endsWith("a.ts") && headers[HttpHeaders.Range] != null ->
                    HttpStatusCode.PartialContent to (ts(1) to mapOf(HttpHeaders.ContentRange to "bytes 1000-1187/9000", HttpHeaders.ContentType to "video/mp2t"))
                url.endsWith("a.ts") -> HttpStatusCode.OK to ((png + ts(4)) to mapOf(HttpHeaders.ContentType to "video/mp2t"))
                url.endsWith("gone.ts") -> HttpStatusCode.NotFound to (ByteArray(0) to emptyMap())
                else -> HttpStatusCode.OK to (ByteArray(0) to emptyMap())
            }
        }
        val sessions = ProxySessions { "tok" }
        return Triple(ProxyHandler(upstream.client, sessions, BASE, onStripped), sessions, upstream)
    }

    private fun pathOf(local: String) = local.removePrefix(BASE).substringBefore('?')

    @Test fun rewritesAPlaylistWithTheSessionsRefererAndStripsItsAds() = runTest {
        var stripped: Pair<Int, Int>? = null
        val (handler, sessions, upstream) = rig(this, StripListener { removed, total -> stripped = removed to total })
        val token = sessions.open(ProxySession("https://site.test/", compilePattern("^/ads/")))
        val response = handler.handle(pathOf(handler.urlFor(token, "https://cdn.test/show/index.m3u8")!!), null)

        assertEquals(200, response.status)
        assertEquals("application/vnd.apple.mpegurl", response.headers[HttpHeaders.ContentType])
        val text = response.body.decodeToString()
        assertTrue("$BASE/s/tok/https/cdn.test/show/a.ts" in text)
        assertFalse("/ads/" in text)
        assertEquals(1 to 3, stripped)
        assertEquals("https://site.test/", upstream.requests.single().second[HttpHeaders.Referrer])
    }

    @Test fun unwrapsADecoySegment() = runTest {
        val (handler, sessions) = rig(this)
        val token = sessions.open(ProxySession("https://site.test/", null))
        val response = handler.handle(pathOf(handler.urlFor(token, "https://cdn.test/show/a.ts")!!), null)
        assertEquals(200, response.status)
        assertContentEquals(ts(4), response.body)
        assertEquals("${ts(4).size}", response.headers[HttpHeaders.ContentLength])
    }

    @Test fun passesARangeThroughUntouched() = runTest {
        val (handler, sessions, upstream) = rig(this)
        val token = sessions.open(ProxySession("https://site.test/", null))
        val response = handler.handle(pathOf(handler.urlFor(token, "https://cdn.test/show/a.ts")!!), null, range = "bytes=1000-1187")
        assertEquals(206, response.status)
        assertEquals("bytes 1000-1187/9000", response.headers[HttpHeaders.ContentRange])
        assertContentEquals(ts(1), response.body)
        assertEquals("bytes=1000-1187", upstream.requests.single().second[HttpHeaders.Range])
    }

    @Test fun relaysAnUpstreamRefusalAsItIs() = runTest {
        // Its status is what the player's error banner names the failure by.
        val (handler, sessions) = rig(this)
        val noReferer = ProxySessions { "bare" }.let { it.open(ProxySession("", null)); it }
        val bareHandler = ProxyHandler(rig(this).third.client, noReferer, BASE)
        assertEquals(403, bareHandler.handle(pathOf(bareHandler.urlFor("bare", "https://cdn.test/show/a.ts")!!), null).status)
        val token = sessions.open(ProxySession("https://site.test/", null))
        assertEquals(404, handler.handle(pathOf(handler.urlFor(token, "https://cdn.test/gone.ts")!!), null).status)
    }

    @Test fun refusesAnUnknownTokenAndAPathItCannotRead() = runTest {
        val (handler) = rig(this)
        assertEquals(403, handler.handle("/s/not-a-session/https/cdn.test/show/a.ts", null).status)
        assertEquals(404, handler.handle("/favicon.ico", null).status)
    }

    @Test fun forgetsAClosedSession() = runTest {
        val (handler, sessions) = rig(this)
        val token = sessions.open(ProxySession("https://site.test/", null))
        sessions.close(token)
        assertEquals(403, handler.handle(pathOf(handler.urlFor(token, "https://cdn.test/show/a.ts")!!), null).status)
    }
}
