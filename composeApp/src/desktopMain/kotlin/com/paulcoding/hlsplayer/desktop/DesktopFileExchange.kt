package com.paulcoding.hlsplayer.desktop

import com.paulcoding.hlsplayer.FileExchange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The system's own save and open dialogs. */
class DesktopFileExchange(private val owner: () -> Frame?) : FileExchange {
    override val available = true

    private suspend fun pick(mode: Int, name: String? = null): File? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(owner(), if (mode == FileDialog.SAVE) "Export backup" else "Import backup", mode)
        if (name != null) dialog.file = name
        dialog.isVisible = true
        val file = dialog.file ?: return@withContext null
        File(dialog.directory, file)
    }

    override suspend fun export(name: String, text: String): Boolean {
        val file = pick(FileDialog.SAVE, name) ?: return false
        withContext(Dispatchers.IO) { file.writeText(text) }
        return true
    }

    override suspend fun import(): String? {
        val file = pick(FileDialog.LOAD) ?: return null
        return withContext(Dispatchers.IO) { file.readText() }
    }
}
