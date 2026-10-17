package com.paulcoding.hlsplayer.model

import kotlinx.serialization.Serializable

/**
 * The stored shapes, field for field the extension's — a backup written by
 * either side must read on the other. Optional fields stay nullable rather than
 * defaulted so an absent field is written as absent, as the extension does.
 */

@Serializable
data class Subtitle(val label: String, val src: String, val lang: String? = null)

/** What an addon episode names; its URL is resolved on every play. */
@Serializable
data class StreamRef(val pluginId: String, val type: String = "movie", val videoId: String)

/** The link back to the addon a movie came from, which Refresh reads. */
@Serializable
data class SourceRef(val pluginId: String, val itemId: String, val type: String? = null)

@Serializable
data class Episode(
    val id: String,
    val title: String,
    val src: String? = null,
    val stream: StreamRef? = null,
    val subtitles: List<Subtitle>? = null,
)

@Serializable
data class Movie(
    val id: String,
    val title: String = "",
    val poster: String = "",
    val referer: String = "",
    val adPattern: String = "",
    /** The subtitle language this movie prefers, matched like the global one; "" inherits it. */
    val subtitleLang: String = "",
    val source: SourceRef? = null,
    /** When it was marked finished; playing it again clears this. */
    val watchedAt: Long? = null,
    val skipLeading: Int? = null,
    val skipTrailing: Int? = null,
    /** Seconds the Skip intro jump moves forward, for an intro that does not start at 0. */
    val introLength: Int? = null,
    val autoSkip: Boolean? = null,
    val lastEpisodeId: String? = null,
    val episodes: List<Episode> = emptyList(),
    val addedAt: Long = 0,
    val updatedAt: Long? = null,
    val lastPlayedAt: Long? = null,
)

@Serializable
data class LastPlayed(val movieId: String, val episodeId: String)

@Serializable
data class Library(val movies: List<Movie> = emptyList(), val lastPlayed: LastPlayed? = null)

@Serializable
data class Position(val position: Double, val duration: Double, val updatedAt: Long = 0)

/** A Stremio addon; `kind` carries its one value so an older kind can be recognised and dropped. */
@Serializable
data class Plugin(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: String = "stremio",
    val url: String = "",
    val referer: String = "",
    val adPattern: String = "",
)

@Serializable
data class PluginStore(val items: List<Plugin> = emptyList())
