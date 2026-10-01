package com.paulcoding.hlsplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcoding.hlsplayer.LocalDeps
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.playback.compilePattern
import com.paulcoding.hlsplayer.stremio.ManifestSummary
import com.paulcoding.hlsplayer.stremio.StremioException
import com.paulcoding.hlsplayer.stremio.describeManifest
import com.paulcoding.hlsplayer.stremio.pluginToJson
import com.paulcoding.hlsplayer.ui.Choices
import com.paulcoding.hlsplayer.ui.Field
import com.paulcoding.hlsplayer.ui.GhostButton
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.PrimaryButton
import com.paulcoding.hlsplayer.ui.Sheet
import com.paulcoding.hlsplayer.ui.SwitchRow
import com.paulcoding.hlsplayer.util.booleanValue
import com.paulcoding.hlsplayer.util.field
import com.paulcoding.hlsplayer.util.obj
import com.paulcoding.hlsplayer.util.parseJson
import com.paulcoding.hlsplayer.util.string
import kotlinx.coroutines.launch

private const val JSON_EXAMPLE = """{
  "name": "My addon",
  "url": "https://addon.example.com/manifest.json"
}"""

private sealed interface TestState {
    data object Running : TestState
    data class Done(val summary: ManifestSummary) : TestState
    data class Failed(val message: String) : TestState
}

/**
 * An addon is a manifest URL; the protocol supplies the rest. Test reads the
 * manifest, which is what says whether it can be searched and played here.
 */
@Composable
fun AddonForm(plugin: Plugin?, onSave: (Plugin) -> Unit, onDismiss: () -> Unit) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(plugin ?: Plugin(id = "")) }
    var json by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var test by remember { mutableStateOf<TestState?>(null) }

    fun readJson(text: String): Plugin {
        val obj = try { parseJson(text).obj() } catch (_: Exception) { null } ?: throw IllegalArgumentException("That is not valid JSON, or not a single addon object.")
        val name = obj.field("name").string().trim()
        if (name.isEmpty()) throw IllegalArgumentException("Give the addon a \"name\".")
        return draft.copy(
            name = name,
            url = obj.field("url").string().trim(),
            referer = obj.field("referer").string(),
            adPattern = obj.field("adPattern").string(),
            enabled = obj.field("enabled").booleanValue() != false,
        )
    }

    fun runTest() = scope.launch {
        error = ""
        test = TestState.Running
        test = try {
            // An edited URL must not be answered from the manifest read before it.
            deps.client.forgetManifest(draft)
            val summary = describeManifest(deps.client.readManifest(draft))
            // The addon names itself; a blank name is filled rather than left to the user.
            if (draft.name.isBlank() && summary.name.isNotEmpty()) draft = draft.copy(name = summary.name)
            TestState.Done(summary)
        } catch (e: StremioException) {
            TestState.Failed(e.message.orEmpty())
        }
    }

    fun save() {
        val result = json?.let {
            try { readJson(it) } catch (e: IllegalArgumentException) { error = e.message.orEmpty(); return }
        } ?: draft
        error = when {
            result.url.isBlank() -> "Give the addon a manifest URL."
            result.name.isBlank() -> "Give the addon a name — Test can fill it in."
            result.adPattern.isNotBlank() && compilePattern(result.adPattern.trim()) == null -> "The ad segment pattern is not a valid regular expression."
            else -> ""
        }
        if (error.isEmpty()) onSave(result.copy(name = result.name.trim(), url = result.url.trim(), adPattern = result.adPattern.trim()))
    }

    Sheet(if (plugin == null) "Add Stremio addon" else "Edit Stremio addon", onDismiss) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Choices(listOf("form" to "Form", "json" to "JSON"), if (json == null) "form" else "json", { mode ->
                error = ""
                if (mode == "json" && json == null) json = if (plugin == null && draft.url.isBlank()) "" else pluginToJson(draft)
                else if (mode == "form" && json != null) {
                    if (json.isNullOrBlank()) json = null
                    else try { draft = readJson(json!!); json = null } catch (e: IllegalArgumentException) { error = e.message.orEmpty() }
                }
            })
            if (json != null) {
                Field("Addon JSON", json.orEmpty(), { json = it }, mono = true, singleLine = false, minLines = 8, placeholder = JSON_EXAMPLE,
                    help = "The same shape the export writes, so addons can be shared.")
            } else {
                Field("Manifest URL", draft.url, { draft = draft.copy(url = it) }, mono = true, placeholder = "https://addon.example.com/manifest.json",
                    help = "The addon's install link; a stremio:// one works too. It must offer streams over HTTP — torrent-only addons have nothing this player can open.")
                Field("Name", draft.name, { draft = draft.copy(name = it) }, placeholder = "Torrentio",
                    help = "Left blank, Test fills in the name the addon gives itself.")
                Field("Referer", draft.referer, { draft = draft.copy(referer = it) }, placeholder = "https://example.com/",
                    help = "Only for streams that need one and do not say so — an addon can name its own per stream, and that wins. Copied onto movies added from it.")
                Field("Ad segment pattern", draft.adPattern, { draft = draft.copy(adPattern = it) }, mono = true, placeholder = "^/ads/.+\\.ts$",
                    help = "Also copied onto movies added from it. Segments whose URI matches are cut from the playlist.")
                SwitchRow("Search and browse this addon", draft.enabled, { draft = draft.copy(enabled = it) })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("Test", { runTest() }, enabled = test != TestState.Running)
                    when (val state = test) {
                        TestState.Running -> Text("Reading the manifest…", color = Palette.inkFaint, fontSize = 12.sp)
                        is TestState.Failed -> Text(state.message, color = Palette.danger, fontSize = 12.sp)
                        is TestState.Done -> Column {
                            val s = state.summary
                            Text("${s.name.ifEmpty { "Unnamed addon" }}${if (s.version.isNotEmpty()) " v${s.version}" else ""} · ${s.catalogs} catalog(s), ${s.searchable} searchable",
                                color = Palette.inkMuted, fontSize = 12.sp)
                            if (s.missing.isNotEmpty()) Text(
                                "Offers no ${s.missing.joinToString(" or ")} — " +
                                    if ("stream" in s.missing) "what it lists cannot be played here." else "it will be missing from the Sources screen.",
                                color = Palette.warn, fontSize = 12.sp,
                            )
                        }
                        null -> {}
                    }
                }
            }
            if (error.isNotEmpty()) Text(error, color = Palette.danger, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            GhostButton("Cancel", onDismiss)
            PrimaryButton(if (plugin == null) "Add addon" else "Save", ::save)
        }
    }
}
