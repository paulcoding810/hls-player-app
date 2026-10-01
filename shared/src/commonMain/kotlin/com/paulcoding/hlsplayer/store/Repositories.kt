package com.paulcoding.hlsplayer.store

import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.library.buildEpisodes
import com.paulcoding.hlsplayer.library.findMovie
import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.library.mergeEpisodes
import com.paulcoding.hlsplayer.model.LastPlayed
import com.paulcoding.hlsplayer.model.Library
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.model.PluginStore
import com.paulcoding.hlsplayer.model.Position
import com.paulcoding.hlsplayer.model.SourceRef
import com.paulcoding.hlsplayer.settings.Settings
import com.paulcoding.hlsplayer.settings.readSettings
import com.paulcoding.hlsplayer.settings.sanitize
import com.paulcoding.hlsplayer.util.AppJson
import com.paulcoding.hlsplayer.util.newId
import com.paulcoding.hlsplayer.util.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

const val LIBRARY = "library"
const val SETTINGS = "settings"
const val PROGRESS = "progress"
const val PLUGINS = "plugins"

/** Below this many seconds in there is nothing worth resuming. */
const val RESUME_MIN_POSITION = 15.0
/** The last stretch of an item counts as watched. */
const val RESUME_TAIL = 30.0
/** Positions are written every few seconds, so the map is capped. */
const val MAX_POSITIONS = 200

val PositionMap: KSerializer<Map<String, Position>> = MapSerializer(String.serializer(), Position.serializer())

internal fun <T> decode(serializer: KSerializer<T>, element: JsonElement?, fallback: T): T =
    if (element == null) fallback else try {
        AppJson.decodeFromJsonElement(serializer, element)
    } catch (_: Exception) {
        fallback
    }

/**
 * The tree encoder writes `null` for every absent optional, whatever
 * `explicitNulls` says; the extension leaves them out, and so does this.
 */
internal fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement =
    AppJson.encodeToJsonElement(serializer, value).withoutNulls()

private fun JsonElement.withoutNulls(): JsonElement = when (this) {
    is JsonObject -> JsonObject(filterValues { it !is kotlinx.serialization.json.JsonNull }.mapValues { it.value.withoutNulls() })
    is JsonArray -> JsonArray(map { it.withoutNulls() })
    else -> this
}

/** The stalest positions go once the map passes the cap. */
internal fun capPositions(all: Map<String, Position>): Map<String, Position> =
    if (all.size <= MAX_POSITIONS) all
    else all.entries.sortedByDescending { it.value.updatedAt }.take(MAX_POSITIONS).associate { it.key to it.value }

/**
 * Everything the app stores, as flows the screens observe. Each write is a
 * read-modify-write under the store's lock, so the flows only ever follow disk.
 */
class AppData(val store: JsonStore, private val now: () -> Long = ::nowMillis, private val id: () -> String = ::newId) {
    private val _library = MutableStateFlow(readLibrary())
    private val _settings = MutableStateFlow(readSettings(store.read(SETTINGS) as? JsonObject))
    private val _progress = MutableStateFlow(readProgress())
    private val _plugins = MutableStateFlow(readPlugins())

    val library: StateFlow<Library> = _library.asStateFlow()
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val progress: StateFlow<Map<String, Position>> = _progress.asStateFlow()
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    private fun readLibrary() = decode(Library.serializer(), store.read(LIBRARY), Library())
    private fun readProgress() = decode(PositionMap, store.read(PROGRESS), emptyMap())
    private fun readPlugins() = addonsIn(store.read(PLUGINS))

    /** Re-reads every namespace, after an import replaced them underneath. */
    fun reload() {
        _library.value = readLibrary()
        _settings.value = readSettings(store.read(SETTINGS) as? JsonObject)
        _progress.value = readProgress()
        _plugins.value = readPlugins()
    }

    // ---- library

    private suspend fun <T> changeLibrary(change: (Library) -> Pair<Library?, T>): T =
        store.update(LIBRARY) { raw ->
            val (next, result) = change(decode(Library.serializer(), raw, Library()))
            if (next != null) _library.value = next
            (next?.let { encode(Library.serializer(), it) }) to result
        }

    suspend fun addMovie(
        title: String,
        episodes: List<EpisodeDraft>,
        poster: String = "",
        referer: String = "",
        adPattern: String = "",
        source: SourceRef? = null,
        skipLeading: Int? = null,
        skipTrailing: Int? = null,
        autoSkip: Boolean? = null,
    ): Movie = changeLibrary { library ->
        val movie = Movie(
            id = id(),
            title = title.trim().ifEmpty { "Untitled" },
            poster = poster,
            referer = referer,
            adPattern = adPattern,
            source = source,
            skipLeading = skipLeading,
            skipTrailing = skipTrailing,
            autoSkip = autoSkip,
            episodes = buildEpisodes(episodes, id = id),
            addedAt = now(),
        )
        library.copy(movies = library.movies + movie) to movie
    }

    /** Stamps `updatedAt`, which the Recently updated order reads. */
    suspend fun updateMovie(movieId: String, change: (Movie) -> Movie) = changeLibrary { library ->
        library.copy(movies = library.movies.map {
            if (it.id == movieId) change(it).copy(id = it.id, updatedAt = now()) else it
        }) to Unit
    }

