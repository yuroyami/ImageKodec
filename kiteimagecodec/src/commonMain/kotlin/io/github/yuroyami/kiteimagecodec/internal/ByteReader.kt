package io.github.yuroyami.kiteimagecodec.internal

import io.github.yuroyami.kiteimagecodec.ImageDecodeException

/**
 * Bounds-checked forward cursor over a decoder's input. Every read throws
 * [ImageDecodeException] (never IndexOutOfBounds) past the end, so truncated
 * files surface as decode errors with a position, not as crashes.
 *
 * Both endiannesses live here because the formats disagree: BMP/GIF are
 * little-endian, PNG/JPEG network order.
 */
internal class ByteReader(private val data: ByteArray, var pos: Int = 0) {

    val remaining: Int get() = data.size - pos

    fun require(n: Int) {
        if (remaining < n) {
            throw ImageDecodeException(
                "truncated input: need $n more byte(s) at offset $pos, have $remaining",
            )
        }
    }

    fun u8(): Int {
        require(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16le(): Int = u8() or (u8() shl 8)
    fun u32le(): Long = (u16le().toLong()) or (u16le().toLong() shl 16)
    /** BMP height is SIGNED little-endian 32-bit (negative = top-down rows). */
    fun i32le(): Int = u32le().toInt()

    fun u16be(): Int = (u8() shl 8) or u8()
    fun u32be(): Long = (u16be().toLong() shl 16) or u16be().toLong()

    fun bytes(n: Int): ByteArray {
        require(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) {
        require(n)
        pos += n
    }
}
