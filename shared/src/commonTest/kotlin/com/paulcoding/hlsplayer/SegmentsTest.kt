package com.paulcoding.hlsplayer

import com.paulcoding.hlsplayer.playback.PNG_SIGNATURE
import com.paulcoding.hlsplayer.playback.decoyPrefixLength
import com.paulcoding.hlsplayer.playback.findPayloadStart
import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentsTest {
    private fun ts(packets: Int) = ByteArray(188 * packets) { if (it % 188 == 0) 0x47 else 0x11 }

    /** A PNG header whose body holds a lone 0x47, which must not be taken for a sync byte. */
    private val png = PNG_SIGNATURE + byteArrayOf(0, 0, 0, 13, 0x47, 0x48, 0x44, 0x52) + ByteArray(40) { 7 }

    @Test fun findsTheTsPayloadBehindAPng() {
        val wrapped = png + ts(4)
        assertEquals(png.size, findPayloadStart(wrapped))
        assertEquals(png.size, decoyPrefixLength(wrapped))
    }

    @Test fun findsAnFmp4PayloadBehindAPng() {
        val box = byteArrayOf(0, 0, 0, 24) + "ftyp".encodeToByteArray() + ByteArray(16)
        assertEquals(png.size, decoyPrefixLength(png + box))
    }

    @Test fun leavesAnUnwrappedSegmentAlone() {
        assertEquals(0, decoyPrefixLength(ts(4)))
    }

    @Test fun leavesAPngWithNoPayloadAlone() {
        // Falling back to the original bytes lets the demuxer fail as it would have.
        assertEquals(0, decoyPrefixLength(png))
    }

    @Test fun respectsALengthShorterThanTheBuffer() {
        val wrapped = png + ts(4)
        val buffer = wrapped + ByteArray(1000)
        assertEquals(png.size, decoyPrefixLength(buffer, wrapped.size))
    }
}
