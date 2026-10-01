package com.paulcoding.hlsplayer.subtitles

import com.paulcoding.hlsplayer.model.Subtitle
import com.paulcoding.hlsplayer.util.normalizeSource
import com.paulcoding.hlsplayer.util.percentDecode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** SRT and WebVTT time codes differ only in `,` versus `.` for milliseconds. */
private val SRT_TIME = Regex("(\\d+:\\d{2}:\\d{2}),(\\d{3})")

/**
 * WebVTT from SRT or WebVTT. The header is no evidence about the times: files
 * announcing WEBVTT and then using SRT's comma are common.
 */
fun toVtt(text: String?): String {
    // A BOM before `WEBVTT` makes a parser reject the whole file.
    val body = text.orEmpty().removePrefix("﻿").trim()
    if (body.isEmpty()) return ""
    // SRT cue numbers are legal VTT cue identifiers, so only the times change.
    val cues = body.replace(SRT_TIME, "$1.$2")
    return if (cues.startsWith("WEBVTT")) cues else "WEBVTT\n\n$cues"
}

/** A label for a track the user never named, from the file name. */
fun labelFor(src: String, position: Int): String {
    val path = src.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
    val name = path.split('/').lastOrNull { it.isNotEmpty() }?.let(::percentDecode).orEmpty()
    return name.replace(Regex("\\.[^.]+$"), "").ifEmpty { "Subtitles ${position + 1}" }
}

/** Subtitle URLs given bare, as the episode-lines format writes them. */
fun readSubtitleUrls(urls: List<String>): List<Subtitle> = urls.mapIndexedNotNull { position, raw ->
    normalizeSource(raw)?.let { Subtitle(labelFor(it, position), it) }
}

/**
 * `{ label, src }` entries from what a movie or an addon supplied: one URL, a
 * list of URLs, or a list of objects. Anything without a usable URL is dropped.
 */
fun readSubtitles(value: JsonElement?): List<Subtitle> {
    val list = when (value) {
        null -> return emptyList()
        is JsonArray -> value.toList()
        else -> listOf(value)
    }
    return list.mapIndexedNotNull { position, entry ->
        val raw = when (entry) {
            is JsonPrimitive -> entry.contentOrNull
            is JsonObject -> (entry["src"] ?: entry["url"])?.let { (it as? JsonPrimitive)?.contentOrNull }
            else -> null
        }
        val src = normalizeSource(raw) ?: return@mapIndexedNotNull null
        val obj = entry as? JsonObject
        val label = (obj?.get("label") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val lang = (obj?.get("lang") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
        Subtitle(label.ifEmpty { labelFor(src, position) }, src, lang)
    }
}
