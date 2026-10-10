package com.paulcoding.hlsplayer.proxy

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ProxyHandler] on a loopback port. It starts on first use, on a port the
 * system picks, and listens on `127.0.0.1` alone — nothing off the device can
 * reach it, and a session token guards it from what is on it.
 */
class ProxyServer(
    private val http: HttpClient,
    val sessions: ProxySessions = ProxySessions(),
    private val onStripped: StripListener = StripListener { _, _ -> },
) {
    private val lock = Mutex()
    private var server: EmbeddedServer<*, *>? = null
    private var handler: ProxyHandler? = null

    /** The handler, with the server started if it was not already. */
    suspend fun handler(): ProxyHandler = lock.withLock { handler ?: start() }

    private suspend fun start(): ProxyHandler {
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/{path...}") { serve(call, withBody = true) }
                // Players probe with HEAD before they range-read.
                head("/{path...}") { serve(call, withBody = false) }
            }
        }
        engine.startSuspend(wait = false)
        val port = engine.engine.resolvedConnectors().first().port
        server = engine
        return ProxyHandler(http, sessions, "http://127.0.0.1:$port", onStripped).also { handler = it }
    }

    private suspend fun serve(call: ApplicationCall, withBody: Boolean) {
        // Requests only arrive once the port is known, which is when the handler exists.
        val current = handler ?: return call.respondBytes(ByteArray(0), status = HttpStatusCode.ServiceUnavailable)
        val response = current.handle(
            path = call.request.path(),
            query = call.request.queryString().ifEmpty { null },
            range = call.request.header(HttpHeaders.Range),
            userAgent = call.request.header(HttpHeaders.UserAgent),
        )
        response.headers.forEach { (name, value) ->
            // Ktor writes these itself from the body and the type below.
            if (name != HttpHeaders.ContentLength && name != HttpHeaders.ContentType) call.response.header(name, value)
        }
        val type = response.headers[HttpHeaders.ContentType]
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream
        val status = HttpStatusCode.fromValue(response.status)
        if (withBody) {
            call.respondBytes(response.body, type, status)
        } else {
            call.response.header(HttpHeaders.ContentLength, "${response.body.size}")
            call.respondBytes(ByteArray(0), type, status)
        }
    }

    suspend fun stop() = lock.withLock {
        server?.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
        handler = null
    }
}
