package com.paulcoding.hlsplayer.stremio

import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.model.StreamRef
import com.paulcoding.hlsplayer.model.Subtitle
import com.paulcoding.hlsplayer.subtitles.readSubtitles
import com.paulcoding.hlsplayer.util.AppJson
import com.paulcoding.hlsplayer.util.arr
import com.paulcoding.hlsplayer.util.field
import com.paulcoding.hlsplayer.util.jsNumber
import com.paulcoding.hlsplayer.util.normalizeSource
import com.paulcoding.hlsplayer.util.obj
import com.paulcoding.hlsplayer.util.parseJson
import com.paulcoding.hlsplayer.util.string
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stremio addons describe content rather than hand over URLs: `/meta/` names
 * videos, and a separate `/stream/` call resolves something playable for one.
 * Those URLs are often short-lived, so an episode stores the video it names
 * and the player resolves it on play.
 */

/** A dead host must not hang a search. */
private const val TIMEOUT_MILLIS = 10_000L

class StremioException(message: String) : Exception(message)

data class SearchResult(val id: String, val title: String, val poster: String, val type: String, val plugin: Plugin)

data class CatalogRef(val type: String, val id: String, val name: String, val pageable: Boolean = false, val searchable: Boolean = false)

/** One searchable catalog's answer; a catalog that failed carries its error instead of vanishing. */
data class CatalogResults(val catalog: CatalogRef?, val results: List<SearchResult>, val error: String = "")

data class ManifestSummary(
    val name: String,
    val version: String,
    val catalogs: Int,
    val searchable: Int,
    /** What this player depends on and the addon lacks. */
    val missing: List<String>,
)

data class Details(
    val title: String,
    val poster: String,
    val background: String,
    val description: String,
    val released: String,
    val runtime: String,
    val genres: List<String>,
    val rating: Double?,
    val cast: List<String>,
    val director: List<String>,
    val seasons: Int,
)

data class FoundStream(
    /** Streams carry no id, and two pointing at one URL are one stream. */
    val url: String,
    val referer: String,
    val name: String,
    val description: String,
    val bingeGroup: String,
    val subtitles: List<Subtitle>,
)

/** `https://host/path/manifest.json`, `https://host/path` and a `stremio://` install link all work. */
fun baseOf(plugin: Plugin): String = plugin.url.trim()
    .replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    .replace(Regex("/manifest\\.json/?$", RegexOption.IGNORE_CASE), "")
    .removeSuffix("/")

private fun labelOf(plugin: Plugin) = plugin.name.ifEmpty { "The addon" }

private fun hasExtra(catalog: JsonElement?, name: String) =
    catalog.obj().field("extra").arr().orEmpty().any { it.obj().field("name").string() == name }

private fun resourceNames(manifest: JsonElement?): Set<String> =
    manifest.obj().field("resources").arr().orEmpty().mapNotNull {
        if (it is JsonPrimitive) it.string().ifEmpty { null } else it.obj().field("name").string().ifEmpty { null }
    }.toSet()

private fun catalogsOf(manifest: JsonElement?): List<JsonObject> =
    manifest.obj().field("catalogs").arr().orEmpty().mapNotNull { it.obj() }
        .filter { it.field("type").string().isNotEmpty() && it.field("id").string().isNotEmpty() }

/**
 * What the settings screen needs to judge an addon. Episodes resolve their
 * stream through the addon that listed them, so a catalog-only addon finds
 * things it cannot play.
 */
fun describeManifest(manifest: JsonElement?): ManifestSummary {
    val resources = resourceNames(manifest)
    val catalogs = catalogsOf(manifest)
    return ManifestSummary(
        name = manifest.obj().field("name").string().trim(),
        version = manifest.obj().field("version").string(),
        catalogs = catalogs.size,
        searchable = catalogs.count { hasExtra(it, "search") },
        missing = listOf("catalog", "meta", "stream").filter { it !in resources },
    )
}

