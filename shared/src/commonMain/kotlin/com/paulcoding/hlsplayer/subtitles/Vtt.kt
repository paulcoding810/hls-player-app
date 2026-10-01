package com.paulcoding.hlsplayer.subtitles

/**
 * The app draws subtitles itself rather than through the player, which is what
 * lets two tracks stack, a timing offset apply, and both platforms look alike.
 */
data class Cue(val start: Double, val end: Double, val text: String)

private val TIMING = Regex(
    "^((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s+-->\\s+((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})",
)
private val TAG = Regex("<[^>]+>")

/** Seconds from `hh:mm:ss.ttt` or `mm:ss.ttt`; null when it is not a time. */
fun parseTimestamp(value: String): Double? {
    val parts = value.replace(',', '.').split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    val minutes = parts[parts.size - 2].toIntOrNull() ?: return null
    val hours = if (parts.size == 3) parts[0].toIntOrNull() ?: return null else 0
    return hours * 3600.0 + minutes * 60.0 + seconds
}

private fun decodeEntities(text: String) = text
    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    .replace("&lrm;", "").replace("&rlm;", "").replace("&amp;", "&")

/** Cues from a WebVTT file, in order. Blocks that are not cues are skipped. */
fun parseVtt(text: String): List<Cue> {
    val blocks = toVtt(text).replace("\r\n", "\n").replace('\r', '\n').split(Regex("\\n{2,}"))
    return blocks.mapNotNull { block ->
        val lines = block.split('\n')
        // An identifier, when there is one, sits on the line before the timing.
        val timingAt = lines.indexOfFirst { TIMING.containsMatchIn(it.trim()) }
        if (timingAt < 0 || timingAt > 1) return@mapNotNull null
        val match = TIMING.find(lines[timingAt].trim()) ?: return@mapNotNull null
        val start = parseTimestamp(match.groupValues[1]) ?: return@mapNotNull null
        val end = parseTimestamp(match.groupValues[2]) ?: return@mapNotNull null
        val body = lines.drop(timingAt + 1).joinToString("\n") { decodeEntities(it.replace(TAG, "")) }.trim()
        if (body.isEmpty() || end < start) null else Cue(start, end, body)
    }.sortedBy { it.start }
}

/**
 * Every cue moved by `offset` seconds from the times it was born with. Shifting
 * from where cues are now would be lossy: a cue near the start clamps at zero.
 */
fun shiftCues(cues: List<Cue>, offset: Double): List<Cue> =
    if (offset == 0.0) cues else cues.map {
        it.copy(start = maxOf(0.0, it.start + offset), end = maxOf(0.0, it.end + offset))
    }

fun activeCues(cues: List<Cue>, time: Double): List<Cue> =
    cues.filter { it.start <= time && it.end >= time }
