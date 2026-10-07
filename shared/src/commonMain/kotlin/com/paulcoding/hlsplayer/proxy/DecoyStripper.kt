package com.paulcoding.hlsplayer.proxy

import com.paulcoding.hlsplayer.playback.PNG_SIGNATURE
import com.paulcoding.hlsplayer.playback.SCAN_LIMIT
import com.paulcoding.hlsplayer.playback.decoyPrefixLength

/**
 * Takes a segment off a PNG it is wrapped in while the bytes stream past. Only
 * the head is held back — until the signature rules it out, or the scan window
 * fills — and everything after it flows straight through.
 */
class DecoyStripper {
    private var head = ByteArray(0)
    private var decided = false

    /** Bytes ready to send for this [chunk]; possibly none while the head is still being read. */
    fun push(chunk: ByteArray): ByteArray {
        if (decided) return chunk
        head += chunk
        val signatureRuledOut = head.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.any { head[it] != PNG_SIGNATURE[it] }
        return if (signatureRuledOut || head.size >= SCAN_LIMIT) release() else ByteArray(0)
    }

    /** Whatever is left once the upstream body has ended. */
    fun finish(): ByteArray = if (decided) ByteArray(0) else release()

    private fun release(): ByteArray {
        decided = true
        val prefix = decoyPrefixLength(head)
        val out = head.copyOfRange(prefix, head.size)
        head = ByteArray(0)
        return out
    }
}
