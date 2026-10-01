package com.paulcoding.hlsplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcoding.hlsplayer.LocalDeps
import com.paulcoding.hlsplayer.LocalNavigator
import com.paulcoding.hlsplayer.backup.BackupException
import com.paulcoding.hlsplayer.backup.applyBackup
import com.paulcoding.hlsplayer.backup.backupFileName
import com.paulcoding.hlsplayer.backup.buildBackup
import com.paulcoding.hlsplayer.backup.readBackup
import com.paulcoding.hlsplayer.model.Plugin
import com.paulcoding.hlsplayer.settings.SUBTITLE_POSITIONS
import com.paulcoding.hlsplayer.settings.SUBTITLE_SIZES
import com.paulcoding.hlsplayer.settings.Settings
import com.paulcoding.hlsplayer.stremio.pluginToJson
import com.paulcoding.hlsplayer.ui.Choices
import com.paulcoding.hlsplayer.ui.Confirm
import com.paulcoding.hlsplayer.ui.Field
import com.paulcoding.hlsplayer.ui.GhostButton
import com.paulcoding.hlsplayer.ui.Help
import com.paulcoding.hlsplayer.ui.IconAction
import com.paulcoding.hlsplayer.ui.Icons
import com.paulcoding.hlsplayer.ui.Palette
import com.paulcoding.hlsplayer.ui.Radius
import com.paulcoding.hlsplayer.ui.SectionLabel
import com.paulcoding.hlsplayer.ui.SwitchRow
import com.paulcoding.hlsplayer.ui.TopBar
import com.paulcoding.hlsplayer.ui.UrlText
import kotlinx.coroutines.launch

