package com.paulcoding.hlsplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.paulcoding.hlsplayer.playback.EngineEvent
import com.paulcoding.hlsplayer.playback.EngineState
import com.paulcoding.hlsplayer.playback.LoadRequest
import com.paulcoding.hlsplayer.playback.TextFetcher
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.store.JsonStore
import com.paulcoding.hlsplayer.stremio.StremioClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Not built yet: AVPlayer has no playlist hook, so ad stripping and unwrapping
 * disguised segments need a local proxy or a resource-loader delegate.
 */
private class PendingEngine : PlatformEngine {
    override val state = MutableStateFlow(EngineState())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 1)
    override fun load(request: LoadRequest) { events.tryEmit(EngineEvent.Failed("Playback on iOS is not built yet.")) }
    override fun play() {}
    override fun pause() {}
    override fun seekTo(seconds: Double) {}
    override fun setRate(rate: Double) {}
    override fun setVolume(volume: Double) {}
    override fun setMuted(muted: Boolean) {}
    override fun selectLevel(id: String?) {}
    override fun stop() {}
    override fun release() {}

    @Composable
    override fun Surface(modifier: Modifier) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Playback on iOS is not built yet.", color = Color.White, modifier = Modifier.padding(16.dp))
        }
    }
}

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

fun MainViewController() = ComposeUIViewController {
    val http = HttpClient(Darwin)
    App(
        AppDeps(
            data = AppData(JsonStore(FileSystem.SYSTEM, "${documents()}/data".toPath())),
            client = StremioClient(http),
            fetchText = TextFetcher { url, referer ->
                http.get(url) { if (referer.isNotEmpty()) header("Referer", referer) }.bodyAsText()
            },
            files = PendingFiles(),
            newEngine = { PendingEngine() },
            version = "1.0.0",
            persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        ),
    )
}
