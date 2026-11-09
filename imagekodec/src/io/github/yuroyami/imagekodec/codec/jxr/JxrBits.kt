/*
 * JPEG XR bit reading: a Kotlin port of the read side of jxrlib's bit I/O (image/sys/strcodec.c,
 * image/decode/segdec.c) and of its Huffman table walk (getHuff), at 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE. Altered for Kotlin: one reader over the whole file in place of jxrlib's
 * circular packet buffers.
 */
package io.github.yuroyami.imagekodec.codec.jxr

import io.github.yuroyami.imagekodec.ImageDecodeException

internal fun jxrFail(message: String): Nothing = throw ImageDecodeException("JPEG XR: $message")

/**
 * Reads bits from byte [start] of [data], most significant bit first, as ITU-T T.832 packs
 * every part of a codestream.
 *
 * A peek past [end] reads zeros, because a code is looked up by its longest length; taking a bit
 * past [end] is an [ImageDecodeException], so a file cut short fails instead of decoding noise.
 */
internal class JxrBits(private val data: ByteArray, start: Int, private val end: Int) {
    private var pos = start.toLong() * 8
    private val endBits = end.toLong() * 8

    /** The byte that holds the next bit. */
    val bytePosition: Int get() = (pos ushr 3).toInt()

    private fun byteAt(i: Int): Int = if (i < end) data[i].toInt() and 0xFF else 0

    /** The next [n] bits, 1 to 25, without taking them. */
    fun peek(n: Int): Int {
        val at = (pos ushr 3).toInt()
        val off = (pos and 7).toInt()
        val word = (byteAt(at) shl 24) or (byteAt(at + 1) shl 16) or (byteAt(at + 2) shl 8) or byteAt(at + 3)
        return (word shl off) ushr (32 - n)
    }

    fun flush(n: Int) {
        pos += n
        if (pos > endBits) jxrFail("data ends inside a packet")
    }

    /** The next [n] bits, 0 to 16. */
    fun bits(n: Int): Int {
        if (n == 0) return 0
        val v = peek(n)
        flush(n)
        return v
    }

    /** The next [n] bits, 0 to 32, in two halves as jxrlib's getBit32 reads them. */
    fun bits32(n: Int): Int {
        if (n <= 16) return bits(n)
        val high = bits(16)
        return (high shl (n - 16)) or bits(n - 16)
    }

    fun bool(): Int = bits(1)

    /** 0 for a 0 bit, -1 for a 1 bit, as jxrlib's `_getSign`. */
    fun sign(): Int = -bits(1)

    /**
     * [n] bits of magnitude then a sign bit when the magnitude is not 0, as jxrlib's `_getBit16s`
     * in segdec.c reads a refinement of a zero coefficient.
     */
    fun signedBits(n: Int): Int {
        val r = peek(n + 1)
        val v = ((r shr 1) xor -(r and 1)) + (r and 1)
        flush(n + if (v != 0) 1 else 0)
        return v
    }

    fun alignToByte() {
        pos = (pos + 7) and 7L.inv()
        if (pos > endBits) jxrFail("data ends inside a packet")
    }

    /** A symbol of [table], a decode table in jxrlib's layout of 5 root bits (getHuff). */
    fun huff(table: IntArray): Int {
        val symbol = table[peek(5)]
        flush(if (symbol < 0) 5 else symbol and 7)
        var huff = symbol shr 3
        if (huff < 0) {
            huff = symbol
            do {
                huff = table[huff + 0x8000 + bits(1)]
            } while (huff < 0)
        }
        return huff
    }

    /** A symbol of [table] whose codes all fit the 5 root bits (`_getHuffShort`). */
    fun huffShort(table: IntArray): Int {
        val symbol = table[peek(5)]
        flush(symbol and 7)
        return symbol shr 3
    }
}
