package com.paulcoding.hlsplayer.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The extension's `@theme` tokens: dark-only, red-accented, flat. These are the
 * only colors the UI uses; there is no light theme and no dynamic color.
 */
object Palette {
    val surface = Color(0xFF0B0B0C)
    val panel = Color(0xFF121214)
    val elevated = Color(0xFF1B1B1F)
    val line = Color(0xFF2A2A2F)
    val lineStrong = Color(0xFF3F3F46)
    val ink = Color(0xFFF4F4F5)
    val inkMuted = Color(0xFFA1A1AA)
    val inkFaint = Color(0xFF71717A)
    val primary = Color(0xFFDC2626)
    val primaryHover = Color(0xFFEF4444)
    val primarySoft = Color(0xFF2B0F10)
    val danger = Color(0xFFFCA5A5)
    val dangerSoft = Color(0xFF2B0F10)
    val warn = Color(0xFFFCD34D)
    val warnSoft = Color(0xFF2A2109)
}

/** `rounded-md`, used everywhere. */
val Radius = 6.dp

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.primary,
            onPrimary = Color.White,
            background = Palette.surface,
            onBackground = Palette.ink,
            surface = Palette.panel,
            onSurface = Palette.ink,
            surfaceVariant = Palette.elevated,
            onSurfaceVariant = Palette.inkMuted,
            surfaceContainer = Palette.panel,
            surfaceContainerHigh = Palette.elevated,
            surfaceContainerHighest = Palette.elevated,
            outline = Palette.line,
            outlineVariant = Palette.line,
            error = Palette.danger,
            errorContainer = Palette.dangerSoft,
        ),
        typography = Typography(
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink),
            titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink),
            bodyMedium = TextStyle(fontSize = 14.sp, color = Palette.ink),
            bodySmall = TextStyle(fontSize = 12.sp, color = Palette.inkMuted),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, color = Palette.inkMuted),
        ),
    ) {
        // Text without its own color is ink, never Material's default black.
        CompositionLocalProvider(
            LocalContentColor provides Palette.ink,
            LocalTextStyle provides TextStyle(color = Palette.ink, fontSize = 14.sp),
            content = content,
        )
    }
}
