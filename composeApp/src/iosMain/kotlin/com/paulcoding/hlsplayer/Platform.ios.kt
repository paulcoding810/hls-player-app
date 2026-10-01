package com.paulcoding.hlsplayer

import androidx.compose.runtime.Composable

/** iOS has no system back button; the screens' own back actions cover it. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}

@Composable
actual fun FullscreenEffect(enabled: Boolean) {}

@Composable
actual fun KeepScreenOn(enabled: Boolean) {}
