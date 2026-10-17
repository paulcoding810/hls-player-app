package com.paulcoding.hlsplayer.library

import com.paulcoding.hlsplayer.model.Episode
import com.paulcoding.hlsplayer.model.Library
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Position
import com.paulcoding.hlsplayer.model.SourceRef
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.model.Subtitle
import com.paulcoding.hlsplayer.playback.compilePattern
import com.paulcoding.hlsplayer.settings.Settings
import com.paulcoding.hlsplayer.settings.SortOrder
import com.paulcoding.hlsplayer.subtitles.readSubtitles
import com.paulcoding.hlsplayer.util.AppJson
import com.paulcoding.hlsplayer.util.PrettyJson
import com.paulcoding.hlsplayer.util.arr
import com.paulcoding.hlsplayer.util.booleanValue
import com.paulcoding.hlsplayer.util.field
import com.paulcoding.hlsplayer.util.newId
import com.paulcoding.hlsplayer.util.normalizeReferer
import com.paulcoding.hlsplayer.util.normalizeSource
import com.paulcoding.hlsplayer.util.obj
import com.paulcoding.hlsplayer.util.parseJson
import com.paulcoding.hlsplayer.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** An episode before it has an id: what forms, pastes and addons hand over. */
data class EpisodeDraft(
    val title: String = "",
    val src: String? = null,
    val stream: StreamRef? = null,
    val subtitles: List<Subtitle> = emptyList(),
)

private const val STREAM_KEY_PREFIX = "stremio:"
/** A plugin id holds no colon; a video id may (`tt0108778:1:1`). */
private val STREAM_KEY = Regex("^stremio:[^:]+:.+$")

/**
 * What identifies an episode: its URL, or — when an addon resolves the stream
 * only at play time — the video it names. Progress is stored under this.
 */
fun episodeKey(src: String?, stream: StreamRef?): String = when {
    !src.isNullOrEmpty() -> src
    stream != null -> "$STREAM_KEY_PREFIX${stream.pluginId}:${stream.videoId}"
    else -> ""
}

val Episode.key get() = episodeKey(src, stream)
val EpisodeDraft.key get() = episodeKey(src, stream)

/** Whether a stored key is one [episodeKey] could have produced. */
fun isEpisodeKey(key: String?): Boolean =
    key != null && (STREAM_KEY.matches(key) || normalizeSource(key) != null)

/** What an episode names when its URL is only resolved at play time, or null. */
fun readStream(raw: JsonElement?): StreamRef? {
    val obj = raw.obj()
    val pluginId = obj.field("pluginId").string()
    val videoId = obj.field("videoId").string()
    val type = obj.field("type").string().ifEmpty { "movie" }
    return if (pluginId.isNotEmpty() && videoId.isNotEmpty()) StreamRef(pluginId, type, videoId) else null
}

/** The link back to a source plugin; anything malformed becomes "hand-made". */
fun readSource(raw: JsonElement?): SourceRef? {
    val obj = raw.obj()
    val pluginId = obj.field("pluginId").string()
    val itemId = obj.field("itemId").string()
    val type = obj.field("type").string().ifEmpty { null }
    return if (pluginId.isNotEmpty() && itemId.isNotEmpty()) SourceRef(pluginId, itemId, type) else null
}

/** Untitled episodes are numbered. */
fun buildEpisodes(drafts: List<EpisodeDraft>, offset: Int = 0, id: () -> String = ::newId): List<Episode> =
    drafts.mapIndexed { position, draft ->
        Episode(
            id = id(),
            title = draft.title.trim().ifEmpty { "Episode ${offset + position + 1}" },
            src = draft.src,
            stream = draft.stream,
            subtitles = draft.subtitles.ifEmpty { null },
        )
    }

/**
 * A new episode list in which an id is kept for every key still present —
 * what carries `lastEpisodeId` and the resume position across an edit or a
 * refresh. A refresh that brings no subtitles keeps the ones already stored.
 */
fun mergeEpisodes(current: List<Episode>, drafts: List<EpisodeDraft>, id: () -> String = ::newId): List<Episode> {
    val known = current.associateBy { it.key }
    return drafts.mapIndexed { position, draft ->
        val existing = known[draft.key]
        val subtitles = draft.subtitles.ifEmpty { existing?.subtitles.orEmpty() }
        Episode(
            id = existing?.id ?: id(),
            title = draft.title.trim().ifEmpty { existing?.title ?: "Episode ${position + 1}" },
            src = draft.src,
            stream = draft.stream,
            subtitles = subtitles.ifEmpty { null },
        )
    }
}

fun findMovie(library: Library, movieId: String?): Movie? = library.movies.firstOrNull { it.id == movieId }

