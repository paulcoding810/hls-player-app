package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.store.AppData
import com.paulcoding.hlsplayer.store.JsonStore
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** A fresh, isolated store with a clock and ids the test controls. */
class Fixture {
    val fileSystem = FakeFileSystem()
    var time = 1_000L
    private var counter = 0
    val store = JsonStore(fileSystem, "/data".toPath(), Dispatchers.Unconfined)
    val data = AppData(store, now = { time }, id = { "id-${++counter}" })

    /** The same disk opened again, as the next launch would. */
    fun reopen() = AppData(JsonStore(fileSystem, "/data".toPath(), Dispatchers.Unconfined), now = { time })
}
