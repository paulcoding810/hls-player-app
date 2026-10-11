package com.paulcoding.hlsplayer.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.paulcoding.hlsplayer.App
import com.paulcoding.hlsplayer.AppDeps
import com.paulcoding.hlsplayer.BackStack
import com.paulcoding.hlsplayer.LocalWindowState
import com.paulcoding.hlsplayer.exitFullscreen
import com.paulcoding.hlsplayer.playback.TextFetcher
import com.paulcoding.hlsplayer.proxy.ProxyServer
import com.paulcoding.hlsplayer.proxy.StripListener
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.store.JsonStore
import com.paulcoding.hlsplayer.stremio.StremioClient
import com.paulcoding.hlsplayer.util.normalizeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import java.awt.Desktop
import java.awt.Frame
import java.net.URI
import java.net.URLDecoder

private val VERSION = System.getProperty("app.version") ?: "dev"

/** Where each OS keeps an app's own files; `HLS_DATA_DIR` points a test run elsewhere. */
private fun dataDir(): String {
    System.getenv("HLS_DATA_DIR")?.let { return it }
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> "$home/Library/Application Support/HLS Player"
        "win" in os -> "${System.getenv("APPDATA") ?: home}/HLS Player"
        else -> "${System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"}/hls-player"
    }
}

/** A manifest URL, or an `hlsplayer://play?url=…` link naming one. */
private fun linkFrom(input: String): String? {
    val uri = runCatching { URI(input) }.getOrNull()
    if (uri?.scheme != "hlsplayer") return normalizeSource(input)
    val url = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
    return normalizeSource(URLDecoder.decode(url, Charsets.UTF_8))
}

private val http = HttpClient(OkHttp)
private val proxy = ProxyServer(http, onStripped = StripListener { removed, total -> VlcEngine.current?.stripped(removed, total) })

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    val incoming = MutableStateFlow(args.firstNotNullOfOrNull(::linkFrom))
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
        Desktop.getDesktop().setOpenURIHandler { event -> linkFrom(event.uri.toString())?.let { incoming.value = it } }
    }
    val deps = AppDeps(
        data = AppData(JsonStore(FileSystem.SYSTEM, dataDir().toPath())),
        client = StremioClient(http),
        fetchText = TextFetcher { url, referer ->
            http.get(url) { if (referer.isNotEmpty()) header("Referer", referer) }.bodyAsText()
        },
        files = DesktopFileExchange { Frame.getFrames().firstOrNull { it.isActive } },
        newEngine = { VlcEngine(proxy) },
        version = VERSION,
        persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "HLS Player",
            onKeyEvent = { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
                    (exitFullscreen?.let { it(); true } ?: BackStack.goBack())
            },
        ) {
            val link by incoming.collectAsState()
            CompositionLocalProvider(LocalWindowState provides windowState) {
                App(deps, link) { incoming.value = null }
            }
        }
    }
}