/** The extension's options page: global defaults, subtitles, addons and the backup. */
@Composable
fun SettingsScreen() {
    val deps = LocalDeps.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings by deps.data.settings.collectAsState()
    val plugins by deps.data.plugins.collectAsState()
    var editing by remember { mutableStateOf<Plugin?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Plugin?>(null) }

    fun update(change: (Settings) -> Settings) = scope.launch { deps.data.updateSettings(change) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Playback")
            // Fields hold raw text while typing; the stored value is sanitized on write.
            var referer by remember { mutableStateOf(settings.referer) }
            Field("Default Referer", referer, { referer = it; update { s -> s.copy(referer = it) } }, placeholder = "https://example.com/",
                help = "Sent with movies that do not set their own.")
            var adPattern by remember { mutableStateOf(settings.adPattern) }
            Field("Default ad segment pattern", adPattern, { adPattern = it; update { s -> s.copy(adPattern = it) } }, mono = true, placeholder = "^/ads/.+\\.ts$",
                help = "Segments whose URI matches are cut from the playlist. Written for the extension's engine; the common syntax is shared.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var intro by remember { mutableStateOf(settings.skipLeading.takeIf { it > 0 }?.toString().orEmpty()) }
                var outro by remember { mutableStateOf(settings.skipTrailing.takeIf { it > 0 }?.toString().orEmpty()) }
                Field("Skip intro (s)", intro, { intro = it.filter(Char::isDigit); update { s -> s.copy(skipLeading = intro.toIntOrNull() ?: 0) } }, Modifier.weight(1f),
                    placeholder = "0", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                Field("Skip outro (s)", outro, { outro = it.filter(Char::isDigit); update { s -> s.copy(skipTrailing = outro.toIntOrNull() ?: 0) } }, Modifier.weight(1f),
                    placeholder = "0", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            SwitchRow("Skip automatically", settings.autoSkip, { on -> update { it.copy(autoSkip = on) } },
                help = "Off shows a Skip button instead. An outro still counts down so it can be declined.")
            SwitchRow("Autoplay", settings.autoplay, { on -> update { it.copy(autoplay = on) } })
            SwitchRow("Open .m3u8 and .mpd links", settings.grabLinks, { on -> update { it.copy(grabLinks = on) } },
                help = "Links handed to the app play straight away, outside the library.")

            Divider()
            SectionLabel("Subtitles")
            SwitchRow("Turn subtitles on when a stream has them", settings.subtitlesOn, { on -> update { it.copy(subtitlesOn = on) } })
            var lang by remember { mutableStateOf(settings.subtitleLang) }
            Field("Preferred language", lang, { lang = it; update { s -> s.copy(subtitleLang = it) } }, placeholder = "en",
                help = "Matched on the start of the track's language, so en finds en-GB and eng. Blank picks the first track.")
            Text("Size", fontSize = 13.sp, color = Palette.inkMuted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Choices(SUBTITLE_SIZES.map { it.value to it.label }, settings.subtitleSize, { size -> update { it.copy(subtitleSize = size) } })
            }
            Text("Position", fontSize = 13.sp, color = Palette.inkMuted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Choices(SUBTITLE_POSITIONS.map { it.value to it.label }, settings.subtitlePosition, { spot -> update { it.copy(subtitlePosition = spot) } })
            }
            SwitchRow("Shaded box behind the text", settings.subtitleBackground, { on -> update { it.copy(subtitleBackground = on) } })

            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Sources", Modifier.weight(1f))
                GhostButton("Add addon", { adding = true }, icon = Icons.Plus)
            }
            Help("Each source is a Stremio addon, added by its manifest URL. Enabled ones are searched and browsed on the Sources screen; an episode's streams are asked of the addon that listed it, so it must offer HTTP streams, not only torrents.")
            if (plugins.isEmpty()) Text("No addons yet.", color = Palette.inkMuted, fontSize = 14.sp)
            else Column(Modifier.clip(RoundedCornerShape(Radius)).border(1.dp, Palette.line, RoundedCornerShape(Radius))) {
                plugins.forEachIndexed { index, plugin ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            plugin.enabled,
                            { on -> scope.launch { deps.data.updatePlugin(plugin.id) { it.copy(enabled = on) } } },
                            colors = SwitchDefaults.colors(checkedTrackColor = Palette.primary, checkedThumbColor = Color.White,
                                uncheckedTrackColor = Palette.elevated, uncheckedBorderColor = Palette.lineStrong, uncheckedThumbColor = Palette.inkMuted),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(plugin.name, fontSize = 14.sp, maxLines = 1)
                            UrlText(plugin.url)
                        }
                        IconAction(Icons.Copy, "Copy ${plugin.name} as JSON", {
                            clipboard.setText(AnnotatedString(pluginToJson(plugin)))
                            navigator.warn("Copied “${plugin.name}” as JSON.")
                        }, size = 36)
                        IconAction(Icons.Edit, "Edit ${plugin.name}", { editing = plugin }, size = 36)
                        IconAction(Icons.Trash, "Delete ${plugin.name}", { removing = plugin }, size = 36)
                    }
                }
            }

            Divider()
            SectionLabel("Data")
            Help("The export holds the library, the addons, the settings and every watch position — the same file the browser extension writes and reads. Importing merges it in: a movie or addon already here is updated, the rest are added.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Export", {
                    scope.launch {
                        if (deps.files.export(backupFileName(), buildBackup(deps.data))) navigator.warn("Exported the library.")
                    }
                }, icon = Icons.Download)
                GhostButton("Import", {
                    scope.launch {
                        val text = deps.files.import() ?: return@launch
                        try {
                            val result = applyBackup(deps.data, readBackup(text))
                            navigator.warn("Imported: ${result.added} new movie(s), ${result.updated} updated, ${result.positions} watch position(s) and ${result.plugins} addon(s).")
                        } catch (e: BackupException) {
                            navigator.fail(e.message.orEmpty())
                        }
                    }
                }, icon = Icons.Upload)
            }
            Help("HLS Player ${deps.version}")
        }
    }

    if (adding || editing != null) {
        AddonForm(editing, onDismiss = { adding = false; editing = null }, onSave = { saved ->
            scope.launch {
                val current = editing
                if (current == null) deps.data.addPlugin(saved) else deps.data.updatePlugin(current.id) { saved.copy(id = it.id) }
                deps.client.forgetManifest(saved)
                adding = false
                editing = null
            }
        })
    }

    removing?.let { plugin ->
        Confirm("Delete “${plugin.name}”?", "Movies added from it stay, but their episodes can no longer be played or refreshed.", "Delete", "Keep it",
            onConfirm = { scope.launch { deps.data.removePlugin(plugin.id); removing = null } }, onDismiss = { removing = null })
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
