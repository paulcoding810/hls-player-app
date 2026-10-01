package com.paulcoding.hlsplayer.backup

import com.paulcoding.hlsplayer.library.isEpisodeKey
import com.paulcoding.hlsplayer.library.readSource
import com.paulcoding.hlsplayer.library.readStream
import com.paulcoding.hlsplayer.model.Episode
import com.paulcoding.hlsplayer.model.Library
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.model.PluginStore
import com.paulcoding.hlsplayer.model.Position
import com.paulcoding.hlsplayer.settings.readSettings
import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.store.LIBRARY
import com.paulcoding.hlsplayer.store.PLUGINS
import com.paulcoding.hlsplayer.store.PROGRESS
import com.paulcoding.hlsplayer.store.PositionMap
import com.paulcoding.hlsplayer.store.SETTINGS
import com.paulcoding.hlsplayer.store.addonsIn
import com.paulcoding.hlsplayer.store.capPositions
import com.paulcoding.hlsplayer.store.decode
import com.paulcoding.hlsplayer.store.encode
import com.paulcoding.hlsplayer.subtitles.readSubtitles
import com.paulcoding.hlsplayer.settings.Settings
import com.paulcoding.hlsplayer.util.AppJson
import com.paulcoding.hlsplayer.util.PrettyJson
import com.paulcoding.hlsplayer.util.arr
import com.paulcoding.hlsplayer.util.booleanValue
import com.paulcoding.hlsplayer.util.field
import com.paulcoding.hlsplayer.util.finiteNumber
import com.paulcoding.hlsplayer.util.jsNumber
import com.paulcoding.hlsplayer.util.newId
import com.paulcoding.hlsplayer.util.normalizeSource
import com.paulcoding.hlsplayer.util.nowMillis
import com.paulcoding.hlsplayer.util.obj
import com.paulcoding.hlsplayer.util.parseJson
import com.paulcoding.hlsplayer.util.string
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val BACKUP_FORMAT = "hls-player-backup"
const val BACKUP_VERSION = 1

class BackupException(message: String) : Exception(message)

data class Backup(
    val movies: List<Movie>,
    val settings: JsonObject?,
    val progress: Map<String, Position>,
    val plugins: List<Plugin>,
)

data class ImportSummary(val added: Int, val updated: Int, val positions: Int, val plugins: Int)

/** The extension's `backupFileName`. */
@OptIn(ExperimentalTime::class)
fun backupFileName(millis: Long = nowMillis()): String =
    "hls-player-${Instant.fromEpochMilliseconds(millis).toString().take(10)}.json"

/** The same document the extension's export writes, so either side reads the other's. */
@OptIn(ExperimentalTime::class)
fun buildBackup(data: AppData, millis: Long = nowMillis()): String = PrettyJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("format", BACKUP_FORMAT)
        put("version", BACKUP_VERSION)
        put("exportedAt", Instant.fromEpochMilliseconds(millis).toString())
        put("library", encode(Library.serializer(), data.library.value))
        put("settings", encode(Settings.serializer(), data.settings.value))
        put("progress", encode(PositionMap, data.progress.value))
        put("plugins", encode(ListSerializer(Plugin.serializer()), data.plugins.value))
    },
)

/**
 * A skip value, or null for "inherit the global one". Not `Number(value)` as
 * the extension reads it: that turns the `null` every export writes into 0,
 * so a restored movie stops inheriting the global skips.
 */
private fun numberOrNull(value: JsonElement?): Int? {
    if (value == null || value is kotlinx.serialization.json.JsonNull) return null
    if (value is JsonPrimitive && value.isString && value.content.isBlank()) return null
    val number = value.jsNumber()
    return if (number.isFinite() && number >= 0) number.roundToInt() else null
}

private fun Double.toMillis() = toLong()

/**
 * A backup is a file the user can edit, so every field is taken on suspicion:
 * anything unusable is dropped rather than stored and tripped over later.
 */
internal fun sanitizeMovie(raw: JsonElement?, id: () -> String = ::newId, now: () -> Long = ::nowMillis): Movie? {
    val obj = raw.obj() ?: return null
    val episodes = obj.field("episodes").arr().orEmpty().mapIndexed { position, episode ->
        val subtitles = readSubtitles(episode.obj().field("subtitles"))
        Episode(
            id = episode.obj().field("id").string().ifEmpty { id() },
            title = episode.obj().field("title").string().trim().ifEmpty { "Episode ${position + 1}" },
            src = normalizeSource(episode.obj().field("src").string()),
            stream = readStream(episode.obj().field("stream")),
            subtitles = subtitles.ifEmpty { null },
        )
        // An episode resolved at play time carries no URL, so requiring one
        // would drop every addon episode on the way back in.
    }.filter { it.src != null || it.stream != null }

    // A movie with no playable episode is not worth importing.
    if (episodes.isEmpty()) return null

    val lastEpisodeId = episodes.firstOrNull { it.id == obj.field("lastEpisodeId").string(fallback = "\u0000") }?.id
    return Movie(
        id = obj.field("id").string().ifEmpty { id() },
        title = obj.field("title").string().trim().ifEmpty { "Untitled" },
        poster = obj.field("poster").string(),
        referer = obj.field("referer").string(),
        adPattern = obj.field("adPattern").string(),
        source = readSource(obj.field("source")),
        watchedAt = obj.field("watchedAt").finiteNumber()?.toMillis(),
        skipLeading = numberOrNull(obj.field("skipLeading")),
        skipTrailing = numberOrNull(obj.field("skipTrailing")),
        autoSkip = obj.field("autoSkip").booleanValue(),
        lastEpisodeId = lastEpisodeId,
        episodes = episodes,
        addedAt = obj.field("addedAt").finiteNumber()?.toMillis() ?: now(),
        updatedAt = obj.field("updatedAt").finiteNumber()?.toMillis(),
        lastPlayedAt = obj.field("lastPlayedAt").finiteNumber()?.toMillis(),
    )
}

