package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.playback.PNG_SIGNATURE
import com.paulcoding.hlsplayer.playback.compilePattern
import com.paulcoding.hlsplayer.proxy.ProxyServer
import com.paulcoding.hlsplayer.proxy.ProxySession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The proxy over a real socket, as a player meets it. */
class ProxyServerTest {
    private val png = PNG_SIGNATURE + byteArrayOf(0, 0, 0, 13, 0x47, 0x48, 0x44, 0x52) + ByteArray(40) { 7 }
    private val ts = ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0x11 }
    private val playlist = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:3,\na.ts\n#EXTINF:3,\n/ads/x.ts\n#EXTINF:3,\nb.ts\n#EXT-X-ENDLIST\n"

    private val upstream = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        when {
            request.headers[HttpHeaders.Referrer] != "https://site.test/" -> respond("", HttpStatusCode.Forbidden)
            url.endsWith("index.m3u8") -> respond(playlist, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-mpegURL"))
            request.headers[HttpHeaders.Range] != null ->
                respond(ByteReadChannel(ts.copyOfRange(0, 188)), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-187/1504"))
            else -> respond(ByteReadChannel(png + ts), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "video/mp2t"))
        }
    })

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun fetch(url: String, method: String = "GET", range: String? = null): Reply {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        range?.let { connection.setRequestProperty("Range", it) }
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.first() }
        connection.disconnect()
        return Reply(status, headers, body)
    }

    @Test fun servesAPlaybackOverLoopback() = runBlocking {
        val server = ProxyServer(upstream)
        try {
            val handler = server.handler()
            val token = server.sessions.open(ProxySession("https://site.test/", compilePattern("^/ads/")))
            val local = handler.urlFor(token, "https://cdn.test/show/index.m3u8")!!
            assertTrue(local.startsWith("http://127.0.0.1:"), local)

            val list = fetch(local)
            assertEquals(200, list.status)
            val text = list.body.decodeToString()
            assertFalse("/ads/" in text, "ads cut")
            val segment = text.lines().first { it.endsWith("a.ts") }
            assertTrue(segment.startsWith("http://127.0.0.1:"), "segments come back through the proxy")

            val bytes = fetch(segment)
            assertEquals(200, bytes.status)
            assertContentEquals(ts, bytes.body, "the PNG wrapper is gone")
            assertEquals("${ts.size}", bytes.headers["Content-Length"])

            val ranged = fetch(segment, range = "bytes=0-187")
            assertEquals(206, ranged.status)
            assertEquals("bytes 0-187/1504", ranged.headers["Content-Range"])

            assertEquals(200, fetch(segment, method = "HEAD").status, "a HEAD probe is answered")
        } finally {
            server.stop()
        }
    }

    @Test fun refusesARequestWithoutASession() = runBlocking {
        val server = ProxyServer(upstream)
        try {
            val handler = server.handler()
            val stray = handler.urlFor("not-a-session", "https://cdn.test/show/index.m3u8")!!
            assertEquals(403, fetch(stray).status)
            assertEquals(404, fetch(stray.substringBefore("/s/") + "/favicon.ico").status)
        } finally {
            server.stop()
        }
    }

    @Test fun startsOnceAndKeepsItsPort() = runBlocking {
        val server = ProxyServer(upstream)
        try {
            val first = server.handler().urlFor("t", "https://cdn.test/a.m3u8")!!
            val second = server.handler().urlFor("t", "https://cdn.test/a.m3u8")!!
            assertEquals(first, second)
        } finally {
            server.stop()
        }
    }
}
