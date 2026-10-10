package com.paulcoding.hlsplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.paulcoding.hlsplayer.ui.Palette
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.paulcoding.hlsplayer.playback.TextFetcher
import com.paulcoding.hlsplayer.proxy.ProxyServer
import com.paulcoding.hlsplayer.proxy.StripListener
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.store.JsonStore
import com.paulcoding.hlsplayer.stremio.StremioClient
import com.paulcoding.hlsplayer.util.normalizeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** The backup moves through the Files app once that is built; until then it is unavailable. */
private class PendingFiles : FileExchange {
    override val available = false
    override suspend fun export(name: String, text: String) = false
    override suspend fun import(): String? = null
}

private fun documents(): String {
    val url = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
    return url.path!!
}

/** One per app: every playback shares the loopback server, each with its own token. */
private val http = HttpClient(Darwin)
private val proxy = ProxyServer(http, onStripped = StripListener { removed, total -> IosEngine.currentEngine?.stripped(removed, total) })

/** A manifest link handed to the app, until the player has taken it — Android's VIEW intent. */
private val incomingLinks = MutableStateFlow<String?>(null)

/** Called from Swift for an `hlsplayer://play?url=…` link. */
fun openLink(url: String) {
    normalizeSource(url)?.let { incomingLinks.value = it }
}

fun MainViewController() = ComposeUIViewController {
    val incoming by incomingLinks.collectAsState()
    // The root of the window, so it keeps clear of the status bar and the home indicator itself.
    Box(Modifier.fillMaxSize().background(Palette.surface).safeDrawingPadding()) {
    App(
        AppDeps(
            data = AppData(JsonStore(FileSystem.SYSTEM, "${documents()}/data".toPath())),
            client = StremioClient(http),
            fetchText = TextFetcher { url, referer ->
                http.get(url) { if (referer.isNotEmpty()) header("Referer", referer) }.bodyAsText()
            },
            files = PendingFiles(),
            newEngine = { IosEngine(proxy) },
            version = "1.0.0",
            persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        ),
        incoming = incoming,
        onIncomingHandled = { incomingLinks.value = null },
    )
    }
}
