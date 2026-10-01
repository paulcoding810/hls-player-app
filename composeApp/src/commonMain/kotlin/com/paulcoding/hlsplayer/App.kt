package com.paulcoding.hlsplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.paulcoding.hlsplayer.playback.PlayTarget
import com.paulcoding.hlsplayer.playback.TextFetcher
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.stremio.StremioClient
import com.paulcoding.hlsplayer.ui.AppTheme
import com.paulcoding.hlsplayer.ui.Notice
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.Toast
import com.paulcoding.hlsplayer.ui.Tone
import com.paulcoding.hlsplayer.ui.library.LibraryScreen
import com.paulcoding.hlsplayer.ui.player.PlayerScreen
import com.paulcoding.hlsplayer.ui.settings.SettingsScreen
import com.paulcoding.hlsplayer.ui.sources.SourcesScreen

/** Everything the screens need, built once by the platform's entry point. */
class AppDeps(
    val data: AppData,
    val client: StremioClient,
    val fetchText: TextFetcher,
    val files: FileExchange,
    val newEngine: () -> PlatformEngine,
    val version: String,
    /** Outlives every screen, for writes that must land after one closes. */
    val persistScope: kotlinx.coroutines.CoroutineScope,
)

/** The pages of the extension, one screen each. */
sealed interface Screen {
    data object Library : Screen
    data object Sources : Screen
    data object Settings : Screen
    data class Player(val target: PlayTarget) : Screen
}

/** Navigation and toasts, for the screens to reach without threading them through. */
class Navigator(start: Screen) {
    val stack = mutableStateListOf(start)
    var notice by mutableStateOf<Notice?>(null)

    val current get() = stack.last()

    fun open(screen: Screen) {
        stack += screen
    }

    /** Opens the player, replacing one already open rather than stacking another. */
    fun play(target: PlayTarget) {
        if (current is Screen.Player) stack[stack.lastIndex] = Screen.Player(target) else stack += Screen.Player(target)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun warn(message: String) {
        notice = Notice(Tone.Warn, message)
    }

    fun fail(message: String) {
        notice = Notice(Tone.Danger, message)
    }
}

val LocalDeps = staticCompositionLocalOf<AppDeps> { error("AppDeps not provided") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/**
 * The root. [incoming] is a link the platform was asked to open — the
 * extension's grabbed `.m3u8` — which plays outside the library.
 */
@Composable
fun App(deps: AppDeps, incoming: String? = null, onIncomingHandled: () -> Unit = {}) {
    val navigator = remember { Navigator(Screen.Library) }

    LaunchedEffect(incoming) {
        if (incoming != null && deps.data.settings.value.grabLinks) {
            navigator.play(PlayTarget.Url(incoming))
        }
        onIncomingHandled()
    }
    LaunchedEffect(Unit) { deps.data.dropRetiredPlugins() }

    AppTheme {
        CompositionLocalProvider(LocalDeps provides deps, LocalNavigator provides navigator) {
            BackHandler(enabled = navigator.stack.size > 1) { navigator.back() }
            Box(Modifier.fillMaxSize().background(Palette.surface)) {
                when (val screen = navigator.current) {
                    Screen.Library -> LibraryScreen()
                    Screen.Sources -> SourcesScreen()
                    Screen.Settings -> SettingsScreen()
                    is Screen.Player -> PlayerScreen(screen.target)
                }
                Toast(navigator.notice, onDismiss = { navigator.notice = null })
            }
        }
    }
}
