package com.paulcoding.hlsplayer.util

/** `1:05` / `1:02:03`; live or unknown durations render as `--:--`. */
fun formatTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "--:--"
    val total = seconds.toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$secs" else "$minutes:$secs"
}
