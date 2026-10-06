package com.paulcoding.hlsplayer.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.paulcoding.hlsplayer.LocalDeps
import com.paulcoding.hlsplayer.LocalNavigator
import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.library.movieToJson
import com.paulcoding.hlsplayer.library.resumeEpisodeId
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.model.SourceRef
import com.paulcoding.hlsplayer.playback.PlayTarget
import com.paulcoding.hlsplayer.stremio.CatalogRef
import com.paulcoding.hlsplayer.stremio.Details
import com.paulcoding.hlsplayer.stremio.SearchResult
import com.paulcoding.hlsplayer.stremio.SourceGroup
import com.paulcoding.hlsplayer.stremio.StremioException
import com.paulcoding.hlsplayer.stremio.searchAll
import com.paulcoding.hlsplayer.ui.Field
import com.paulcoding.hlsplayer.ui.GhostButton
import com.paulcoding.hlsplayer.ui.Help
import com.paulcoding.hlsplayer.ui.IconAction
import com.paulcoding.hlsplayer.ui.Icons
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.PrimaryButton
import com.paulcoding.hlsplayer.ui.Radius
import com.paulcoding.hlsplayer.ui.SectionLabel
import com.paulcoding.hlsplayer.ui.Sheet
import com.paulcoding.hlsplayer.ui.TopBar
import com.paulcoding.hlsplayer.ui.focusRing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** What a catalog hands back in one go; the next page starts after it. */
private const val PAGE = 100

private data class CatalogEntry(val plugin: Plugin, val catalog: CatalogRef) {
    val key get() = "${plugin.id}:${catalog.type}:${catalog.id}"
}

private data class Viewing(val plugin: Plugin, val result: SearchResult, val details: Details? = null, val episodes: List<EpisodeDraft>? = null, val error: String? = null) {
    val key get() = "${plugin.id}:${result.id}"
}

/**
 * The extension's `catalog.html`: search every enabled addon, or browse one of
 * their catalogs. It is the only place movies are added from an addon.
 */
