package com.paulcoding.hlsplayer.playback

/**
 * Server-side ad insertion splices ad segments straight into the media
 * playlist, so there is no URL to block. What sets them apart is the URI, so a
 * pattern naming the ad segments is enough to cut them back out.
 */

/** Tags that describe the segment after them and go with it; the rest is playlist state. */
private val SEGMENT_TAGS = listOf(
    "#EXTINF",
    "#EXT-X-BYTERANGE",
    "#EXT-X-DISCONTINUITY",
    "#EXT-X-PROGRAM-DATE-TIME",
    "#EXT-X-GAP",
    "#EXT-X-BITRATE",
)

private fun isSegmentTag(line: String) = SEGMENT_TAGS.any { line == it || line.startsWith("$it:") }

/** Only a finished playlist can be cut: a live window is addressed by sequence number. */
private fun isComplete(text: String) =
    "#EXT-X-ENDLIST" in text || "#EXT-X-PLAYLIST-TYPE:VOD" in text

data class StripResult(val text: String, val removed: Int, val total: Int)

/**
 * Removes every segment whose URI matches [pattern], with the tags describing
 * it. The text comes back untouched when there is nothing to do.
 */
fun stripAdSegments(text: String, pattern: Regex?): StripResult {
    if (pattern == null) return StripResult(text, 0, 0)
    // A main playlist lists renditions, not segments.
    if ("#EXTINF" !in text || !isComplete(text)) return StripResult(text, 0, 0)

    val kept = mutableListOf<String>()
    // Tags wait until a URI closes the segment; sticky ones survive a dropped
    // segment, and buffering keeps every tag in its original order.
    var pending = mutableListOf<Pair<String, Boolean>>()
    var removed = 0
    var total = 0

    text.split('\n').forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        if (trimmed.startsWith("#")) {
            pending += line to !isSegmentTag(trimmed)
            return@forEach
        }

        total += 1
        val dropped = pattern.containsMatchIn(trimmed)
        if (dropped) removed += 1
        pending.forEach { (tag, sticky) -> if (!dropped || sticky) kept += tag }
        if (!dropped) kept += line
        pending = mutableListOf()
    }
    // Whatever trails the last segment — `#EXT-X-ENDLIST` and friends.
    pending.forEach { (tag, sticky) -> if (sticky) kept += tag }

    // A pattern that matches everything is a mistake, not a request for silence.
    if (removed == 0 || removed == total) return StripResult(text, 0, total)
    return StripResult(kept.joinToString("\n") + "\n", removed, total)
}

/**
 * A pattern half-typed in a form must not throw mid-playback. Patterns are
 * written for the extension's JavaScript engine; the common syntax is shared.
 */
fun compilePattern(source: String?): Regex? {
    if (source.isNullOrEmpty()) return null
    return try {
        Regex(source)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
