package com.paulcoding.hlsplayer.stremio

import com.paulcoding.hlsplayer.library.key
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.util.PrettyJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An addon as text its JSON mode reads back, for sharing one. `id` is local to
 * this install and left out; a blank optional field is omitted.
 */
fun pluginToJson(plugin: Plugin): String = PrettyJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("name", plugin.name)
        if (plugin.url.isNotEmpty()) put("url", plugin.url)
        if (plugin.referer.isNotEmpty()) put("referer", plugin.referer)
        if (plugin.adPattern.isNotEmpty()) put("adPattern", plugin.adPattern)
        // Only worth stating when it is not the default.
        if (!plugin.enabled) put("enabled", false)
    },
)

/** One searchable catalog of one addon; a whole addon that failed has no catalog. */
data class SourceGroup(val plugin: Plugin, val catalog: CatalogRef?, val results: List<SearchResult>, val error: String)

/** Every enabled addon at once; one that fails comes back with its error rather than taking the rest down. */
suspend fun StremioClient.searchAll(plugins: List<Plugin>, query: String): List<SourceGroup> = coroutineScope {
    plugins.filter { it.enabled }.map { plugin ->
        async {
            try {
                search(plugin, query).map { SourceGroup(plugin, it.catalog, it.results, it.error) }
            } catch (error: StremioException) {
                // The manifest itself failed, so which catalogs exist is unknown.
                listOf(SourceGroup(plugin, null, emptyList(), error.message.orEmpty()))
            }
        }
    }.awaitAll().flatten()
}

/**
 * Re-reads an addon-backed movie's episodes. Ids are kept for every episode
 * still present, so its place and every stored position ride through.
 */
suspend fun refreshMovie(data: AppData, client: StremioClient, movie: Movie): Int {
    val source = movie.source ?: throw StremioException("This movie was not added from an addon.")
    val plugin = data.plugins.value.firstOrNull { it.id == source.pluginId }
        ?: throw StremioException("The source this movie came from is gone.")
    val episodes = client.fetchEpisodes(plugin, source.itemId, source.type)
    if (episodes.isEmpty()) throw StremioException("${plugin.name.ifEmpty { "The source" }} returned no episodes.")
    // By key, not URL: an addon's episodes have none.
    val known = movie.episodes.map { it.key }.toSet()
    data.setEpisodes(movie.id, episodes)
    return episodes.count { it.key !in known }
}
