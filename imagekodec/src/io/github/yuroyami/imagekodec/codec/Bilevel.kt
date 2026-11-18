package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.KiteBitmap

/**
 * [bitmap] as a bilevel page: rows packed MSB first and padded to a byte, a black pixel's bit set
 * when [blackIs1] and clear otherwise. A pixel is black when its luma, laid over white by its alpha,
 * is below half: `77 R + 150 G + 29 B` (BT.601's weights in 8 bits) under 128 * 256, so a
 * transparent pixel is white. Integer arithmetic gives the same page on every target.
 */
internal fun bilevelRows(bitmap: KiteBitmap, blackIs1: Boolean, cancel: (() -> Unit)? = null): ByteArray {
    val w = bitmap.width
    val bytesPerRow = (w + 7) / 8
    val out = ByteArray(bytesPerRow * bitmap.height)
    if (!blackIs1) out.fill(-1)
    val argb = bitmap.argb
    for (y in 0 until bitmap.height) {
        if ((y and 63) == 0) cancel?.invoke()
        val row = y * w
        val base = y * bytesPerRow
        for (x in 0 until w) {
            val p = argb[row + x]
            val alpha = p ushr 24
            val luma = 77 * ((p ushr 16) and 0xFF) + 150 * ((p ushr 8) and 0xFF) + 29 * (p and 0xFF)
            // luma * alpha / 255 + 256 * (255 - alpha), compared without a division.
            val black = luma * alpha + 65280 * (255 - alpha) < 128 * 256 * 255
            if (black) {
                val i = base + (x ushr 3)
                val bit = 0x80 ushr (x and 7)
                out[i] = (if (blackIs1) out[i].toInt() or bit else out[i].toInt() and bit.inv()).toByte()
            }
        }
        // The padding of a white-is-1 row stays clear, as the decoders leave it.
        if (!blackIs1 && (w and 7) != 0) {
            val last = base + bytesPerRow - 1
            out[last] = (out[last].toInt() and (0xFF shl (8 - (w and 7)))).toByte()
        }
    }
    return out
}
