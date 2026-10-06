package com.paulcoding.hlsplayer.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.paulcoding.hlsplayer.FileExchange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Storage Access Framework, so the backup goes wherever the viewer picks. */
class AndroidFileExchange(private val activity: ComponentActivity) : FileExchange {
    /** Many TVs ship without a documents UI, and launching one there throws. */
    override val available: Boolean = listOf(
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
    ).all { it.resolveActivity(activity.packageManager) != null }

    private var created: CompletableDeferred<Uri?>? = null
    private var opened: CompletableDeferred<Uri?>? = null

    private val create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { created?.complete(it) }
    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { opened?.complete(it) }

    override suspend fun export(name: String, text: String): Boolean {
        val pending = CompletableDeferred<Uri?>().also { created = it }
        try { create.launch(name) } catch (_: ActivityNotFoundException) { return false }
        val uri = pending.await() ?: return false
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
        }
        return true
    }

    override suspend fun import(): String? {
        val pending = CompletableDeferred<Uri?>().also { opened = it }
        try { open.launch(arrayOf("application/json", "text/plain", "*/*")) } catch (_: ActivityNotFoundException) { return null }
        val uri = pending.await() ?: return null
        return withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }
    }
}