@Composable
fun SourcesScreen() {
    val deps = LocalDeps.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val plugins by deps.data.plugins.collectAsState()
    val library by deps.data.library.collectAsState()

    var query by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<SourceGroup>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var catalogs by remember { mutableStateOf(emptyList<CatalogEntry>()) }
    var picked by remember { mutableStateOf<CatalogEntry?>(null) }
    var picking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<SearchResult>()) }
    var browsing by remember { mutableStateOf(false) }
    /** Null once a page comes back short — there is nothing further to ask for. */
    var skip by remember { mutableStateOf<Int?>(0) }
    var adding by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }

    /** Results already in the library offer Watch instead of Add. */
    val added = remember(library.movies) {
        library.movies.mapNotNull { movie -> movie.source?.let { "${it.pluginId}:${it.itemId}" to movie } }.toMap()
    }

    LaunchedEffect(plugins) {
        catalogs = plugins.filter { it.enabled }.map { plugin ->
            async {
                // An addon whose manifest will not load offers no catalogs;
                // searching it reports the failure in its own group.
                try { deps.client.listCatalogs(plugin).map { CatalogEntry(plugin, it) } } catch (_: StremioException) { emptyList() }
            }
        }.awaitAll().flatten()
    }

    fun play(movie: Movie) = resumeEpisodeId(movie)?.let { navigator.play(PlayTarget.Episode(movie.id, it)) }

    suspend fun add(plugin: Plugin, result: SearchResult, known: List<EpisodeDraft>? = null): Movie? {
        val key = "${plugin.id}:${result.id}"
        adding = key
        return try {
            val episodes = known ?: deps.client.fetchEpisodes(plugin, result.id, result.type)
            if (episodes.isEmpty()) throw StremioException("${plugin.name} returned no episodes for this title.")
            deps.data.addMovie(
                result.title, episodes, result.poster, plugin.referer, plugin.adPattern,
                SourceRef(plugin.id, result.id, result.type.ifEmpty { null }),
            )
        } catch (e: StremioException) {
            navigator.fail(e.message.orEmpty())
            null
        } finally {
            adding = null
        }
    }

    /** Playing needs a library entry — progress and the player both name one. */
    fun watch(plugin: Plugin, result: SearchResult, known: List<EpisodeDraft>? = null) = scope.launch {
        add(plugin, result, known)?.let(::play)
    }

    fun copy(plugin: Plugin, result: SearchResult) = scope.launch {
        try {
            val episodes = deps.client.fetchEpisodes(plugin, result.id, result.type)
            clipboard.setText(AnnotatedString(movieToJson(result.title, result.poster, plugin.referer, plugin.adPattern, episodes = episodes)))
            navigator.warn("Copied “${result.title}” as JSON — ${episodes.size} episode(s).")
        } catch (e: StremioException) {
            navigator.fail("Could not copy: ${e.message}")
        }
    }

    fun open(plugin: Plugin, result: SearchResult) {
        val view = Viewing(plugin, result)
        viewing = view
        scope.launch {
            val next = try {
                val (details, episodes) = deps.client.fetchDetails(plugin, result.id, result.type)
                view.copy(details = details, episodes = episodes)
            } catch (e: StremioException) {
                view.copy(error = e.message)
            }
            // Closed, or another opened, while this one was loading.
            if (viewing?.key == view.key) viewing = next
        }
    }

    fun search() {
        val term = query.trim()
        if (term.isEmpty()) return
        picked = null
        searching = true
        scope.launch {
            try { groups = deps.client.searchAll(plugins, term) } finally { searching = false }
        }
    }

    fun browse(entry: CatalogEntry) {
        picked = entry
        picking = false
        groups = null
        results = emptyList()
        skip = 0
        browsing = true
        scope.launch {
            try {
                val page = deps.client.browseCatalog(entry.plugin, entry.catalog)
                results = page
                // Only a catalog that says it pages can be asked for more, and only after a full page.
                skip = if (entry.catalog.pageable && page.size >= PAGE) page.size else null
            } catch (e: StremioException) {
                navigator.fail(e.message.orEmpty())
                skip = null
            } finally {
                browsing = false
            }
        }
    }

    fun more() {
        val entry = picked ?: return
        val from = skip ?: return
        browsing = true
        scope.launch {
            try {
                val page = deps.client.browseCatalog(entry.plugin, entry.catalog, from)
                val seen = results.map { it.id }.toSet()
                val fresh = page.filter { it.id !in seen }
                results = results + fresh
                // Nothing new also catches an addon that ignores `skip` and repeats itself.
                skip = if (fresh.isNotEmpty()) from + page.size else null
            } catch (e: StremioException) {
                navigator.fail(e.message.orEmpty())
                skip = null
            } finally {
                browsing = false
            }
        }
    }

    val shownGroups: List<SourceGroup> = when {
        groups != null -> groups.orEmpty().filter { it.results.isNotEmpty() || it.error.isNotEmpty() }
        picked != null -> listOf(SourceGroup(picked!!.plugin, picked!!.catalog, results, ""))
        else -> emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Sources") { IconAction(Icons.Library, "Library", { navigator.back() }) }
        if (plugins.none { it.enabled }) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Film, null, Modifier.size(40.dp), tint = Palette.inkFaint)
                Text("No sources yet.", color = Palette.inkFaint, modifier = Modifier.padding(vertical = 12.dp))
                Help("Add a Stremio addon in Settings.")
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Search", query, { query = it }, placeholder = "Search every enabled source",
                        keyboard = KeyboardOptions(imeAction = ImeAction.Search), onSubmit = ::search)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Search", ::search, enabled = query.isNotBlank(), icon = Icons.Search)
                        if (catalogs.isNotEmpty()) GhostButton(picked?.let { "${it.plugin.name} · ${it.catalog.name}" } ?: "Browse a catalog", { picking = true }, icon = Icons.Layers)
                    }
                }
            }
            if (searching || (browsing && results.isEmpty())) item { Text("Searching…", color = Palette.inkFaint, fontSize = 14.sp) }
            else if (groups != null && shownGroups.isEmpty()) item { Text("Nothing found.", color = Palette.inkFaint, fontSize = 14.sp) }
            else if (groups == null && picked == null) item { Text("Search your sources, or pick a catalog to browse.", color = Palette.inkFaint, fontSize = 14.sp) }

            shownGroups.forEach { group ->
                item(key = "h:${group.plugin.id}:${group.catalog?.type}:${group.catalog?.id}") {
                    SectionLabel(group.catalog?.let { "${group.plugin.name} · ${it.name} · ${it.type}" } ?: group.plugin.name, Modifier.padding(top = 8.dp))
                    if (group.error.isNotEmpty()) Text(group.error, color = Palette.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                items(group.results, key = { "${group.plugin.id}:${group.catalog?.type}:${group.catalog?.id}:${it.id}" }) { result ->
                    val key = "${group.plugin.id}:${result.id}"
                    ResultRow(
                        result,
                        movie = added[key],
                        busy = adding == key,
                        onOpen = { open(group.plugin, result) },
                        onCopy = { copy(group.plugin, result) },
                        onAdd = { scope.launch { add(group.plugin, result) } },
                        onWatch = { movie -> if (movie != null) play(movie) else watch(group.plugin, result) },
                    )
                }
            }
            if (picked != null && groups == null && skip != null && results.isNotEmpty()) {
                item { GhostButton(if (browsing) "Loading…" else "More", ::more, enabled = !browsing) }
            }
        }
    }

    if (picking) {
        Sheet("Browse a catalog", { picking = false }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                catalogs.forEach { entry ->
                    Text(
                        "${entry.plugin.name} · ${entry.catalog.name}",
                        color = if (entry.key == picked?.key) Palette.primary else Palette.ink,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius)).focusRing().clickable { browse(entry) }.padding(12.dp),
                    )
                }
            }
        }
    }

    viewing?.let { view ->
        DetailsSheet(
            view.result, view.details, view.episodes, view.error,
            movie = added[view.key],
            busy = adding == view.key,
            onAdd = { scope.launch { add(view.plugin, view.result, view.episodes) } },
            onWatch = { movie -> if (movie != null) play(movie) else watch(view.plugin, view.result, view.episodes) },
            onCopy = { copy(view.plugin, view.result) },
            onDismiss = { viewing = null },
        )
    }
}

