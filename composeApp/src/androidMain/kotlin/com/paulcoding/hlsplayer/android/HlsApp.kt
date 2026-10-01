package com.paulcoding.hlsplayer.android

import android.app.Application
import com.paulcoding.hlsplayer.playback.TextFetcher
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.store.JsonStore
import com.paulcoding.hlsplayer.stremio.StremioClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** What lives as long as the process: the stores, the HTTP client, the lasting-write scope. */
class HlsApp : Application() {
    val http by lazy { HttpClient(OkHttp) }
    val data by lazy { AppData(JsonStore(FileSystem.SYSTEM, filesDir.toOkioPath() / "data")) }
    val client by lazy { StremioClient(http) }
    val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val fetchText = TextFetcher { url, referer ->
        val response = http.get(url) { if (referer.isNotEmpty()) header("Referer", referer) }
        if (!response.status.isSuccess()) error("answered ${response.status.value}")
        response.bodyAsText()
    }
}