private fun text(value: JsonElement?) = value.string().trim()
private fun names(value: JsonElement?) = value.arr().orEmpty().map { text(it) }.filter { it.isNotEmpty() }

/** What a details view shows; addons fill a different subset of the meta each. */
fun detailsOf(meta: JsonElement?): Details {
    val obj = meta.obj()
    val genreLinks = obj.field("links").arr().orEmpty().filter { it.obj().field("category").string() == "Genres" }
        .map { it.obj().field("name") }.let { JsonArray(it.filterNotNull()) }
    val seasons = obj.field("videos").arr().orEmpty()
        .map { it.obj().field("season").jsNumber() }
        .filter { it.isFinite() && it > 0 }.toSet()
    val rating = obj.field("imdbRating").jsNumber()
    val year = obj.field("year")
    val genres = names(obj.field("genres")).ifEmpty { names(genreLinks) }
    return Details(
        title = text(obj.field("name")),
        poster = normalizeSource(obj.field("poster").string()).orEmpty(),
        background = normalizeSource(obj.field("background").string()).orEmpty(),
        description = text(obj.field("description")),
        released = text(obj.field("releaseInfo")).ifEmpty {
            when {
                year == null -> ""
                year is JsonPrimitive && year.isString -> year.content
                else -> year.jsNumber().takeIf { it.isFinite() && it != 0.0 }?.toLong()?.toString().orEmpty()
            }
        },
        runtime = text(obj.field("runtime")),
        genres = genres,
        rating = rating.takeIf { it.isFinite() && it > 0 },
        cast = names(obj.field("cast")),
        director = names(obj.field("director")),
        seasons = seasons.size,
    )
}

/** `S1E2` when an episode has no title of its own. */
private fun titleOf(video: JsonObject, position: Int): String {
    val named = text(video.field("title"))
    if (named.isNotEmpty()) return named
    val season = video.field("season")
    val episode = video.field("episode")
    val s = (season as? JsonPrimitive)?.takeIf { season !is kotlinx.serialization.json.JsonNull }?.content
    val e = (episode as? JsonPrimitive)?.takeIf { episode !is kotlinx.serialization.json.JsonNull }?.content
    if (s != null && e != null) return "S${s}E$e"
    return "Episode ${position + 1}"
}

/** Episodes carrying the video they name rather than a URL; a movie is its own one. */
fun episodesOf(meta: JsonObject, plugin: Plugin, type: String): List<EpisodeDraft> {
    val videos = meta.field("videos").arr().orEmpty().mapNotNull { it.obj() }
    val entries = videos.ifEmpty {
        listOf(JsonObject(mapOf("id" to (meta["id"] ?: JsonPrimitive("")), "title" to (meta["name"] ?: JsonPrimitive("")))))
    }
    return entries.filter { video -> idOf(video.field("id")).isNotEmpty() }.mapIndexed { position, video ->
        EpisodeDraft(title = titleOf(video, position), stream = StreamRef(plugin.id, type, idOf(video.field("id"))))
    }
}

/** Ids are strings in the protocol, but a number is taken as one rather than dropped. */
private fun idOf(value: JsonElement?): String =
    (value as? JsonPrimitive)?.content?.takeIf { value !is kotlinx.serialization.json.JsonNull }.orEmpty()

/** What this player can open: everything else needs a torrent client or a browser. */
private fun playable(stream: JsonElement) = normalizeSource(stream.obj().field("url").string()) != null

/** Stremio subtitles are `{ id, url, lang }`; the language is the label worth showing. */
private fun subtitlesOf(stream: JsonObject): List<Subtitle> {
    val list = stream.field("subtitles").arr().orEmpty().map { entry ->
        val obj = entry.obj()
        JsonObject(
            mapOf(
                "src" to JsonPrimitive(obj.field("url").string()),
                "label" to JsonPrimitive(obj.field("lang").string().ifEmpty { obj.field("id").string() }),
                "lang" to JsonPrimitive(obj.field("lang").string()),
            ),
        )
    }
    return readSubtitles(JsonArray(list))
}