@Composable
private fun Poster(url: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(Radius)).background(Palette.elevated), contentAlignment = Alignment.Center) {
        if (url.isNotEmpty()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Film, null, Modifier.size(20.dp), tint = Palette.inkFaint)
    }
}

@Composable
private fun ResultRow(
    result: SearchResult,
    movie: Movie?,
    busy: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onAdd: () -> Unit,
    onWatch: (Movie?) -> Unit,
) {
    val shape = RoundedCornerShape(Radius)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.panel).border(1.dp, Palette.line, shape).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f).clip(shape).focusRing().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Poster(result.poster, Modifier.size(width = 44.dp, height = 64.dp))
            Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
        }
        IconAction(Icons.Copy, "Copy ${result.title} as JSON", onCopy, size = 36)
        if (movie == null) IconAction(Icons.Plus, "Add ${result.title}", onAdd, enabled = !busy, size = 36)
        IconAction(Icons.Play, "Watch ${result.title}", { onWatch(movie) }, enabled = !busy, tint = Palette.ink, size = 36)
    }
}

@Composable
private fun DetailsSheet(
    result: SearchResult,
    details: Details?,
    episodes: List<EpisodeDraft>?,
    error: String?,
    movie: Movie?,
    busy: Boolean,
    onAdd: () -> Unit,
    onWatch: (Movie?) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = details?.title?.ifEmpty { null } ?: result.title
    val facts = listOfNotNull(
        details?.released?.ifEmpty { null },
        details?.runtime?.ifEmpty { null },
        details?.rating?.let { "IMDb $it" },
        details?.seasons?.takeIf { it > 1 }?.let { "$it seasons" },
        episodes?.size?.takeIf { it > 1 }?.let { "$it episodes" },
    )
    Sheet(title, onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Poster(details?.poster?.ifEmpty { null } ?: result.poster, Modifier.size(width = 96.dp, height = 144.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Palette.inkMuted, fontSize = 12.sp)
                    details?.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), color = Palette.inkFaint, fontSize = 12.sp) }
                }
            }
            if (details == null && error == null) Text("Loading details…", color = Palette.inkFaint, fontSize = 14.sp)
            if (error != null) Text(error, color = Palette.danger, fontSize = 14.sp)
            details?.description?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp) }
            details?.director?.takeIf { it.isNotEmpty() }?.let { Text("Director  ${it.joinToString(", ")}", color = Palette.inkMuted, fontSize = 12.sp) }
            details?.cast?.takeIf { it.isNotEmpty() }?.let { Text("Cast  ${it.joinToString(", ")}", color = Palette.inkMuted, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Copy, "Copy $title as JSON", onCopy)
            if (movie == null) GhostButton(if (busy) "Adding…" else "Add to library", onAdd, enabled = !busy, icon = Icons.Plus)
            Box(Modifier.width(0.dp))
            PrimaryButton("Watch", { onWatch(movie) }, enabled = !busy, icon = Icons.Play)
        }
    }
}
