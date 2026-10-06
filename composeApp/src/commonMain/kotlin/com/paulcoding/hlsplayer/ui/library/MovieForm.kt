package com.paulcoding.hlsplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcoding.hlsplayer.library.EpisodeDraft
import com.paulcoding.hlsplayer.library.JsonInputException
import com.paulcoding.hlsplayer.library.MovieDraft
import com.paulcoding.hlsplayer.library.movieToJson
import com.paulcoding.hlsplayer.library.parseMovieJson
import com.paulcoding.hlsplayer.model.Movie
import com.paulcoding.hlsplayer.playback.compilePattern
import com.paulcoding.hlsplayer.settings.Settings
import com.paulcoding.hlsplayer.ui.Choices
import com.paulcoding.hlsplayer.ui.Field
import com.paulcoding.hlsplayer.ui.GhostButton
import com.paulcoding.hlsplayer.ui.Help
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.PrimaryButton
import com.paulcoding.hlsplayer.ui.SectionLabel
import com.paulcoding.hlsplayer.ui.Sheet
import com.paulcoding.hlsplayer.util.normalizeReferer
import com.paulcoding.hlsplayer.util.parseEpisodeLines

private const val JSON_EXAMPLE = """{
  "title": "My show",
  "episodes": [
    { "title": "Pilot", "src": "https://example.com/1.m3u8" }
  ]
}"""

private fun episodesToText(episodes: List<EpisodeDraft>) = episodes.joinToString("\n") { episode ->
    (listOf("${episode.title} | ${episode.src.orEmpty()}") + episode.subtitles.map { it.src }).joinToString(" ")
}

private fun Movie.drafts() = episodes.map { EpisodeDraft(it.title, it.src, it.stream, it.subtitles.orEmpty()) }

/**
 * Adding or editing a movie. A movie an addon added has no episode URLs to
 * edit — they name a video resolved on play — so its list is shown, not
 * offered as text, and saving leaves it alone. Form and JSON edit the same
 * movie, each handing its state to the other.
 */
