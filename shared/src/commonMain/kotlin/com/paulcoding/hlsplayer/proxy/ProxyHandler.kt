package com.paulcoding.hlsplayer.proxy

import com.paulcoding.hlsplayer.util.newId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

/** What one playback sends upstream and cuts out. */
data class ProxySession(val referer: String, val adPattern: Regex?, val userAgent: String? = null)

/**
 * Playbacks by token. The token is the proxy's lock: it listens on loopback
 * only, but without it any other app on the device — or a web page sending
 * requests to localhost — could use it as an open relay with these headers.
 */
class ProxySessions(private val newToken: () -> String = ::newId) {
    // Replaced whole rather than mutated: the server reads it from many threads
    // at once, and playbacks open and close too rarely for the copies to matter.
    @Volatile
    private var sessions = emptyMap<String, ProxySession>()

    fun open(session: ProxySession): String = newToken().also { sessions = sessions + (it to session) }

    fun close(token: String) {
        sessions = sessions - token
    }

    operator fun get(token: String): ProxySession? = sessions[token]
}

data class ProxyResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?) = other is ProxyResponse && status == other.status &&
        headers == other.headers && body.contentEquals(other.body)

    override fun hashCode() = 31 * (31 * status + headers.hashCode()) + body.contentHashCode()
}

/** Told when a playlist lost segments to the ad pattern, so the UI can say so. */
fun interface StripListener {
    fun stripped(removed: Int, total: Int)
}

/**
 * The proxy without its server: a request in, a response out. A server binds
 * it to `127.0.0.1` and hands it the path, query and `Range` of each request;
 * the player only ever sees a plain HLS or DASH stream.
 */
class ProxyHandler(
    private val http: HttpClient,
    private val sessions: ProxySessions,
    /** `http://127.0.0.1:<port>`, where the server listens. */
    private val base: String,
    private val onStripped: StripListener = StripListener { _, _ -> },
) {
    /** What the player should open for [upstream] in the playback [token] names. */
    fun urlFor(token: String, upstream: String): String? = proxyUrl(base, token, upstream)

    suspend fun handle(path: String, query: String?, range: String? = null, userAgent: String? = null): ProxyResponse {
        val target = upstreamOf(path, query) ?: return ProxyResponse(404)
        val session = sessions[target.token] ?: return ProxyResponse(403)

        val upstream = try {
            http.get(target.upstream) {
                if (session.referer.isNotEmpty()) header(HttpHeaders.Referrer, session.referer)
                (session.userAgent ?: userAgent)?.let { header(HttpHeaders.UserAgent, it) }
                range?.let { header(HttpHeaders.Range, it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Unreachable reads as a gateway failure, which the player reports as a network error.
            return ProxyResponse(502)
        }

        // An upstream refusal is passed on as it is: its status is what names the failure.
        if (!upstream.status.isSuccess()) return ProxyResponse(upstream.status.value)

        val body = upstream.readRawBytes()
        val contentType = upstream.headers[HttpHeaders.ContentType]
        val status = upstream.status.value

        if (looksLikePlaylist(contentType, target.upstream, body)) {
            val result = rewritePlaylist(body.decodeToString(), target.upstream, session.adPattern) { urlFor(target.token, it) }
            if (result.removed > 0) onStripped.stripped(result.removed, result.total)
            val bytes = result.text.encodeToByteArray()
            return ProxyResponse(200, mapOf(HttpHeaders.ContentType to "application/vnd.apple.mpegurl", HttpHeaders.ContentLength to "${bytes.size}"), bytes)
        }

        // Only a read from the start can begin with the decoy; a later range is passed through.
        val fromStart = range == null || Regex("^bytes=0-").containsMatchIn(range.trim())
        val payload = if (fromStart && status == 200) DecoyStripper().let { it.push(body) + it.finish() } else body

        val headers = buildMap {
            put(HttpHeaders.ContentLength, "${payload.size}")
            contentType?.let { put(HttpHeaders.ContentType, it) }
            upstream.headers[HttpHeaders.ContentRange]?.let { put(HttpHeaders.ContentRange, it) }
            upstream.headers[HttpHeaders.AcceptRanges]?.let { put(HttpHeaders.AcceptRanges, it) }
        }
        return ProxyResponse(status, headers, payload)
    }
}
