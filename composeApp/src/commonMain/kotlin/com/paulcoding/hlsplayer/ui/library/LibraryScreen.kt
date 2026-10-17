package com.paulcoding.hlsplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.paulcoding.hlsplayer.LocalDeps
import com.paulcoding.hlsplayer.LocalNavigator
import com.paulcoding.hlsplayer.Screen
import com.paulcoding.hlsplayer.library.SourceLabel
import com.paulcoding.hlsplayer.library.describeSource
import com.paulcoding.hlsplayer.library.findEpisode
import com.paulcoding.hlsplayer.library.findMovie
import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.library.resumeEpisodeId
import com.paulcoding.hlsplayer.library.sortMovies
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Position
import com.paulcoding.hlsplayer.playback.PlayTarget
import com.paulcoding.hlsplayer.settings.SortOrder
import com.paulcoding.hlsplayer.stremio.StremioException
import com.paulcoding.hlsplayer.stremio.refreshMovie
import com.paulcoding.hlsplayer.ui.Choices
import com.paulcoding.hlsplayer.ui.Confirm
import com.paulcoding.hlsplayer.ui.Field
import com.paulcoding.hlsplayer.ui.GhostButton
import com.paulcoding.hlsplayer.ui.IconAction
import com.paulcoding.hlsplayer.ui.Icons
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.PrimaryButton
import com.paulcoding.hlsplayer.ui.Radius
import com.paulcoding.hlsplayer.ui.SectionLabel
import com.paulcoding.hlsplayer.ui.TopBar
import kotlinx.coroutines.launch

/** The extension's `home.html`: the library, filtered locally and sorted without rewriting the stored order. */
@Composable
fun LibraryScreen() {
    val deps = LocalDeps.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val library by deps.data.library.collectAsState()
    val settings by deps.data.settings.collectAsState()
    val positions by deps.data.progress.collectAsState()
    val plugins by deps.data.plugins.collectAsState()

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Movie?>(null) }
    var refreshing by remember { mutableStateOf<String?>(null) }

    val order = SortOrder.of(settings.gallerySort) ?: SortOrder.Watched
    val sorted = remember(library.movies, order, positions) { sortMovies(library.movies, order, positions) }
    val term = query.trim().lowercase()
    val shown = if (term.isEmpty()) sorted else sorted.filter { term in it.title.lowercase() }
    val lastMovie = findMovie(library, library.lastPlayed?.movieId)
    val lastEpisode = findEpisode(lastMovie, library.lastPlayed?.episodeId) ?: findEpisode(lastMovie, resumeEpisodeId(lastMovie))

    fun play(movie: Movie, episodeId: String? = resumeEpisodeId(movie)) {
        if (episodeId != null) navigator.play(PlayTarget.Episode(movie.id, episodeId))
    }

    fun refresh(movie: Movie) = scope.launch {
        refreshing = movie.id
        try {
            val added = refreshMovie(deps.data, deps.client, movie)
            navigator.warn(if (added > 0) "${movie.title}: $added new episode(s)." else "${movie.title} is already up to date.")
        } catch (e: StremioException) {
            navigator.fail(e.message.orEmpty())
        } finally {
            refreshing = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Library") {
            if (plugins.any { it.enabled }) GhostButton("Sources", { navigator.open(Screen.Sources) }, icon = Icons.Search)
            IconAction(Icons.Plus, "Add movie", { editing = "new" })
            IconAction(Icons.Settings, "Settings", { navigator.open(Screen.Settings) })
        }

        if (library.movies.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Film, null, Modifier.size(40.dp), tint = Palette.inkFaint)
                Text("Your library is empty.", color = Palette.inkFaint, modifier = Modifier.padding(vertical = 12.dp))
                PrimaryButton(if (plugins.any { it.enabled }) "Find something to watch" else "Add a movie", onClick = {
                    if (plugins.any { it.enabled }) navigator.open(Screen.Sources) else editing = "new"
                }, icon = if (plugins.any { it.enabled }) Icons.Search else Icons.Plus)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (lastMovie != null && lastEpisode != null && term.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ContinuePanel(lastMovie, lastEpisode.title, positions[lastEpisode.key]) { play(lastMovie, lastEpisode.id) }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Filter", query, { query = it }, placeholder = "Filter your library")
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Choices(SortOrder.entries.map { it to it.label }, order, { picked ->
                                scope.launch { deps.data.updateSettings { s -> s.copy(gallerySort = picked.value) } }
                            })
                        }
                    }
                }
                items(shown, key = { it.id }) { movie ->
                    MovieCard(
                        movie,
                        source = describeSource(movie, plugins),
                        refreshing = refreshing == movie.id,
                        onPlay = { play(movie) },
                        onWatched = { scope.launch { deps.data.markWatched(movie.id, movie.watchedAt == null) } },
                        onRefresh = { refresh(movie) },
                        onEdit = { editing = movie.id },
                        onDelete = { deleting = movie },
                    )
                }
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("Nothing matches “$query”.", color = Palette.inkFaint) }
                }
            }
        }
    }

    editing?.let { id ->
        val movie = library.movies.firstOrNull { it.id == id }
        MovieForm(movie, settings, onDismiss = { editing = null }, onSave = { draft, keepEpisodes ->
            scope.launch {
                if (movie == null) {
                    deps.data.addMovie(draft.title, draft.episodes, draft.poster, draft.referer, draft.adPattern, null, draft.skipLeading, draft.skipTrailing, draft.autoSkip, draft.introLength, draft.subtitleLang)
                } else {
                    deps.data.updateMovie(movie.id) {
                        it.copy(title = draft.title, poster = draft.poster, referer = draft.referer, adPattern = draft.adPattern,
                            skipLeading = draft.skipLeading, skipTrailing = draft.skipTrailing, introLength = draft.introLength, subtitleLang = draft.subtitleLang, autoSkip = draft.autoSkip)
                    }
                    // An addon movie's form leaves its episodes alone: only the addon sets them.
                    if (!keepEpisodes) deps.data.setEpisodes(movie.id, draft.episodes)
                }
                editing = null
            }
        })
    }

    deleting?.let { movie ->
        Confirm(
            title = "Delete “${movie.title}”?",
            message = "It leaves the library. Its watch positions are dropped too.",
            confirm = "Delete",
            dismiss = "Keep it",
            onConfirm = {
                scope.launch {
                    deps.data.clearProgressFor(movie.episodes.map { it.key })
                    deps.data.removeMovie(movie.id)
                    deleting = null
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ContinuePanel(movie: Movie, episodeTitle: String, position: Position?, onContinue: () -> Unit) {
    val shape = RoundedCornerShape(Radius)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.panel).border(1.dp, Palette.line, shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Continue watching")
            Text(movie.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(episodeTitle, color = Palette.inkFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (position != null && position.duration > 0) ProgressLine(position.position / position.duration, Modifier.padding(top = 6.dp))
        }
        PrimaryButton("Continue", onContinue, icon = Icons.Play)
    }
}

@Composable
private fun ProgressLine(fraction: Double, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Palette.line)) {
        Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).height(3.dp).background(Palette.primary))
    }
}

