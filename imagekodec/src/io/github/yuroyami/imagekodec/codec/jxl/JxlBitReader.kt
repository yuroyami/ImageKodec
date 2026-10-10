// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_bit_reader.h, fields.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import io.github.yuroyami.imagekodec.ImageDecodeException

internal fun jxlFail(message: String): Nothing = throw ImageDecodeException("JPEG XL: $message")

/** A field distribution that is the constant [x], as libjxl's `Val`. */
internal fun fv(x: Int): Int = x or Int.MIN_VALUE

/** A field distribution of [bits] raw bits plus [offset], as libjxl's `BitsOffset`. */
internal fun fo(bits: Int, offset: Int): Int = ((bits - 1) and 0x1F) or (offset shl 5)

/** A field distribution of [bits] raw bits, as libjxl's `Bits`. */
internal fun fb(bits: Int): Int = fo(bits, 0)

/**
 * Reads bits from bytes [start] until [end] of [data], least significant bit first, as
 * every part of a JPEG XL codestream is packed.
 *
 * [peek] gives zeros past the end, because a prefix code looks ahead by its longest code;
 * taking a bit past the end is an [ImageDecodeException], so no loop outruns its data.
 */
internal class JxlBitReader(private val data: ByteArray, private val start: Int, private val end: Int) {
    private var pos = start
    private var buf = 0L
    private var count = 0

    /** Bits taken so far. */
    val bitsConsumed: Long get() = (pos - start).toLong() * 8 - count

    /** The index in the data of the next byte, once [zeroPadToByte] has aligned the reader. */
    val bytePosition: Int get() = pos - (count ushr 3)

    /** Bits left before the end. */
    val bitsLeft: Long get() = (end - pos).toLong() * 8 + count

    /** True once a read has asked for more bits than the data holds. */
    var ranOut: Boolean = false
        private set

    private fun short(what: String): Nothing {
        ranOut = true
        jxlFail("data ends inside $what")
    }

    private fun refill() {
        while (count <= 56 && pos < end) {
            buf = buf or ((data[pos++].toLong() and 0xFF) shl count)
            count += 8
        }
    }

    /** The next [n] bits, 32 at most, without taking them. */
    fun peek(n: Int): Int {
        if (count < n) refill()
        return (buf and ((1L shl n) - 1)).toInt()
    }

    fun consume(n: Int) {
        if (n > count) short("a $n-bit field")
        buf = buf ushr n
        count -= n
    }

    /** Takes [n] bits, 32 at most. */
    fun bits(n: Int): Int {
        if (count < n) {
            refill()
            if (count < n) short("a $n-bit field")
        }
        val v = (buf and ((1L shl n) - 1)).toInt()
        buf = buf ushr n
        count -= n
        return v
    }

    fun bool(): Boolean = bits(1) != 0

    /** A value coded with one of four distributions, chosen by two bits. */
    fun u32(d0: Int, d1: Int, d2: Int, d3: Int): Int {
        val d = when (bits(2)) {
            0 -> d0
            1 -> d1
            2 -> d2
            else -> d3
        }
        return if (d < 0) d and Int.MAX_VALUE else bits((d and 0x1F) + 1) + ((d ushr 5) and 0x3FFFFFF)
    }

    fun u64(): Long {
        when (bits(2)) {
            0 -> return 0
            1 -> return 1L + bits(4)
            2 -> return 17L + bits(8)
        }
        var result = bits(12).toLong()
        var shift = 12
        while (bits(1) == 1) {
            if (shift == 60) {
                result = result or (bits(4).toLong() shl shift)
                break
            }
            result = result or (bits(8).toLong() shl shift)
            shift += 8
        }
        return result
    }

    /** A half-precision float. Infinity and NaN are not valid in a codestream. */
    fun f16(): Double {
        val b = bits(16)
        val sign = b ushr 15
        val exp = (b ushr 10) and 0x1F
        val mantissa = b and 0x3FF
        if (exp == 31) jxlFail("a half float holds infinity or NaN")
        val magnitude = if (exp == 0) {
            mantissa / 1024.0 / 16384.0
        } else {
            // 2^(exp - 15) is exact in a double for every exponent a half float has.
            (1.0 + mantissa / 1024.0) * if (exp >= 15) (1 shl (exp - 15)).toDouble() else 1.0 / (1 shl (15 - exp))
        }
        return if (sign == 1) -magnitude else magnitude
    }

    /** An enumerated value; the caller checks that it names something. */
    fun enumValue(): Int = u32(fv(0), fv(1), fo(4, 2), fo(6, 18))

    fun skip(n: Long) {
        var left = n
        if (left > bitsLeft) short("a skipped field")
        while (left > 0) {
            val step = minOf(left, 32L).toInt()
            bits(step)
            left -= step
        }
    }

    /** Moves to the next byte boundary. The bits passed over are padding and must be zero. */
    fun zeroPadToByte() {
        val pad = (-bitsConsumed and 7).toInt()
        if (pad != 0 && bits(pad) != 0) jxlFail("padding bits are not zero")
    }
}

/** The signed value libjxl packs as `2 * |v|`, less one when negative. */
internal fun unpackSigned(u: Int): Int = (u ushr 1) xor -(u and 1)
