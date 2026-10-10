package com.paulcoding.hlsplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The extension's icon set, path for path: 24×24, stroked at 1.5 with round
 * caps and joins, no fill. Tinted by the caller, never colored here.
 * Generated from `src/components/icons.jsx`; circles and rects became paths.
 */
private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object Icons {
    val Back: ImageVector by lazy { icon("Back", "M15 5l-7 7 7 7") }
    val Prev: ImageVector by lazy { icon("Prev", "M19 5v14l-10-7 10-7Z", "M5 5v14") }
    val Next: ImageVector by lazy { icon("Next", "M5 5v14l10-7-10-7Z", "M19 5v14") }
    val Play: ImageVector by lazy { icon("Play", "M7 4.5v15l12-7.5-12-7.5Z") }
    val Close: ImageVector by lazy { icon("Close", "M6 6l12 12", "M18 6 6 18") }
    val Minus: ImageVector by lazy { icon("Minus", "M5 12h14") }
    val Plus: ImageVector by lazy { icon("Plus", "M12 5v14", "M5 12h14") }
    val Playlist: ImageVector by lazy { icon("Playlist", "M8 6h13", "M8 12h13", "M8 18h13", "M3.5 6h.01", "M3.5 12h.01", "M3.5 18h.01") }
    val Settings: ImageVector by lazy { icon("Settings", "M4 7h16", "M4 17h16", "M6.75,7a2.25,2.25 0 1,0 4.5,0a2.25,2.25 0 1,0 -4.5,0Z", "M12.75,17a2.25,2.25 0 1,0 4.5,0a2.25,2.25 0 1,0 -4.5,0Z") }
    val Trash: ImageVector by lazy { icon("Trash", "M4 7h16", "M9 7V5h6v2", "M6 7l1 13h10l1-13", "M10 11v6", "M14 11v6") }
    val Alert: ImageVector by lazy { icon("Alert", "M12 4l9 16H3l9-16Z", "M12 10v4", "M12 17.5h.01") }
    val Pause: ImageVector by lazy { icon("Pause", "M9 5v14", "M15 5v14") }
    val Volume: ImageVector by lazy { icon("Volume", "M4 10v4h3l4 3V7L7 10H4Z", "M15.5 9.5a3.5 3.5 0 0 1 0 5", "M18 7a7 7 0 0 1 0 10") }
    val VolumeMuted: ImageVector by lazy { icon("VolumeMuted", "M4 10v4h3l4 3V7L7 10H4Z", "M15 10l4 4", "M19 10l-4 4") }
    val Fullscreen: ImageVector by lazy { icon("Fullscreen", "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5") }
    val FullscreenExit: ImageVector by lazy { icon("FullscreenExit", "M9 4v5H4", "M15 4v5h5", "M9 20v-5H4", "M15 20v-5h5") }
    val Library: ImageVector by lazy { icon("Library", "M5,4h4a1.5,1.5 0 0 1 1.5,1.5v4a1.5,1.5 0 0 1 -1.5,1.5h-4a1.5,1.5 0 0 1 -1.5,-1.5v-4a1.5,1.5 0 0 1 1.5,-1.5Z", "M15,4h4a1.5,1.5 0 0 1 1.5,1.5v4a1.5,1.5 0 0 1 -1.5,1.5h-4a1.5,1.5 0 0 1 -1.5,-1.5v-4a1.5,1.5 0 0 1 1.5,-1.5Z", "M5,13h4a1.5,1.5 0 0 1 1.5,1.5v4a1.5,1.5 0 0 1 -1.5,1.5h-4a1.5,1.5 0 0 1 -1.5,-1.5v-4a1.5,1.5 0 0 1 1.5,-1.5Z", "M15,13h4a1.5,1.5 0 0 1 1.5,1.5v4a1.5,1.5 0 0 1 -1.5,1.5h-4a1.5,1.5 0 0 1 -1.5,-1.5v-4a1.5,1.5 0 0 1 1.5,-1.5Z") }
    val Edit: ImageVector by lazy { icon("Edit", "M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17v3Z", "M14.5 7.5l2 2") }
    val Search: ImageVector by lazy { icon("Search", "M4.5,11a6.5,6.5 0 1,0 13,0a6.5,6.5 0 1,0 -13,0Z", "M16 16l3.5 3.5") }
    val Layers: ImageVector by lazy { icon("Layers", "M12 4l8 4-8 4-8-4 8-4Z", "M4 12l8 4 8-4", "M4 16l8 4 8-4") }
    val Copy: ImageVector by lazy { icon("Copy", "M11,9h7a2,2 0 0 1 2,2v7a2,2 0 0 1 -2,2h-7a2,2 0 0 1 -2,-2v-7a2,2 0 0 1 2,-2Z", "M15 5.5A1.5 1.5 0 0 0 13.5 4H6a2 2 0 0 0-2 2v7.5A1.5 1.5 0 0 0 5.5 15") }
    val Check: ImageVector by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7") }
    val Refresh: ImageVector by lazy { icon("Refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4v4.5H15") }
    val Download: ImageVector by lazy { icon("Download", "M12 4v11", "M8 11.5l4 4 4-4", "M5 19h14") }
    val Upload: ImageVector by lazy { icon("Upload", "M12 15V4", "M8 7.5l4-4 4 4", "M5 19h14") }
    val Film: ImageVector by lazy { icon("Film", "M5,5h14a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2v-10a2,2 0 0 1 2,-2Z", "M8 5v14", "M16 5v14", "M3 12h18") }
}
