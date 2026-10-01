package com.paulcoding.hlsplayer.store

import com.paulcoding.hlsplayer.util.AppJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okio.FileSystem
import okio.Path

/**
 * One JSON document per namespace — `library`, `settings`, `progress`,
 * `plugins` — the four `chrome.storage.local` keys the extension uses, so a
 * backup is a straight dump of them. Every change goes through one lock: the
 * extension's pages read, modify and write the whole document unguarded, and
 * two of them at once can lose a write.
 */
class JsonStore(
    private val fileSystem: FileSystem,
    private val directory: Path,
    /** Where the file work runs: off the main thread in the app, inline in tests. */
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Mutex()

    init {
        fileSystem.createDirectories(directory)
    }

    private fun pathOf(namespace: String) = directory / "$namespace.json"

    /** The stored document, or null when there is none or it cannot be read. */
    fun read(namespace: String): JsonElement? {
        val path = pathOf(namespace)
        if (!fileSystem.exists(path)) return null
        return try {
            AppJson.parseToJsonElement(fileSystem.read(path) { readUtf8() })
        } catch (_: Exception) {
            null
        }
    }

    /** Written beside the target and moved over it, so a crash leaves the old file whole. */
    private fun write(namespace: String, value: JsonElement) {
        val target = pathOf(namespace)
        val staging = directory / "$namespace.json.tmp"
        fileSystem.write(staging) { writeUtf8(AppJson.encodeToString(JsonElement.serializer(), value)) }
        fileSystem.atomicMove(staging, target)
    }

    /** Reads, lets [change] decide the next value (null leaves it alone), writes it back. */
    suspend fun <T> update(namespace: String, change: (JsonElement?) -> Pair<JsonElement?, T>): T = lock.withLock {
        withContext(io) {
            val (next, result) = change(read(namespace))
            if (next != null) write(namespace, next)
            result
        }
    }

    suspend fun replace(namespace: String, value: JsonElement) = lock.withLock { withContext(io) { write(namespace, value) } }

    /** Several namespaces changed as one step, as an import does. */
    suspend fun <T> transaction(block: Transaction.() -> T): T = lock.withLock { withContext(io) { Transaction().block() } }

    inner class Transaction {
        fun read(namespace: String) = this@JsonStore.read(namespace)
        fun write(namespace: String, value: JsonElement) = this@JsonStore.write(namespace, value)
    }
}