private fun sanitizeProgress(raw: JsonElement?): Map<String, Position> {
    val obj = raw.obj() ?: return emptyMap()
    return obj.mapNotNull { (key, entry) ->
        val position = entry.obj().field("position").jsNumber()
        val duration = entry.obj().field("duration").jsNumber()
        // Not `normalizeSource`: an addon's episodes are keyed by the video they name.
        if (!isEpisodeKey(key) || !(position > 0) || !duration.isFinite()) return@mapNotNull null
        val updatedAt = entry.obj().field("updatedAt").jsNumber().takeIf { it.isFinite() } ?: 0.0
        key to Position(position, duration, updatedAt.toLong())
    }.toMap()
}

/** An addon is config, so it travels with the rest; an older kind is refused. */
private fun sanitizePlugin(raw: JsonElement?, id: () -> String): Plugin? {
    val obj = raw.obj() ?: return null
    val name = obj.field("name").string().trim()
    val url = obj.field("url").string().trim()
    if (name.isEmpty() || obj.field("kind").string() != "stremio" || url.isEmpty()) return null
    return Plugin(
        id = obj.field("id").string().ifEmpty { id() },
        name = name,
        url = url,
        enabled = obj.field("enabled").booleanValue() != false,
        referer = obj.field("referer").string(),
        adPattern = obj.field("adPattern").string().trim(),
    )
}

/**
 * Parses an exported file. Throws with a message meant for the user, since a
 * failed import is nearly always a wrong file rather than a bug.
 */
fun readBackup(text: String, id: () -> String = ::newId, now: () -> Long = ::nowMillis): Backup {
    val parsed = try {
        parseJson(text).obj()
    } catch (_: Exception) {
        throw BackupException("That file is not valid JSON.")
    }
    if (parsed.field("format").string() != BACKUP_FORMAT) throw BackupException("That file is not an HLS Player export.")
    if (parsed.field("version").jsNumber() > BACKUP_VERSION) {
        throw BackupException("That file was written by a newer version of the app.")
    }

    val movies = parsed.field("library").obj().field("movies").arr().orEmpty().mapNotNull { sanitizeMovie(it, id, now) }
    val progress = sanitizeProgress(parsed.field("progress"))
    val settings = parsed.field("settings").obj()
    val plugins = parsed.field("plugins").arr().orEmpty().mapNotNull { sanitizePlugin(it, id) }

    if (movies.isEmpty() && settings == null && plugins.isEmpty() && progress.isEmpty()) {
        throw BackupException("That file holds nothing to import.")
    }
    return Backup(movies, settings, progress, plugins)
}

/**
 * Folds a backup into what is stored: a movie or addon with the same id is
 * replaced, the rest are appended, and nothing local is dropped.
 */
suspend fun applyBackup(data: AppData, backup: Backup): ImportSummary {
    val summary = data.store.transaction {
        val library = decode(Library.serializer(), read(LIBRARY), Library())
        val byId = LinkedHashMap(library.movies.associateBy { it.id })
        val added = backup.movies.count { it.id !in byId }
        backup.movies.forEach { byId[it.id] = it }
        write(LIBRARY, encode(Library.serializer(), library.copy(movies = byId.values.toList())))

        if (backup.plugins.isNotEmpty()) {
            val plugins = LinkedHashMap(addonsIn(read(PLUGINS)).associateBy { it.id })
            backup.plugins.forEach { plugins[it.id] = it }
            write(PLUGINS, encode(PluginStore.serializer(), PluginStore(plugins.values.toList())))
        }

        if (backup.progress.isNotEmpty()) {
            val all = decode(PositionMap, read(PROGRESS), emptyMap()).toMutableMap()
            backup.progress.forEach { (key, entry) -> if (entry.updatedAt >= (all[key]?.updatedAt ?: 0)) all[key] = entry }
            write(PROGRESS, encode(PositionMap, capPositions(all)))
        }

        backup.settings?.let { incoming ->
            val current = read(SETTINGS).obj() ?: JsonObject(emptyMap())
            write(SETTINGS, encode(Settings.serializer(), readSettings(JsonObject(current + incoming))))
        }

        ImportSummary(added, backup.movies.size - added, backup.progress.size, backup.plugins.size)
    }
    data.reload()
    return summary
}