@Composable
fun MovieForm(movie: Movie?, defaults: Settings, onSave: (MovieDraft, keepEpisodes: Boolean) -> Unit, onDismiss: () -> Unit) {
    val fromSource = movie?.source != null
    var title by remember { mutableStateOf(movie?.title.orEmpty()) }
    var poster by remember { mutableStateOf(movie?.poster.orEmpty()) }
    var episodesText by remember { mutableStateOf(movie?.let { episodesToText(it.drafts()) }.orEmpty()) }
    var referer by remember { mutableStateOf(movie?.referer.orEmpty()) }
    var adPattern by remember { mutableStateOf(movie?.adPattern.orEmpty()) }
    var skipLeading by remember { mutableStateOf(movie?.skipLeading?.toString().orEmpty()) }
    var skipTrailing by remember { mutableStateOf(movie?.skipTrailing?.toString().orEmpty()) }
    var autoSkip by remember { mutableStateOf(movie?.autoSkip) }
    var json by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }

    fun formDraft(episodes: List<EpisodeDraft>) = MovieDraft(
        title = title.trim(),
        poster = poster.trim(),
        referer = normalizeReferer(referer),
        adPattern = adPattern.trim(),
        skipLeading = skipLeading.trim().toIntOrNull()?.coerceAtLeast(0),
        skipTrailing = skipTrailing.trim().toIntOrNull()?.coerceAtLeast(0),
        autoSkip = autoSkip,
        episodes = episodes,
    )

    fun submitForm() {
        val (lines, skipped) = parseEpisodeLines(episodesText)
        val episodes = lines.map { EpisodeDraft(it.title, it.src, subtitles = it.subtitles) }
        error = when {
            title.isBlank() -> "Give the movie a title."
            !fromSource && episodes.isEmpty() -> "Add at least one episode URL. Delete the movie to remove it entirely."
            !fromSource && skipped > 0 -> "$skipped line(s) are not valid http(s) URLs."
            adPattern.isNotBlank() && compilePattern(adPattern.trim()) == null -> "The ad segment pattern is not a valid regular expression."
            else -> ""
        }
        if (error.isEmpty()) onSave(formDraft(episodes), fromSource)
    }

    fun toggleJson(next: String) {
        error = ""
        if (next == "json" && json == null) {
            val episodes = parseEpisodeLines(episodesText).episodes.map { EpisodeDraft(it.title, it.src, subtitles = it.subtitles) }
            val draft = formDraft(episodes)
            json = if (movie == null && title.isBlank()) "" else movieToJson(
                draft.title, draft.poster, draft.referer, draft.adPattern, draft.skipLeading, draft.skipTrailing, draft.autoSkip, draft.episodes,
            )
        } else if (next == "form" && json != null) {
            val text = json.orEmpty()
            if (text.isBlank()) { json = null; return }
            try {
                val parsed = parseMovieJson(text)
                // The form edits URLs, one per line; an addon's episodes have none to show.
                if (parsed.episodes.any { it.src == null }) throw JsonInputException("Episodes from an addon have no URL to edit — save them from JSON.")
                title = parsed.title; poster = parsed.poster; referer = parsed.referer; adPattern = parsed.adPattern
                skipLeading = parsed.skipLeading?.toString().orEmpty(); skipTrailing = parsed.skipTrailing?.toString().orEmpty()
                autoSkip = parsed.autoSkip
                episodesText = episodesToText(parsed.episodes)
                json = null
            } catch (e: JsonInputException) {
                error = e.message.orEmpty()
            }
        }
    }

    Sheet(if (movie == null) "Add movie" else "Edit movie", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!fromSource) Choices(listOf("form" to "Form", "json" to "JSON"), if (json == null) "form" else "json", ::toggleJson)

            if (json != null) {
                Field("Movie JSON", json.orEmpty(), { json = it }, placeholder = JSON_EXAMPLE, mono = true, singleLine = false, minLines = 10,
                    help = "The shape the library stores and the export writes, so a movie lifted from a backup pastes straight in.")
            } else {
                Field("Title", title, { title = it })
                Field("Poster", poster, { poster = it }, placeholder = "https://example.com/poster.jpg")
                if (fromSource) {
                    Column {
                        SectionLabel("Episodes")
                        Help("${movie.episodes.size} episode(s) from the addon that added this movie. They resolve when played; Refresh on the library card asks the addon for the current list.")
                    }
                } else {
                    Field("Episodes", episodesText, { episodesText = it }, singleLine = false, minLines = 5, mono = true,
                        placeholder = "Pilot | https://example.com/1.m3u8 https://example.com/1.en.vtt",
                        help = "One per line: a URL, or Title | URL. Subtitle URLs may follow the video, separated by spaces.")
                }
                Field("Referer", referer, { referer = it }, placeholder = defaults.referer.ifEmpty { "https://example.com/" },
                    help = "Sent with every playlist and segment request. Blank uses the global default.")
                Field("Ad segment pattern", adPattern, { adPattern = it }, mono = true, placeholder = defaults.adPattern.ifEmpty { "^/ads/.+\\.ts$" },
                    help = "Segments whose URI matches are cut from the playlist — the pattern names the ads, not the content.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Skip intro (s)", skipLeading, { skipLeading = it.filter(Char::isDigit) }, Modifier.weight(1f),
                        placeholder = defaults.skipLeading.toString(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Field("Skip outro (s)", skipTrailing, { skipTrailing = it.filter(Char::isDigit) }, Modifier.weight(1f),
                        placeholder = defaults.skipTrailing.toString(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Help("Blank inherits the global setting; 0 turns it off for this movie.")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Auto skip")
                    Choices(listOf(null to "Default (${if (defaults.autoSkip) "on" else "off"})", true to "On", false to "Off"), autoSkip, { autoSkip = it })
                }
            }
            if (error.isNotEmpty()) Text(error, color = Palette.danger, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            GhostButton("Cancel", onDismiss)
            PrimaryButton(if (movie == null) "Add movie" else "Save", onClick = {
                val text = json
                if (text == null) submitForm() else try {
                    onSave(parseMovieJson(text), false)
                } catch (e: JsonInputException) {
                    error = e.message.orEmpty()
                }
            })
        }
    }
}
