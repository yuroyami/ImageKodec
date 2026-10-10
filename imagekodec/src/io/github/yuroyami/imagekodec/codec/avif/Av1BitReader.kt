package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException

/**
 * The AV1 specification's bit-level descriptors (section 4.10): `f(n)`, `uvlc()`, `le(n)`,
 * `leb128()`, `su(n)` and `ns(n)`, over [data] from bit [position] to byte [end]. A read past
 * [end] fails as damaged data instead of reading another OBU's bytes.
 */
internal class Av1BitReader(val data: ByteArray, start: Int, val end: Int) {
    /** The bit position, from the start of [data]. */
    var position: Long = start.toLong() * 8

    val bytePosition: Int get() = (position ushr 3).toInt()

    fun bit(): Int {
        val byte = (position ushr 3).toInt()
        if (byte >= end) throw ImageDecodeException("AV1: header cut off at byte $byte")
        val b = (data[byte].toInt() shr (7 - (position and 7).toInt())) and 1
        position++
        return b
    }

    /** `f(n)`, high bit first, for n up to 32. */
    fun f(n: Int): Int {
        var x = 0L
        for (i in 0 until n) x = (x shl 1) or bit().toLong()
        return x.toInt()
    }

    fun flag(): Boolean = bit() == 1

    /** `f(32)` as a non-negative Long. */
    fun f32(): Long {
        var x = 0L
        for (i in 0 until 32) x = (x shl 1) or bit().toLong()
        return x
    }

    fun uvlc(): Long {
        var leadingZeros = 0
        while (bit() == 0) {
            leadingZeros++
            if (leadingZeros > 40) throw ImageDecodeException("AV1: uvlc() with more than 32 leading zeros")
        }
        if (leadingZeros >= 32) return (1L shl 32) - 1
        var value = 0L
        for (i in 0 until leadingZeros) value = (value shl 1) or bit().toLong()
        return value + (1L shl leadingZeros) - 1
    }

    fun le(n: Int): Long {
        var t = 0L
        for (i in 0 until n) t = t or (f(8).toLong() shl (i * 8))
        return t
    }

    fun leb128(): Long {
        var value = 0L
        for (i in 0 until 8) {
            val b = f(8)
            value = value or ((b and 0x7F).toLong() shl (i * 7))
            if (b and 0x80 == 0) break
        }
        if (value > 0xFFFFFFFFL) throw ImageDecodeException("AV1: leb128() value above 2^32 - 1")
        return value
    }

    fun su(n: Int): Int {
        var value = f(n)
        val signMask = 1 shl (n - 1)
        if (value and signMask != 0) value -= 2 * signMask
        return value
    }

    fun ns(n: Int): Int {
        val w = floorLog2(n) + 1
        val m = (1 shl w) - n
        val v = f(w - 1)
        if (v < m) return v
        val extraBit = bit()
        return (v shl 1) - m + extraBit
    }

    fun byteAlignment() {
        while (position and 7 != 0L) bit()
    }

    companion object {
        fun floorLog2(x: Int): Int = 31 - x.countLeadingZeroBits()

        /** CeilLog2 of the specification: 0 for x below 2. */
        fun ceilLog2(x: Int): Int {
            if (x < 2) return 0
            var i = 1
            var p = 2
            while (p < x) {
                i++
                p = p shl 1
            }
            return i
        }
    }
}
