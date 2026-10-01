package com.paulcoding.hlsplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private val shape = RoundedCornerShape(Radius)

/** The solid red button — at most one per view, as the extension's `buttonClass`. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    ButtonBase(text, onClick, modifier, enabled, icon, background = Palette.primary, border = Color.Transparent, content = Color.White)

/** Bordered and transparent: the default for everything else. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, active: Boolean = false) =
    ButtonBase(
        text, onClick, modifier, enabled, icon,
        background = if (active) Palette.primarySoft else Color.Transparent,
        border = if (active) Palette.primary else Palette.line,
        content = if (active) Palette.primary else Palette.ink,
    )

@Composable
private fun ButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?,
    background: Color,
    border: Color,
    content: Color,
) {
    Row(
        modifier
            .heightIn(min = 40.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = content)
        Text(text, color = content, fontSize = 14.sp, maxLines = 1)
    }
}

/** Square and icon-only; [label] is what a screen reader says. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Palette.inkMuted, size: Int = 40) {
    Box(
        modifier
            .size(size.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size((size * 0.45).dp), tint = tint)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(text, color = Palette.inkMuted, fontSize = 13.sp, modifier = modifier.clip(shape).clickable(onClick = onClick).padding(6.dp))
}

/** A field with a real label above it and help under it, as the extension's forms are built. */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    help: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        SectionLabel(label)
        Spacer(Modifier.size(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboard,
            keyboardActions = KeyboardActions(onAny = { onSubmit?.invoke() }),
            cursorBrush = SolidColor(Palette.primary),
            textStyle = TextStyle(color = Palette.ink, fontSize = if (mono) 12.sp else 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Palette.elevated)
                        .border(1.dp, if (focused) Palette.primary else Palette.line, shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = Palette.inkFaint, fontSize = if (mono) 12.sp else 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
                    }
                    inner()
                }
            },
        )
        if (help != null) Help(help, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Help(text: String, modifier: Modifier = Modifier) = Text(text, color = Palette.inkFaint, fontSize = 12.sp, modifier = modifier)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = modifier)

/** A switch row whose whole width toggles, like the extension's `checkboxRowClass`. */
@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, help: String? = null) {
    Row(
        modifier.fillMaxWidth().clip(shape).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 14.sp, color = Palette.ink)
            if (help != null) Help(help)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.primary,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Palette.elevated,
                uncheckedBorderColor = Palette.lineStrong,
                uncheckedThumbColor = Palette.inkMuted,
            ),
        )
    }
}

/** Choices as a row of pills — the extension's selects, sized for a thumb. */
@Composable
fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label,
                fontSize = 13.sp,
                color = if (active) Palette.primary else Palette.inkMuted,
                modifier = Modifier
                    .clip(shape)
                    .border(1.dp, if (active) Palette.primary else Palette.line, shape)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

enum class Tone { Warn, Danger }

/** Something that just happened: a toast, over the layout, gone by itself. */
data class Notice(val tone: Tone, val message: String)

@Composable
fun Toast(notice: Notice?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (notice == null) return
    LaunchedEffect(notice) {
        delay(if (notice.tone == Tone.Danger) 8000 else 4000)
        onDismiss()
    }
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .clip(shape)
                .background(if (notice.tone == Tone.Danger) Palette.dangerSoft else Palette.warnSoft)
                .border(1.dp, Palette.line, shape)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                notice.message,
                color = if (notice.tone == Tone.Danger) Palette.danger else Palette.warn,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp),
            )
            Spacer(Modifier.width(4.dp))
            IconAction(Icons.Close, "Dismiss", onDismiss, tint = Palette.inkMuted, size = 36)
        }
    }
}

/** Something still true: a band in the flow, there until the condition is gone. */
@Composable
fun Banner(message: String, tone: Tone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (tone == Tone.Danger) Palette.dangerSoft else Palette.warnSoft)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Alert, null, Modifier.size(16.dp).padding(top = 2.dp), tint = if (tone == Tone.Danger) Palette.danger else Palette.warn)
        Text(message, color = if (tone == Tone.Danger) Palette.danger else Palette.warn, fontSize = 14.sp)
    }
}

/**
 * A confirmation. Dismissing it — back, tapping outside — is the negative
 * answer; the affirmative one is the solid button.
 */
@Composable
fun Confirm(title: String, message: String, confirm: String, dismiss: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(shape).background(Palette.panel).border(1.dp, Palette.line, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Text(message, color = Palette.inkMuted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                GhostButton(dismiss, onDismiss)
                PrimaryButton(confirm, onConfirm)
            }
        }
    }
}

/** A panel dialog sized for forms, scrolling inside. */
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Palette.panel)
                .border(1.dp, Palette.line, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconAction(Icons.Close, "Close", onDismiss, size = 36)
            }
            content()
        }
    }
}

/** A text that can hold a URL: one line, cut short, the full value kept. */
@Composable
fun UrlText(text: String, modifier: Modifier = Modifier, color: Color = Palette.inkFaint) =
    Text(text, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

/** A bar of actions under a title, `px-4 py-2` with one bottom edge. */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().background(Palette.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Palette.line))
    }
}