    suspend fun removeMovie(movieId: String) = changeLibrary { library ->
        Library(
            movies = library.movies.filterNot { it.id == movieId },
            lastPlayed = library.lastPlayed?.takeUnless { it.movieId == movieId },
        ) to Unit
    }

    /** Replaces the episode list, keeping the id of every episode still there. */
    suspend fun setEpisodes(movieId: String, drafts: List<EpisodeDraft>) =
        updateMovie(movieId) { it.copy(episodes = mergeEpisodes(it.episodes, drafts, id)) }

    /** Remembered both globally and on the movie, so each one keeps its own place. */
    suspend fun setLastPlayed(movieId: String, episodeId: String) = changeLibrary { library ->
        library.copy(
            movies = library.movies.map {
                if (it.id == movieId) it.copy(lastEpisodeId = episodeId, lastPlayedAt = now(), watchedAt = null) else it
            },
            lastPlayed = LastPlayed(movieId, episodeId),
        ) to Unit
    }

    /**
     * Records a movie as finished: its stored positions go, so does the episode
     * it was left on. Opening it again clears the mark.
     */
    suspend fun markWatched(movieId: String, watched: Boolean = true) {
        val movie = findMovie(library.value, movieId) ?: return
        if (watched) clearProgressFor(movie.episodes.map { it.key })
        changeLibrary { library ->
            library.copy(
                movies = library.movies.map {
                    if (it.id == movieId) it.copy(
                        watchedAt = if (watched) now() else null,
                        lastEpisodeId = if (watched) null else it.lastEpisodeId,
                    ) else it
                },
                lastPlayed = if (watched && library.lastPlayed?.movieId == movieId) null else library.lastPlayed,
            ) to Unit
        }
    }

    suspend fun clearLibrary() = changeLibrary { Library() to Unit }

    // ---- progress

    private suspend fun changeProgress(change: (MutableMap<String, Position>) -> Boolean) =
        store.update(PROGRESS) { raw ->
            val all = decode(PositionMap, raw, emptyMap()).toMutableMap()
            if (!change(all)) return@update null to Unit
            val capped = capPositions(all)
            _progress.value = capped
            encode(PositionMap, capped) to Unit
        }

    fun getProgress(key: String): Position? = _progress.value[key]?.takeIf { it.position > 0 }

    /**
     * Stores where playback stopped, or drops the entry when the position
     * carries no information — near the start, past the tail, or live.
     */
    suspend fun saveProgress(key: String, position: Double, duration: Double) {
        if (key.isEmpty()) return
        val finished = duration.isFinite() && position >= duration - RESUME_TAIL
        if (!duration.isFinite() || position < RESUME_MIN_POSITION || finished) return clearProgress(key)
        changeProgress { all -> all[key] = Position(position, duration, now()); true }
    }

    suspend fun clearProgress(key: String) = changeProgress { all -> all.remove(key) != null }

    suspend fun clearProgressFor(keys: List<String>) = changeProgress { all -> keys.map { all.remove(it) != null }.any { it } }

    /** Folds imported positions in, keeping whichever entry was written last. */
    suspend fun mergeProgress(incoming: Map<String, Position>) = changeProgress { all ->
        incoming.forEach { (key, entry) -> if (entry.updatedAt >= (all[key]?.updatedAt ?: 0)) all[key] = entry }
        true
    }

    // ---- settings

    suspend fun updateSettings(change: (Settings) -> Settings) = store.update(SETTINGS) { raw ->
        val next = sanitize(change(readSettings(raw as? JsonObject)))
        _settings.value = next
        encode(Settings.serializer(), next) to next
    }

    // ---- plugins

    suspend fun savePlugins(items: List<Plugin>) {
        store.replace(PLUGINS, encode(PluginStore.serializer(), PluginStore(items)))
        _plugins.value = items
    }

    suspend fun addPlugin(config: Plugin): Plugin {
        val plugin = config.copy(id = id(), kind = "stremio")
        savePlugins(plugins.value + plugin)
        return plugin
    }

    suspend fun updatePlugin(pluginId: String, change: (Plugin) -> Plugin) =
        savePlugins(plugins.value.map { if (it.id == pluginId) change(it).copy(id = it.id) else it })

    suspend fun removePlugin(pluginId: String) = savePlugins(plugins.value.filterNot { it.id == pluginId })

    /**
     * Sources described by hand-written paths are no longer readable; dropping
     * them once and writing back beats re-deciding on every read.
     */
    suspend fun dropRetiredPlugins() {
        val stored = (store.read(PLUGINS) as? JsonObject)?.get("items") as? JsonArray ?: return
        val kept = addonsIn(store.read(PLUGINS))
        if (kept.size != stored.size) savePlugins(kept)
    }
}

/**
 * The Stremio addons in a stored plugin document. Read from the raw JSON: the
 * model defaults `kind` to its one value, which would let a retired source
 * stored without one through.
 */
internal fun addonsIn(raw: JsonElement?): List<Plugin> {
    val items = (raw as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        val kind = ((item as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (kind != "stremio") null else runCatching { AppJson.decodeFromJsonElement(Plugin.serializer(), item) }.getOrNull()
    }
}
