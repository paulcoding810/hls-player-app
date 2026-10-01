package com.paulcoding.hlsplayer.util

import com.paulcoding.hlsplayer.subtitles.readSubtitleUrls

/**
 * Validation rather than parsing: an extension backup holds URLs the browser
 * already normalized, and they are keys — progress is stored under them — so
 * one coming back through here must come back unchanged.
 */
private val HTTP_URL = Regex("^(https?)://([^/?#\\s]+)([/?#]\\S*)?$", RegexOption.IGNORE_CASE)

/** A normalized http(s) URL, or null when the input is unusable. */
fun normalizeSource(input: String?): String? {
    val value = input?.trim().orEmpty()
    if (value.isEmpty()) return null
    val match = HTTP_URL.matchEntire(value) ?: return null
    val (scheme, authority, rest) = match.destructured
    val host = authority.substringAfterLast('@')
    if (host.isEmpty() || host.startsWith(':')) return null
    val userInfo = authority.removeSuffix(host)
    val path = when {
        rest.isEmpty() -> "/"
        rest.startsWith("?") || rest.startsWith("#") -> "/$rest"
        else -> rest
    }
    return "${scheme.lowercase()}://$userInfo${host.lowercase()}$path"
}

/** A `Referer` may be a bare origin, so it is normalized separately. */
fun normalizeReferer(input: String?): String {
    val value = input?.trim().orEmpty()
    if (value.isEmpty()) return ""
    val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) value else "https://$value"
    return normalizeSource(withScheme).orEmpty()
}

data class EpisodeLine(val title: String, val src: String, val subtitles: List<com.paulcoding.hlsplayer.model.Subtitle> = emptyList())

data class ParsedLines(val episodes: List<EpisodeLine>, val skipped: Int)

/**
 * One episode per line, a bare URL or `Title | https://…`, with any subtitle
 * URLs after the video separated by whitespace. The last `|` separates, since a
 * title may hold anything and a URL cannot hold an unencoded space.
 */
fun parseEpisodeLines(text: String?): ParsedLines {
    val episodes = mutableListOf<EpisodeLine>()
    val seen = mutableSetOf<String>()
    var skipped = 0

    text.orEmpty().split(Regex("\\r?\\n")).forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach

        val separator = trimmed.lastIndexOf('|')
        val title = if (separator > -1) trimmed.substring(0, separator).trim() else ""
        val urls = (if (separator > -1) trimmed.substring(separator + 1) else trimmed)
            .split(Regex("\\s+")).filter { it.isNotEmpty() }

        val src = normalizeSource(urls.firstOrNull())
        if (src == null) {
            skipped += 1
            return@forEach
        }
        val rest = urls.drop(1)
        // A mistyped subtitle URL is reported rather than quietly dropped.
        val subtitles = readSubtitleUrls(rest)
        if (subtitles.size != rest.size) {
            skipped += 1
            return@forEach
        }
        if (!seen.add(src)) return@forEach
        episodes += EpisodeLine(title, src, subtitles)
    }
    return ParsedLines(episodes, skipped)
}

const val HLS_MIME = "application/x-mpegURL"
const val DASH_MIME = "application/dash+xml"

private fun pathOf(src: String): String? =
    normalizeSource(src)?.substringAfter("://")?.let { rest ->
        val start = rest.indexOf('/')
        if (start < 0) "/" else rest.substring(start).substringBefore('?').substringBefore('#')
    }

/** `.mpd` is DASH, everything else HLS — most stream URLs carry no usable extension. */
fun manifestMime(src: String): String =
    if (pathOf(src)?.lowercase()?.endsWith(".mpd") == true) DASH_MIME else HLS_MIME

fun isDash(src: String): Boolean = manifestMime(src) == DASH_MIME

/** True when the URL *path* names a manifest, whatever the query string says. */
fun isManifestUrl(src: String): Boolean {
    val path = pathOf(src)?.lowercase() ?: return false
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

fun fileNameOf(src: String): String {
    val normalized = normalizeSource(src) ?: return src
    val path = pathOf(normalized).orEmpty()
    val last = path.split('/').lastOrNull { it.isNotEmpty() }
    return last?.let(::percentDecode) ?: normalized.substringAfter("://").substringBefore('/')
}

fun percentDecode(value: String): String = try {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            bytes += value.substring(index + 1, index + 3).toInt(16).toByte()
            index += 3
        } else {
            char.toString().encodeToByteArray().forEach { bytes += it }
            index += 1
        }
    }
    bytes.toByteArray().decodeToString()
} catch (_: NumberFormatException) {
    value
}
