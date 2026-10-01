package com.paulcoding.hlsplayer.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.paulcoding.hlsplayer.FileExchange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Storage Access Framework, so the backup goes wherever the viewer picks. */
class AndroidFileExchange(private val activity: ComponentActivity) : FileExchange {
    private var created: CompletableDeferred<Uri?>? = null
    private var opened: CompletableDeferred<Uri?>? = null

    private val create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { created?.complete(it) }
    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { opened?.complete(it) }

    override suspend fun export(name: String, text: String): Boolean {
        val pending = CompletableDeferred<Uri?>().also { created = it }
        create.launch(name)
        val uri = pending.await() ?: return false
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
        }
        return true
    }

    override suspend fun import(): String? {
        val pending = CompletableDeferred<Uri?>().also { opened = it }
        open.launch(arrayOf("application/json", "text/plain", "*/*"))
        val uri = pending.await() ?: return null
        return withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }
    }
}
