// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/icc_codec.cc, icc_codec_common.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/**
 * Reads the ICC profile a codestream embeds. The profile is stored as a stream of commands
 * that predict its header, its tag table and its tables of numbers, and that stream is
 * entropy coded.
 */
internal object JxlIcc {
    private const val HEADER_SIZE = 128
    private const val NUM_CONTEXTS = 41
    private const val MAX_ENCODED = 1L shl 28
    private const val MAX_OUTPUT = 1L shl 28

    private val TAG_STRINGS = arrayOf(
        "cprt", "wtpt", "bkpt", "rXYZ", "gXYZ", "bXYZ", "kXYZ", "rTRC", "gTRC", "bTRC", "kTRC", "chad",
        "desc", "chrm", "dmnd", "dmdd", "lumi",
    )
    private val TYPE_STRINGS = arrayOf("XYZ ", "desc", "text", "mluc", "para", "curv", "sf32", "gbd ")
    private val FIXED_SIZE_TAGS = setOf("rXYZ", "gXYZ", "bXYZ", "kXYZ", "wtpt", "bkpt", "lumi")

    /** The header most profiles nearly have: a version 4 RGB monitor profile with a D50 illuminant. */
    private fun initialHeader(size: Int): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        h[0] = (size ushr 24).toByte()
        h[1] = (size ushr 16).toByte()
        h[2] = (size ushr 8).toByte()
        h[3] = size.toByte()
        h[8] = 4
        "mntrRGB XYZ ".forEachIndexed { i, c -> h[12 + i] = c.code.toByte() }
        "acsp".forEachIndexed { i, c -> h[36 + i] = c.code.toByte() }
        h[70] = 246.toByte()
        h[71] = 214.toByte()
        h[73] = 1
        h[78] = 211.toByte()
        h[79] = 45
        return h
    }

    private class Sink(private val limit: Long) {
        var data = ByteArray(256)
        var size = 0

        fun push(b: Int) {
            if (size >= limit) jxlFail("ICC profile is longer than it says")
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = b.toByte()
        }

        fun u32(v: Long) {
            push((v ushr 24).toInt())
            push((v ushr 16).toInt())
            push((v ushr 8).toInt())
            push(v.toInt())
        }

        fun keyword(k: String) {
            for (c in k) push(c.code)
        }
    }

    private fun byteKind1(b: Int): Int = when {
        b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code -> 0
        b in '0'.code..'9'.code || b == '.'.code || b == ','.code -> 1
        b == 0 -> 2
        b == 1 -> 3
        b < 16 -> 4
        b == 255 -> 6
        b > 240 -> 5
        else -> 7
    }

    private fun byteKind2(b: Int): Int = when {
        b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code -> 0
        b in '0'.code..'9'.code || b == '.'.code || b == ','.code -> 1
        b < 16 -> 2
        b > 240 -> 3
        else -> 4
    }

    /** Reads the profile at [br], which is left just past it. */
    fun read(br: JxlBitReader): ByteArray {
        val encodedSize = br.u64()
        if (encodedSize < 0 || encodedSize > MAX_ENCODED) jxlFail("ICC stream of $encodedSize bytes")
        val startBits = br.bitsConsumed
        val code = JxlCode.read(br, NUM_CONTEXTS)
        val reader = JxlSymbolReader(code, br)
        val n = encodedSize.toInt()
        var enc = ByteArray(minOf(n, 1024))
        var b1 = 0
        var b2 = 0
        for (i in 0 until n) {
            if (i == enc.size) {
                // A stream that grows 256 times past the bytes it came from is not a profile.
                if (i > (br.bitsConsumed - startBits) / 8 * 256 + 1024) jxlFail("ICC stream expands past its data")
                enc = enc.copyOf(minOf(n.toLong(), i * 2L).toInt())
            }
            val ctx = if (i <= 128) 0 else 1 + byteKind1(b1) + byteKind2(b2) * 8
            val b = reader.read(ctx) and 0xFF
            enc[i] = b.toByte()
            b2 = b1
            b1 = b
        }
        reader.checkFinalState()
        return unpredict(enc, n)
    }

    private fun shuffle(data: ByteArray, width: Int) {
        val size = data.size
        val height = (size + width - 1) / width
        val copy = data.copyOf()
        var s = 0
        var j = 0
        for (i in 0 until size) {
            data[i] = copy[j]
            j += height
            if (j >= size) j = ++s
        }
    }

    private fun unpredict(enc: ByteArray, size: Int): ByteArray {
        var pos = 0L
        var cpos = 0L

        fun varint(atCommands: Boolean): Long {
            val at = if (atCommands) cpos else pos
            var ret = 0L
            var i = 0
            while (at + i < size && i < 10) {
                val b = enc[(at + i).toInt()].toInt()
                ret = ret or ((b and 127).toLong() shl (7 * i))
                if (b and 128 == 0) break
                i++
            }
            if (atCommands) cpos = at + i + 1 else pos = at + i + 1
            return ret
        }

        fun is32(v: Long) {
            if (v < 0 || v > 0xFFFFFFFFL) jxlFail("ICC value does not fit 32 bits")
        }

        fun bounds(a: Long, b: Long, limit: Long) {
            if (a < 0 || b < 0 || a + b > limit || a + b < a) jxlFail("ICC stream reads past its end")
        }

        if (size == 0) jxlFail("empty ICC stream")
        val osize = varint(false)
        is32(osize)
        if (pos >= size) jxlFail("ICC stream reads past its end")
        val csize = varint(false)
        is32(csize)
        cpos = pos
        bounds(pos, csize, size.toLong())
        val commandsEnd = cpos + csize
        pos = commandsEnd
        if (osize > MAX_OUTPUT) jxlFail("ICC profile of $osize bytes")
        if (osize + 65536 < size) jxlFail("ICC stream is longer than its profile")

        val out = Sink(osize)
        val header = initialHeader(osize.toInt())

        fun finished(): ByteArray {
            if (cpos != commandsEnd) jxlFail("ICC commands left over")
            if (pos != size.toLong()) jxlFail("ICC data left over")
            return out.data.copyOf(out.size)
        }

        for (i in 0..HEADER_SIZE) {
            if (out.size.toLong() == osize) return finished()
            if (i == HEADER_SIZE) break
            // A few header bytes are predicted from the bytes before them.
            val icc = out.data
            if (i == 8 && out.size >= 8) for (k in 0 until 4) header[80 + k] = icc[4 + k]
            if (i == 41 && out.size >= 41) {
                if (icc[40] == 'A'.code.toByte()) "PPL".forEachIndexed { k, c -> header[41 + k] = c.code.toByte() }
                if (icc[40] == 'M'.code.toByte()) "SFT".forEachIndexed { k, c -> header[41 + k] = c.code.toByte() }
            }
            if (i == 42 && out.size >= 42) {
                if (icc[40] == 'S'.code.toByte() && icc[41] == 'G'.code.toByte()) "I ".forEachIndexed { k, c -> header[42 + k] = c.code.toByte() }
                if (icc[40] == 'S'.code.toByte() && icc[41] == 'U'.code.toByte()) "NW".forEachIndexed { k, c -> header[42 + k] = c.code.toByte() }
            }
            if (pos >= size) jxlFail("ICC stream reads past its end")
            out.push(enc[pos.toInt()] + header[i])
            pos++
        }
        if (cpos >= commandsEnd) jxlFail("ICC stream reads past its end")

        var numTags = varint(true)
        if (numTags != 0L) {
            numTags--
            is32(numTags)
            out.u32(numTags)
            var prevStart = HEADER_SIZE + numTags * 12
            var prevSize = 0L
            while (true) {
                if (cpos > commandsEnd) jxlFail("ICC stream reads past its end")
                if (cpos == commandsEnd) break
                val command = enc[(cpos++).toInt()].toInt() and 0xFF
                val tagCode = command and 63
                val tag = when {
                    tagCode == 0 -> break
                    tagCode == 1 -> {
                        bounds(pos, 4, size.toLong())
                        val t = CharArray(4) { (enc[(pos + it).toInt()].toInt() and 0xFF).toChar() }.concatToString()
                        pos += 4
                        t
                    }
                    tagCode == 2 -> "rTRC"
                    tagCode == 3 -> "rXYZ"
                    tagCode - 4 < TAG_STRINGS.size -> TAG_STRINGS[tagCode - 4]
                    else -> jxlFail("unknown ICC tag code $tagCode")
                }
                // A keyword is four bytes, whatever they are; write them as they were read.
                for (c in tag) out.push(c.code)
                var tagSize = prevSize
                if (tag in FIXED_SIZE_TAGS) tagSize = 20
                val tagStart: Long
                if (command and 64 != 0) {
                    if (cpos >= commandsEnd) jxlFail("ICC stream reads past its end")
                    tagStart = varint(true)
                } else {
                    is32(prevStart)
                    tagStart = prevStart + prevSize
                }
                is32(tagStart)
                out.u32(tagStart)
                if (command and 128 != 0) {
                    if (cpos >= commandsEnd) jxlFail("ICC stream reads past its end")
                    tagSize = varint(true)
                }
                is32(tagSize)
                out.u32(tagSize)
                prevStart = tagStart
                prevSize = tagSize
                if (tagCode == 2) {
                    out.keyword("gTRC")
                    out.u32(tagStart)
                    out.u32(tagSize)
                    out.keyword("bTRC")
                    out.u32(tagStart)
                    out.u32(tagSize)
                }
                if (tagCode == 3) {
                    is32(tagStart + tagSize * 2)
                    out.keyword("gXYZ")
                    out.u32(tagStart + tagSize)
                    out.u32(tagSize)
                    out.keyword("bXYZ")
                    out.u32(tagStart + tagSize * 2)
                    out.u32(tagSize)
                }
            }
        }

        while (true) {
            if (cpos > commandsEnd) jxlFail("ICC stream reads past its end")
            if (cpos == commandsEnd) break
            val command = enc[(cpos++).toInt()].toInt() and 0xFF
            when {
                command == 1 -> {
                    if (cpos >= commandsEnd) jxlFail("ICC stream reads past its end")
                    val num = varint(true)
                    bounds(pos, num, size.toLong())
                    for (i in 0 until num.toInt()) out.push(enc[(pos++).toInt()].toInt())
                }
                command == 2 || command == 3 -> {
                    if (cpos >= commandsEnd) jxlFail("ICC stream reads past its end")
                    val num = varint(true)
                    bounds(pos, num, size.toLong())
                    val shuffled = enc.copyOfRange(pos.toInt(), (pos + num).toInt())
                    shuffle(shuffled, if (command == 2) 2 else 4)
                    for (b in shuffled) out.push(b.toInt())
                    pos += num
                }
                command == 4 -> {
                    bounds(cpos, 2, commandsEnd)
                    val flags = enc[(cpos++).toInt()].toInt() and 0xFF
                    val width = (flags and 3) + 1
                    if (width == 3) jxlFail("ICC prediction of 3-byte numbers")
                    val order = (flags and 12) shr 2
                    if (order == 3) jxlFail("ICC prediction of order 3")
                    var stride = width.toLong()
                    if (flags and 16 != 0) {
                        if (cpos >= commandsEnd) jxlFail("ICC stream reads past its end")
                        stride = varint(true)
                        if (stride < width) jxlFail("ICC prediction stride below its width")
                    }
                    // Three strides back must lie inside what is written.
                    if (out.size == 0 || ((out.size - 1) ushr 2) < stride) jxlFail("ICC prediction stride past the profile")
                    if (cpos >= commandsEnd) jxlFail("ICC stream reads past its end")
                    val num = varint(true)
                    bounds(pos, num, size.toLong())
                    val shuffled = enc.copyOfRange(pos.toInt(), (pos + num).toInt())
                    if (width > 1) shuffle(shuffled, width)
                    val start = out.size
                    val s = stride.toInt()
                    for (i in 0 until num.toInt()) {
                        out.push(predict(out.data, start, i, s, width, order) + shuffled[i])
                    }
                    pos += num
                }
                command == 10 -> {
                    out.keyword("XYZ ")
                    out.u32(0)
                    bounds(pos, 12, size.toLong())
                    for (i in 0 until 12) out.push(enc[(pos++).toInt()].toInt())
                }
                command >= 16 && command < 16 + TYPE_STRINGS.size -> {
                    out.keyword(TYPE_STRINGS[command - 16])
                    out.u32(0)
                }
                else -> jxlFail("unknown ICC command $command")
            }
        }
        if (pos != size.toLong()) jxlFail("ICC data left over")
        if (out.size.toLong() != osize) jxlFail("ICC profile is shorter than it says")
        return out.data.copyOf(out.size)
    }

    private fun extrapolate(p1: Int, p2: Int, p3: Int, order: Int): Int = when (order) {
        0 -> p1
        1 -> 2 * p1 - p2
        else -> 3 * p1 - 3 * p2 + p3
    }

    /** Byte [i] of a run of [width]-byte big-endian numbers, each predicted from the three numbers [stride] bytes apart before it. */
    private fun predict(data: ByteArray, start: Int, i: Int, stride: Int, width: Int, order: Int): Int {
        val pos = start + i
        fun u8(at: Int) = data[at].toInt() and 0xFF
        when (width) {
            1 -> return extrapolate(u8(pos - stride), u8(pos - stride * 2), u8(pos - stride * 3), order)
            2 -> {
                val p = start + (i and 1.inv())
                fun u16(at: Int) = (u8(at) shl 8) + u8(at + 1)
                val pred = extrapolate(u16(p - stride), u16(p - stride * 2), u16(p - stride * 3), order)
                return if (i and 1 != 0) pred and 255 else (pred ushr 8) and 255
            }
            else -> {
                val p = start + (i and 3.inv())
                // A number that would reach into bytes not yet written counts as zero.
                fun u32(at: Int) = if (at + 4 > pos) 0 else (u8(at) shl 24) or (u8(at + 1) shl 16) or (u8(at + 2) shl 8) or u8(at + 3)
                val pred = extrapolate(u32(p - stride), u32(p - stride * 2), u32(p - stride * 3), order)
                return (pred ushr ((3 - (i and 3)) * 8)) and 255
            }
        }
    }
}
