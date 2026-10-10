package com.paulcoding.hlsplayer

import androidx.compose.runtime.Composable

/** Called from Swift for the edge swipe, iOS having no back button; false when nothing wanted it. */
fun goBack(): Boolean = BackStack.goBack()

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = BackStack.Register(enabled, onBack)

@Composable
actual fun FullscreenEffect(enabled: Boolean, onExit: () -> Unit) {}

@Composable
actual fun KeepScreenOn(enabled: Boolean) {}
