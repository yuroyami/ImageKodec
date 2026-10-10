package io.github.yuroyami.imagekodec

internal fun tiffHeaderWithOffset(offset: Int, littleEndian: Boolean): ByteArray {
    val order = if (littleEndian) 'I' else 'M'
    val encodedOffset = ByteArray(4) { i -> (offset ushr (8 * if (littleEndian) i else 3 - i)).toByte() }
    return byteArrayOf(order.code.toByte(), order.code.toByte()) +
        (if (littleEndian) byteArrayOf(42, 0) else byteArrayOf(0, 42)) + encodedOffset
}

internal fun jpegWithTiffHeader(jpeg: ByteArray, tiff: ByteArray): ByteArray {
    val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
    val length = payload.size + 2
    val app1 = byteArrayOf(-1, 0xE1.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
}

/** Decode a lowercase hex string (as printed by the vector generator) to bytes. */
fun hex(s: String): ByteArray {
    require(s.length % 2 == 0)
    return ByteArray(s.length / 2) { i ->
        ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
    }
}

/** Compact ARGB literal helper: argb(0xFF, 0x12, 0x34, 0x56). */
fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/** Opaque gray pixel. */
fun gray(v: Int): Int = argb(0xFF, v, v, v)