fun findEpisode(movie: Movie?, episodeId: String?): Episode? = movie?.episodes?.firstOrNull { it.id == episodeId }

/** The addon a movie came from, for its card; [missing] when it has since been deleted. */
data class SourceLabel(val name: String, val type: String, val missing: Boolean)

/** Null for a hand-added movie. Refresh and Play go through the addon, so a missing one is worth showing. */
fun describeSource(movie: Movie, plugins: List<com.paulcoding.hlsplayer.model.Plugin>): SourceLabel? {
    val source = movie.source ?: return null
    val plugin = plugins.firstOrNull { it.id == source.pluginId }
    return SourceLabel(
        name = plugin?.name.orEmpty(),
        type = source.type.orEmpty().replaceFirstChar { it.uppercase() },
        missing = plugin == null,
    )
}

/** Where opening a movie should start: where it was left, else the beginning. */
fun resumeEpisodeId(movie: Movie?): String? =
    findEpisode(movie, movie?.lastEpisodeId)?.id ?: movie?.episodes?.firstOrNull()?.id

/**
 * When a movie last came up. A movie never played falls back to when it was
 * added, so adding one puts it at the top rather than under everything watched.
 */
private fun watchedAt(movie: Movie, positions: Map<String, Position>): Long {
    val stamped = maxOf(movie.lastPlayedAt ?: 0, movie.watchedAt ?: 0)
    if (stamped > 0) return stamped
    val fromProgress = movie.episodes.maxOfOrNull { positions[it.key]?.updatedAt ?: 0 } ?: 0
    return if (fromProgress > 0) fromProgress else movie.addedAt
}

private fun sortKey(movie: Movie, order: SortOrder, positions: Map<String, Position>): Long = when (order) {
    SortOrder.Updated -> movie.updatedAt ?: movie.addedAt
    SortOrder.Added -> movie.addedAt
    else -> watchedAt(movie, positions)
}

/**
 * Sorted for display; the stored order is left alone. Every order but title is
 * newest first, ties falling back to the reverse of the stored order.
 */
fun sortMovies(movies: List<Movie>, order: SortOrder = SortOrder.Watched, positions: Map<String, Position> = emptyMap()): List<Movie> {
    val indexed = movies.withIndex().toList()
    val sorted = if (order == SortOrder.Title) {
        indexed.sortedWith(compareBy<IndexedValue<Movie>> { it.value.title.lowercase() }.thenBy { it.index })
    } else {
        indexed.sortedWith(
            compareByDescending<IndexedValue<Movie>> { sortKey(it.value, order, positions) }
                .thenByDescending { it.index },
        )
    }
    return sorted.map { it.value }
}

/** The playback fields a movie may override; a blank one inherits the global setting. */
data class PlaybackConfig(
    val referer: String,
    val adPattern: String,
    val subtitleLang: String,
    val skipLeading: Int,
    val skipTrailing: Int,
    val introLength: Int,
    val autoSkip: Boolean,
    val playbackRate: Double,
    val autoplay: Boolean,
    val muted: Boolean,
    val volume: Double,
)

fun resolveConfig(movie: Movie?, settings: Settings) = PlaybackConfig(
    referer = movie?.referer?.ifEmpty { null } ?: settings.referer,
    adPattern = movie?.adPattern?.ifEmpty { null } ?: settings.adPattern,
    subtitleLang = movie?.subtitleLang?.ifEmpty { null } ?: settings.subtitleLang,
    skipLeading = movie?.skipLeading ?: settings.skipLeading,
    skipTrailing = movie?.skipTrailing ?: settings.skipTrailing,
    introLength = movie?.introLength ?: settings.introLength,
    autoSkip = movie?.autoSkip ?: settings.autoSkip,
    playbackRate = settings.playbackRate,
    autoplay = settings.autoplay,
    muted = settings.muted,
    volume = settings.volume,
)

/** A movie read from pasted JSON, before it is stored. */
data class MovieDraft(
    val title: String,
    val poster: String = "",
    val referer: String = "",
    val adPattern: String = "",
    val subtitleLang: String = "",
    val skipLeading: Int? = null,
    val skipTrailing: Int? = null,
    val introLength: Int? = null,
    val autoSkip: Boolean? = null,
    val episodes: List<EpisodeDraft> = emptyList(),
)

/** Thrown with a message meant for the person who pasted the text. */
class JsonInputException(message: String) : IllegalArgumentException(message)

