package com.paulcoding.hlsplayer.proxy

import com.paulcoding.hlsplayer.playback.stripAdSegments

/** A `URI="…"` attribute, as `EXT-X-KEY`, `EXT-X-MAP`, `EXT-X-MEDIA` and friends carry. */
private val URI_ATTRIBUTE = Regex("URI=\"([^\"]*)\"")

data class RewriteResult(val text: String, val removed: Int, val total: Int)

/** True when a response body is an HLS playlist, whatever the server called it. */
fun looksLikePlaylist(contentType: String?, url: String, head: ByteArray): Boolean {
    val type = contentType?.lowercase().orEmpty()
    if ("mpegurl" in type) return true
    if (url.substringBefore('?').lowercase().endsWith(".m3u8")) return true
    return head.decodeToString(0, minOf(head.size, 16)).trimStart('﻿').startsWith("#EXTM3U")
}

/**
 * A playlist made to load through the proxy: the ad segments cut first — the
 * pattern is written against the URIs as the server sent them — then every
 * http(s) URI, on its own line or in a tag's `URI` attribute, pointed at the
 * proxy. Other schemes (`skd://`, `data:`) are left as they are.
 */
fun rewritePlaylist(text: String, playlistUrl: String, adPattern: Regex?, toProxy: (String) -> String?): RewriteResult {
    val stripped = stripAdSegments(text, adPattern)

    fun mapped(reference: String): String? = resolveUrl(playlistUrl, reference)?.let(toProxy)

    val lines = stripped.text.split('\n').map { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> line
            trimmed.startsWith("#") -> URI_ATTRIBUTE.replace(line) { match ->
                mapped(match.groupValues[1])?.let { "URI=\"$it\"" } ?: match.value
            }
            else -> mapped(trimmed) ?: line
        }
    }
    return RewriteResult(lines.joinToString("\n"), stripped.removed, stripped.total)
}
