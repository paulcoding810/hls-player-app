package com.paulcoding.hlsplayer.playback

/**
 * Some hosts disguise segments as images: a small valid PNG with the real
 * MPEG-TS or fMP4 payload after it. A demuxer sees the PNG signature and gives
 * up, so the prefix has to come off before the bytes reach it.
 */

val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

private const val TS_SYNC_BYTE: Byte = 0x47
private const val TS_PACKET_SIZE = 188

/** Box types that can open an fMP4 segment. */
private val MP4_BOX_TYPES = listOf("ftyp", "styp", "moof", "sidx").map { it.encodeToByteArray() }

/** A decoy is a header, so the payload starts well inside this window. */
const val SCAN_LIMIT = 64 * 1024

private fun ByteArray.startsWith(signature: ByteArray, offset: Int = 0, size: Int = this.size): Boolean =
    offset + signature.size <= size && signature.indices.all { this[offset + it] == signature[it] }

/**
 * Where real media begins in the first [size] bytes, or -1. Three sync bytes a
 * packet apart keep a stray 0x47 inside the PNG from matching.
 */
fun findPayloadStart(bytes: ByteArray, size: Int = bytes.size): Int {
    val limit = minOf(size, SCAN_LIMIT)
    for (offset in 0 until limit) {
        val ts = offset + TS_PACKET_SIZE * 2 < size &&
            bytes[offset] == TS_SYNC_BYTE &&
            bytes[offset + TS_PACKET_SIZE] == TS_SYNC_BYTE &&
            bytes[offset + TS_PACKET_SIZE * 2] == TS_SYNC_BYTE
        if (ts) return offset
        // An fMP4 box is a 4-byte size followed by its type.
        if (offset >= 4 && MP4_BOX_TYPES.any { bytes.startsWith(it, offset, size) }) return offset - 4
    }
    return -1
}

fun isPngWrapped(bytes: ByteArray, size: Int = bytes.size): Boolean =
    size > PNG_SIGNATURE.size && bytes.startsWith(PNG_SIGNATURE, 0, size)

/**
 * How many leading bytes to drop: 0 unless the buffer is PNG-wrapped and a
 * payload is found, so an unrecognised buffer fails the way it would have.
 */
fun decoyPrefixLength(bytes: ByteArray, size: Int = bytes.size): Int {
    if (!isPngWrapped(bytes, size)) return 0
    val start = findPayloadStart(bytes, size)
    return if (start in 1 until size) start else 0
}