private fun optionalNumber(value: JsonElement?, field: String): Int? {
    if (value == null || value is JsonNull) return null
    if (value is JsonPrimitive && value.isString && value.content.isEmpty()) return null
    val number = (value as? JsonPrimitive)?.let { if (it.isString) it.content.trim().toDoubleOrNull() else it.doubleOrNull }
    if (number == null || !number.isFinite() || number < 0) throw JsonInputException("\"$field\" must be 0 or more.")
    return number.roundToInt()
}

/**
 * Reads a movie from pasted JSON in the shape the library stores and the
 * export writes. An addon's episode carries the video it names instead of a URL.
 */
fun parseMovieJson(text: String): MovieDraft {
    val raw = try {
        parseJson(text)
    } catch (_: Exception) {
        throw JsonInputException("That is not valid JSON.")
    }
    val obj = raw as? JsonObject ?: throw JsonInputException("Expected a single movie object, like the example below.")

    val title = obj.field("title").string().trim()
    if (title.isEmpty()) throw JsonInputException("Give the movie a \"title\".")

    val list = obj.field("episodes").arr()?.toList().orEmpty()
    if (list.isEmpty()) throw JsonInputException("Add an \"episodes\" array with at least one URL.")

    val episodes = list.mapIndexed { position, entry ->
        val value = if (entry is JsonPrimitive) entry.string() else entry.obj().let { (it.field("src") ?: it.field("url")).string() }
        val src = normalizeSource(value)
        val stream = readStream(entry.obj().field("stream"))
        if (src == null && stream == null) {
            throw JsonInputException("Episode ${position + 1} has no valid http(s) URL or addon stream.")
        }
        EpisodeDraft(
            title = entry.obj().field("title").string().trim(),
            src = src,
            stream = stream,
            subtitles = readSubtitles(entry.obj().field("subtitles")),
        )
    }

    val adPattern = obj.field("adPattern").string().trim()
    if (adPattern.isNotEmpty() && compilePattern(adPattern) == null) {
        throw JsonInputException("\"adPattern\" is not a valid regular expression.")
    }
    val autoSkip = obj.field("autoSkip")
    if (autoSkip != null && autoSkip !is JsonNull && autoSkip.booleanValue() == null) {
        throw JsonInputException("\"autoSkip\" must be true, false or null.")
    }

    return MovieDraft(
        title = title,
        poster = obj.field("poster").string().trim(),
        referer = normalizeReferer(obj.field("referer").string()),
        adPattern = adPattern,
        subtitleLang = obj.field("subtitleLang").string().trim().lowercase(),
        skipLeading = optionalNumber(obj.field("skipLeading"), "skipLeading"),
        skipTrailing = optionalNumber(obj.field("skipTrailing"), "skipTrailing"),
        introLength = optionalNumber(obj.field("introLength"), "introLength"),
        autoSkip = autoSkip.booleanValue(),
        episodes = episodes,
    )
}

/**
 * A movie as the text [parseMovieJson] reads back. Blank fields are left out —
 * absent means "inherit the global default" — and so are local-only ones.
 */
fun movieToJson(
    title: String,
    poster: String = "",
    referer: String = "",
    adPattern: String = "",
    skipLeading: Int? = null,
    skipTrailing: Int? = null,
    autoSkip: Boolean? = null,
    episodes: List<EpisodeDraft>,
    introLength: Int? = null,
    subtitleLang: String = "",
): String {
    val json = buildJsonObject {
        put("title", title)
        if (poster.isNotEmpty()) put("poster", poster)
        if (referer.isNotEmpty()) put("referer", referer)
        if (adPattern.isNotEmpty()) put("adPattern", adPattern)
        if (subtitleLang.isNotEmpty()) put("subtitleLang", subtitleLang)
        skipLeading?.let { put("skipLeading", it) }
        skipTrailing?.let { put("skipTrailing", it) }
        introLength?.let { put("introLength", it) }
        autoSkip?.let { put("autoSkip", it) }
        putJsonArray("episodes") {
            episodes.forEach { episode ->
                add(buildJsonObject {
                    put("title", episode.title)
                    episode.src?.let { put("src", it) }
                    episode.stream?.let { stream ->
                        putJsonObject("stream") {
                            put("pluginId", stream.pluginId)
                            put("type", stream.type)
                            put("videoId", stream.videoId)
                        }
                    }
                    if (episode.subtitles.isNotEmpty()) {
                        put("subtitles", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Subtitle.serializer()), episode.subtitles))
                    }
                })
            }
        }
    }
    return PrettyJson.encodeToString(JsonObject.serializer(), json)
}

fun Movie.toJsonText() = movieToJson(
    title, poster, referer, adPattern, skipLeading, skipTrailing, autoSkip,
    episodes.map { EpisodeDraft(it.title, it.src, it.stream, it.subtitles.orEmpty()) },
    introLength,
    subtitleLang,
)
