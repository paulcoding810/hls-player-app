package com.paulcoding.hlsplayer.android

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.paulcoding.hlsplayer.App
import com.paulcoding.hlsplayer.AppDeps
import com.paulcoding.hlsplayer.LocalIsTv
import com.paulcoding.hlsplayer.util.normalizeSource

class MainActivity : ComponentActivity() {
    /** A manifest link handed to the app, until the player has taken it. */
    private var incoming by mutableStateOf<String?>(null)
    private lateinit var files: AndroidFileExchange

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only, so the bar icons are always light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        files = AndroidFileExchange(this)
        if (savedInstanceState == null) incoming = linkIn(intent)
        val app = application as HlsApp
        val deps = AppDeps(
            data = app.data,
            client = app.client,
            fetchText = app.fetchText,
            files = files,
            newEngine = { Media3Engine(this) },
            version = packageManager.getPackageInfo(packageName, 0).versionName ?: "",
            persistScope = app.persistScope,
        )
        val tv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        setContent {
            CompositionLocalProvider(LocalIsTv provides tv) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    App(deps, incoming, onIncomingHandled = { incoming = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkIn(intent)?.let { incoming = it }
    }

    private fun linkIn(intent: Intent?) =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(::normalizeSource)
}