/**
 * Which of them to play. A `bingeGroup` is the addon's own way of saying "the
 * same provider and quality as last time", so a choice made once follows the season.
 */
fun pickStream(streams: List<FoundStream>, bingeGroup: String?): FoundStream? {
    if (streams.isEmpty()) return null
    return (if (!bingeGroup.isNullOrEmpty()) streams.firstOrNull { it.bingeGroup == bingeGroup } else null) ?: streams.first()
}

/** Speaks the protocol over one HTTP client; manifests are cached per addon. */
class StremioClient(private val http: HttpClient) {
    private val manifests = mutableMapOf<String, JsonElement>()
    private val manifestLock = Mutex()

    private suspend fun fetchJson(url: String, label: String): JsonElement {
        // Each failure is caught where it happens, so a message written here is
        // never caught and labelled a second time on the way out.
        val response = try {
            withTimeout(TIMEOUT_MILLIS) { http.get(url) }
        } catch (_: TimeoutCancellationException) {
            throw StremioException("$label did not answer in time.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw StremioException("$label: ${error.message ?: error::class.simpleName}")
        }
        if (!response.status.isSuccess()) throw StremioException("$label answered ${response.status.value}.")
        return try {
            parseJson(response.bodyAsText())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw StremioException("$label did not return JSON.")
        }
    }

    suspend fun readManifest(plugin: Plugin): JsonElement {
        val base = baseOf(plugin)
        if (base.isEmpty()) throw StremioException("Give the addon a manifest URL.")
        manifestLock.withLock { manifests[base] }?.let { return it }
        val manifest = fetchJson("$base/manifest.json", labelOf(plugin))
        manifestLock.withLock { manifests[base] = manifest }
        return manifest
    }

    /** Forgets a cached manifest, so editing an addon re-reads it. */
    suspend fun forgetManifest(plugin: Plugin) = manifestLock.withLock { manifests.remove(baseOf(plugin)) }

    private fun toResult(meta: JsonObject, plugin: Plugin, type: String? = null) = SearchResult(
        id = idOf(meta.field("id")),
        title = meta.field("name").string(),
        poster = normalizeSource(meta.field("poster").string()).orEmpty(),
        type = meta.field("type").string().ifEmpty { type ?: "movie" },
        plugin = plugin,
    )

    private fun isListed(meta: JsonObject) = idOf(meta.field("id")).isNotEmpty() && meta.field("name").string().isNotEmpty()

    /** Every catalog an addon publishes, for the browse picker. */
    suspend fun listCatalogs(plugin: Plugin): List<CatalogRef> = catalogsOf(readManifest(plugin)).map {
        CatalogRef(
            type = it.field("type").string(),
            id = it.field("id").string(),
            name = it.field("name").string().ifEmpty { it.field("id").string() },
            // Only a catalog that declares `skip` can be paged past its first page.
            pageable = hasExtra(it, "skip"),
            searchable = hasExtra(it, "search"),
        )
    }

    /** One page; `skip` counts items, and is left out at the start — some addons refuse `skip=0`. */
    suspend fun browseCatalog(plugin: Plugin, catalog: CatalogRef, skip: Int = 0): List<SearchResult> {
        val path = if (skip > 0) "${catalog.id}/skip=$skip" else catalog.id
        val payload = fetchJson("${baseOf(plugin)}/catalog/${catalog.type}/$path.json", labelOf(plugin))
        return payload.obj().field("metas").arr().orEmpty().mapNotNull { it.obj() }.filter(::isListed)
            .map { toResult(it, plugin, catalog.type) }
    }

    /** One catalog's answer to a search; a failure is carried in it rather than thrown. */
    suspend fun searchCatalog(plugin: Plugin, catalog: CatalogRef, query: String, label: String = labelOf(plugin)): CatalogResults {
        val named = CatalogRef(type = catalog.type, id = catalog.id, name = catalog.name)
        return try {
            val url = "${baseOf(plugin)}/catalog/${named.type}/${named.id}/search=${query.encodeURLParameter()}.json"
            val seen = mutableSetOf<String>()
            val results = fetchJson(url, label).obj().field("metas").arr().orEmpty().mapNotNull { it.obj() }
                .filter { isListed(it) && seen.add(idOf(it.field("id"))) }
                .map { toResult(it, plugin) }
            CatalogResults(named, results)
        } catch (error: StremioException) {
            CatalogResults(named, emptyList(), error.message.orEmpty())
        }
    }

    /**
     * One entry per catalog that declares a `search` extra, rather than a merged
     * list: "Series" and "Movies" are different answers to the same question.
     */
    suspend fun search(plugin: Plugin, query: String): List<CatalogResults> {
        val manifest = readManifest(plugin)
        val label = plugin.name.ifEmpty { manifest.obj().field("name").string().ifEmpty { "The addon" } }
        return coroutineScope {
            catalogsOf(manifest).filter { hasExtra(it, "search") }.map { catalog ->
                val ref = CatalogRef(
                    type = catalog.field("type").string(),
                    id = catalog.field("id").string(),
                    name = catalog.field("name").string().ifEmpty { catalog.field("id").string() },
                )
                async { searchCatalog(plugin, ref, query, label) }
            }.awaitAll()
        }
    }

    private suspend fun readMeta(plugin: Plugin, id: String, type: String?): Pair<JsonObject, String> {
        val kind = type?.ifEmpty { null } ?: "movie"
        val payload = fetchJson("${baseOf(plugin)}/meta/$kind/${id.encodeURLParameter()}.json", labelOf(plugin))
        val meta = payload.obj().field("meta").obj() ?: throw StremioException("${labelOf(plugin)} returned no details for this title.")
        return meta to kind
    }

    suspend fun fetchEpisodes(plugin: Plugin, id: String, type: String?): List<EpisodeDraft> {
        val (meta, kind) = readMeta(plugin, id, type)
        return episodesOf(meta, plugin, kind)
    }

    /** Details and episodes from one request, so adding from the details view costs no second. */
    suspend fun fetchDetails(plugin: Plugin, id: String, type: String?): Pair<Details, List<EpisodeDraft>> {
        val (meta, kind) = readMeta(plugin, id, type)
        return detailsOf(meta) to episodesOf(meta, plugin, kind)
    }

    /**
     * Every playable stream for one video, in the addon's order — addons put
     * their own preference first. `proxyHeaders` is how one asks for a `Referer`.
     */
    suspend fun listStreams(plugin: Plugin, stream: StreamRef): List<FoundStream> {
        val label = labelOf(plugin)
        val payload = fetchJson("${baseOf(plugin)}/stream/${stream.type}/${stream.videoId.encodeURLParameter()}.json", label)
        val streams = payload.obj().field("streams").arr().orEmpty()
        val found = streams.filter(::playable).mapNotNull { it.obj() }
        if (found.isEmpty()) {
            throw StremioException(
                if (streams.isNotEmpty()) "$label offered no stream this player can open — torrents and external links need another app."
                else "$label found no stream for this episode.",
            )
        }
        return found.map { entry ->
            val hints = entry.field("behaviorHints").obj()
            val headers = hints.field("proxyHeaders").obj().field("request").obj()
            FoundStream(
                url = normalizeSource(entry.field("url").string())!!,
                referer = (headers.field("Referer") ?: headers.field("referer")).string(),
                name = entry.field("name").string(),
                // `title` is the deprecated spelling of `description`.
                description = entry.field("description").string().ifEmpty { entry.field("title").string() },
                bingeGroup = hints.field("bingeGroup").string(),
                subtitles = subtitlesOf(entry),
            )
        }
    }
}
