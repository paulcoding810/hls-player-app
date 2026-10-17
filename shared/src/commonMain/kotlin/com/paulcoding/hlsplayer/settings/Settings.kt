package com.paulcoding.hlsplayer.settings

import com.paulcoding.hlsplayer.util.booleanValue
import com.paulcoding.hlsplayer.util.field
import com.paulcoding.hlsplayer.util.jsNumber
import com.paulcoding.hlsplayer.util.string
import com.paulcoding.hlsplayer.util.truthy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

enum class SortOrder(val value: String, val label: String) {
    Watched("watched", "Recently watched"),
    Added("added", "Recently added"),
    Updated("updated", "Recently updated"),
    Title("title", "Title");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value }
    }
}

data class Choice(val value: Double, val label: String)

/** Cue scales offered wherever subtitle size is set. */
val SUBTITLE_SIZES = listOf(
    Choice(0.75, "Small"), Choice(1.0, "Normal"), Choice(1.25, "Large"),
    Choice(1.5, "Larger"), Choice(2.0, "Largest"),
)

/** How far the cues sit above the bottom edge, in rem as the extension measures it. */
val SUBTITLE_POSITIONS = listOf(
    Choice(1.5, "Low"), Choice(4.5, "Normal"), Choice(8.0, "High"), Choice(12.0, "Highest"),
)

val PLAYBACK_RATES = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

@Serializable
data class Settings(
    /** Sent as the `Referer` header for items that do not carry their own. */
    val referer: String = "",
    /** Regex matching the URI of an SSAI ad segment; blank strips nothing. */
    val adPattern: String = "",
    /** Open `.m3u8` and `.mpd` links handed to the app. */
    val grabLinks: Boolean = true,
    val gallerySort: String = SortOrder.Watched.value,
    val autoplay: Boolean = true,
    val muted: Boolean = false,
    val volume: Double = 1.0,
    val subtitlesOn: Boolean = false,
    /** Matched on the prefix, so `en` finds `en-GB`. */
    val subtitleLang: String = "",
    val subtitleSize: Double = 1.0,
    val subtitleBackground: Boolean = true,
    val subtitlePosition: Double = 4.5,
    val playbackRate: Double = 1.0,
    val autoSkip: Boolean = true,
    /** Seconds cut from the start of every item (intros). */
    val skipLeading: Int = 0,
    /** Seconds before the end at which the next item starts (credits). */
    val skipTrailing: Int = 0,
    /** Seconds the Skip intro jump moves forward, for intros that start later; 0 offers none. */
    val introLength: Int = 0,
)

val DEFAULT_SETTINGS = Settings()

/** A stored value outside its range would mute the player or make it throw. */
private fun clamp(value: Double, low: Double = 0.0, high: Double = 1.0, fallback: Double = 1.0) =
    if (value.isFinite()) value.coerceIn(low, high) else fallback

private fun toSeconds(value: Double) = if (value.isFinite() && value > 0) value.roundToInt() else 0

/**
 * Settings from whatever was stored or imported, merged over the defaults and
 * coerced exactly as the extension's `sanitizeSettings` does.
 */
fun readSettings(stored: JsonObject?): Settings {
    val d = DEFAULT_SETTINGS
    fun has(name: String) = stored?.containsKey(name) == true
    fun bool(name: String, default: Boolean) = if (has(name)) stored.field(name).truthy() else default
    fun number(name: String, default: Double) = if (has(name)) stored.field(name).jsNumber() else default

    val sort = if (has("gallerySort")) stored.field("gallerySort").string() else d.gallerySort
    val rate = number("playbackRate", d.playbackRate)
    return Settings(
        referer = (if (has("referer")) stored.field("referer").string() else d.referer).trim(),
        adPattern = (if (has("adPattern")) stored.field("adPattern").string() else d.adPattern).trim(),
        grabLinks = bool("grabLinks", d.grabLinks),
        gallerySort = SortOrder.of(sort)?.value ?: d.gallerySort,
        autoplay = bool("autoplay", d.autoplay),
        muted = bool("muted", d.muted),
        volume = clamp(number("volume", d.volume)),
        subtitlesOn = bool("subtitlesOn", d.subtitlesOn),
        subtitleLang = (if (has("subtitleLang")) stored.field("subtitleLang").string() else "").trim().lowercase(),
        subtitleSize = clamp(number("subtitleSize", d.subtitleSize), 0.75, 2.0, 1.0),
        // The box stays unless it is turned off.
        subtitleBackground = !has("subtitleBackground") || stored.field("subtitleBackground").booleanValue() != false,
        subtitlePosition = clamp(number("subtitlePosition", d.subtitlePosition), 1.5, 12.0, 4.5),
        playbackRate = if (rate > 0) rate else 1.0,
        autoSkip = bool("autoSkip", d.autoSkip),
        skipLeading = toSeconds(number("skipLeading", 0.0)),
        skipTrailing = toSeconds(number("skipTrailing", 0.0)),
        introLength = toSeconds(number("introLength", 0.0)),
    )
}

fun sanitize(settings: Settings): Settings = readSettings(
    com.paulcoding.hlsplayer.util.AppJson.encodeToJsonElement(Settings.serializer(), settings) as JsonObject,
)