@Composable
private fun MovieCard(
    movie: Movie,
    source: SourceLabel?,
    refreshing: Boolean,
    onPlay: () -> Unit,
    onWatched: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius)
    Column(Modifier.clip(shape).background(Palette.panel).border(1.dp, Palette.line, shape)) {
        // The poster is what a remote presses most, and the card's own border hides an edge
        // ring, so focus shows as the extension's hover does: dimmed, with a play mark.
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Palette.elevated)
                .onFocusChanged { focused = it.isFocused }.clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            if (movie.poster.isNotEmpty()) {
                AsyncImage(movie.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Film, null, Modifier.size(28.dp), tint = Palette.inkFaint)
            }
            if (focused) {
                Box(
                    Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                        .padding(3.dp).border(3.dp, Palette.primary, RoundedCornerShape(Radius)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Play, null, Modifier.size(40.dp), tint = Palette.ink) }
            }
            if (movie.watchedAt != null) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(6.dp).clip(shape).background(Palette.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Check, null, Modifier.size(12.dp), tint = Palette.primary)
                    Text("Watched", fontSize = 11.sp, color = Palette.ink, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(movie.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            when {
                source == null -> {}
                source.missing -> Text("Source removed", color = Palette.warn, fontSize = 12.sp, maxLines = 1)
                else -> Text(
                    listOf(source.name, source.type).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = Palette.inkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${movie.episodes.size} episode(s)", fontSize = 12.sp, color = Palette.inkFaint)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconAction(Icons.Check, if (movie.watchedAt == null) "Mark as watched" else "Mark as not watched", onWatched,
                tint = if (movie.watchedAt == null) Palette.inkMuted else Palette.primary, size = 36)
            if (movie.source != null) IconAction(Icons.Refresh, "Look for new episodes", onRefresh, enabled = !refreshing, size = 36)
            IconAction(Icons.Edit, "Edit ${movie.title}", onEdit, size = 36)
            IconAction(Icons.Trash, "Delete ${movie.title}", onDelete, size = 36)
        }
    }
}
