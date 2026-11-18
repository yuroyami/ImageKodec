package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.codec.Jp2Headers.STYLE_BYPASS
import io.github.yuroyami.imagekodec.codec.Jp2Headers.STYLE_RESET
import io.github.yuroyami.imagekodec.codec.Jp2Headers.STYLE_SEGSYM
import io.github.yuroyami.imagekodec.codec.Jp2Headers.STYLE_TERMALL
import io.github.yuroyami.imagekodec.codec.Jp2Headers.STYLE_VSC
import io.github.yuroyami.imagekodec.codec.JpxDecoder.CTX_RLC
import io.github.yuroyami.imagekodec.codec.JpxDecoder.CTX_UNI

/**
 * One code-block coded by tier 1: [data] holds its codeword segments one after another, and pass k
 * (from 0) ends at byte [rates] of it, having removed [gains] of the squared error, in squared
 * quantization steps, counted from no pass at all.
 */
internal class CodedBlock(
    val data: ByteArray,
    val passes: Int,
    /** The magnitude bit planes of the block's largest coefficient: its first pass codes plane `planes - 1`. */
    val planes: Int,
    val rates: IntArray,
    val gains: DoubleArray,
    /**
     * For an HT set ([HtBlockEncoder]), the plane its cleanup pass codes, which the zero bit planes of
     * the packet header give; -1 for Part 1 passes and for HT truncation points not yet chosen.
     */
    val htPlane: Int = -1,
) {
    companion object {
        val EMPTY = CodedBlock(ByteArray(0), 0, 0, IntArray(0), DoubleArray(0))
    }
}

/**
 * The EBCOT block coder of T.800 Annex D in the encoding direction. Every context, every pass and
 * every segment boundary is the one [JpxDecoder] reads, from the same zero-coding tables and sign
 * contexts, so that the decoder's walk through a block is this walk with the bits read instead of
 * written.
 *
 * Alongside the bits it measures what each pass is worth for rate control (T.800 J.14): the squared
 * error of every coefficient between its true magnitude and what a decoder reconstructs from the
 * passes so far, at the middle of the interval the coded bits leave open (E.1.1, r = 1/2). For the
 * reversible path the last plane leaves no interval, and the error there is 0.
 */
internal class Jp2BlockEncoder(private val style: Int, private val reversible: Boolean) {
    private val vsc = style and STYLE_VSC != 0
    private val bypass = style and STYLE_BYPASS != 0
    private val reset = style and STYLE_RESET != 0
    private val termAll = style and STYLE_TERMALL != 0
    private val segSym = style and STYLE_SEGSYM != 0

    private var w = 0
    private var h = 0
    private var stride = 0
    private var flags = IntArray(0)
    private var mags = IntArray(0)
    private var exact = DoubleArray(0)
    private var useExact = false
    private val cx = IntArray(19)
    private var zc = IntArray(0)
    private var gain = 0.0

    private var mq: MqEncoder? = null
    private var raw: RawWriter? = null

    /**
     * Codes the block of [width] by [height] coefficients whose signed quantization indices are
     * [values] (row by row, [offset] and [rowStride] into the band), in a band of orientation [orient].
     * [magnitudes], when given, holds each coefficient's magnitude in steps before the floor, which
     * the distortion is measured against; without it the indices are exact, as for the 5/3 path.
     */
    fun encode(
        values: IntArray, offset: Int, rowStride: Int, width: Int, height: Int, orient: Int,
        magnitudes: DoubleArray?,
    ): CodedBlock {
        w = width
        h = height
        stride = w + 2
        val size = stride * (h + 2)
        if (flags.size < size) {
            flags = IntArray(size)
            mags = IntArray(size)
        } else {
            flags.fill(0, 0, size)
            mags.fill(0, 0, size)
        }
        useExact = magnitudes != null
        if (useExact && exact.size < size) exact = DoubleArray(size)
        var max = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = values[offset + y * rowStride + x]
            val i = (y + 1) * stride + x + 1
            val m = if (v < 0) -v else v
            mags[i] = m
            if (v < 0) flags[i] = NEG
            if (m > max) max = m
            if (magnitudes != null) exact[i] = magnitudes[offset + y * rowStride + x]
        }
        val planes = 32 - max.countLeadingZeroBits()
        if (planes == 0) return CodedBlock.EMPTY
        zc = JpxDecoder.zeroCodingTable(orient)
        JpxDecoder.resetContexts(cx)

        val total = 3 * planes - 2
        val rates = IntArray(total)
        val gains = DoubleArray(total)
        val out = Jp2ByteBuffer(64)
        // Where each pass of the open segment ended: the bytes written and the shifts left.
        val endWritten = IntArray(total)
        val endShifts = IntArray(total)
        var segmentStart = 0
        var cumulative = 0.0
        for (k in 0 until total) {
            val type = if (k == 0) CLEANUP else (k - 1) % 3
            val plane = if (k == 0) planes - 1 else planes - 2 - (k - 1) / 3
            val isRaw = bypass && type != CLEANUP && plane + 1 <= planes - 4
            if (k == segmentStart) {
                if (isRaw) { raw = RawWriter(); mq = null } else { mq = MqEncoder(); raw = null }
            }
            gain = 0.0
            when (type) {
                SIGNIFICANCE -> significancePass(plane)
                REFINEMENT -> refinementPass(plane)
                else -> {
                    cleanupPass(plane)
                    if (segSym) {
                        val m = mq!!
                        m.encode(cx, CTX_UNI, 1); m.encode(cx, CTX_UNI, 0); m.encode(cx, CTX_UNI, 1); m.encode(cx, CTX_UNI, 0)
                    }
                }
            }
            if (reset && !isRaw) JpxDecoder.resetContexts(cx)
            cumulative += gain
            gains[k] = cumulative
            val r = raw
            val m = mq
            if (r != null) endWritten[k] = r.started else if (m != null) {
                endWritten[k] = m.written
                endShifts[k] = m.shiftsLeft
            }
            // Segment ends as the decoder sizes them (JpxDecoder's CodeBlock.newSegment): each pass with
            // termination; with the bypass, the first ten passes, then each raw pair and each cleanup.
            val ends = k == total - 1 || termAll || (bypass && k >= 9 && (k - 9) % 3 != 1)
            if (ends) {
                val bytes = r?.finish() ?: m!!.flush()
                for (j in segmentStart..k) {
                    val length = when {
                        j == k -> bytes.size
                        r != null -> minOf(endWritten[j], bytes.size)
                        else -> MqEncoder.truncationLength(bytes, endWritten[j], endShifts[j])
                    }
                    rates[j] = maxOf(out.size + length, if (j > 0) rates[j - 1] else 0)
                }
                out.add(bytes)
                segmentStart = k + 1
            }
        }
        return CodedBlock(out.toByteArray(), total, planes, rates, gains)
    }

    // ---- passes ---------------------------------------------------------------------------------

    private fun encodeBit(symbol: Int, context: Int) {
        val r = raw
        if (r != null) r.bit(symbol) else mq!!.encode(cx, context, symbol)
    }

    private fun seesBelow(y: Int): Boolean = !vsc || y and 3 != 3

    /** The zero-coding table index of coefficient [i] on block row [y], as JpxDecoder's neighborSums gives it. */
    private fun sums(i: Int, y: Int): Int {
        val f = flags
        val s = stride
        val hh = (f[i - 1] and SIG) + (f[i + 1] and SIG)
        var vv = f[i - s] and SIG
        var dd = (f[i - s - 1] and SIG) + (f[i - s + 1] and SIG)
        if (seesBelow(y)) {
            vv += f[i + s] and SIG
            dd += (f[i + s - 1] and SIG) + (f[i + s + 1] and SIG)
        }
        return hh * 12 + vv * 4 + (if (dd > 3) 3 else dd)
    }

    private fun contribution(f: Int): Int = if (f and SIG == 0) 0 else if (f and NEG != 0) -1 else 1

    private fun signContext(i: Int, y: Int): Int {
        val f = flags
        val hc = (contribution(f[i - 1]) + contribution(f[i + 1])).coerceIn(-1, 1)
        val below = if (seesBelow(y)) contribution(f[i + stride]) else 0
        val vc = (contribution(f[i - stride]) + below).coerceIn(-1, 1)
        return JpxDecoder.signContextOf(hc, vc)
    }

    /** Codes the sign of [i], which has just become significant at [plane], and counts what that removed. */
    private fun becomeSignificant(i: Int, y: Int, plane: Int) {
        val negative = if (flags[i] and NEG != 0) 1 else 0
        val r = raw
        if (r != null) r.bit(negative) else {
            val sc = signContext(i, y)
            mq!!.encode(cx, sc shr 1, negative xor (sc and 1))
        }
        flags[i] = flags[i] or SIG
        val x = magnitude(i)
        val e = x - reconstruction(mags[i], plane)
        gain += x * x - e * e
    }

    private fun magnitude(i: Int): Double = if (useExact) exact[i] else mags[i].toDouble()

    /** The magnitude a decoder rebuilds from the bits of [m] down to [plane], at the middle of the interval left. */
    private fun reconstruction(m: Int, plane: Int): Double {
        val kept = (m ushr plane) shl plane
        return kept + when {
            plane > 0 -> (1 shl (plane - 1)).toDouble()
            reversible -> 0.0
            else -> 0.5
        }
    }

    private fun significancePass(plane: Int) {
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(y0 + 4, h)
            for (x in 0 until w) for (y in y0 until y1) {
                val i = (y + 1) * stride + x + 1
                flags[i] = flags[i] and VISITED.inv()
                if (flags[i] and SIG != 0) continue
                val ctx = zc[sums(i, y)]
                if (ctx == 0) continue
                flags[i] = flags[i] or VISITED
                val bit = (mags[i] ushr plane) and 1
                encodeBit(bit, ctx)
                if (bit == 1) becomeSignificant(i, y, plane)
            }
            y0 += 4
        }
    }

    private fun refinementPass(plane: Int) {
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(y0 + 4, h)
            for (x in 0 until w) for (y in y0 until y1) {
                val i = (y + 1) * stride + x + 1
                val f = flags[i]
                if (f and SIG == 0 || f and VISITED != 0) continue
                val ctx = if (f and REFINED != 0) 16 else if (sums(i, y) > 0) 15 else 14
                encodeBit((mags[i] ushr plane) and 1, ctx)
                flags[i] = f or REFINED
                val x0 = magnitude(i)
                val before = x0 - reconstruction(mags[i], plane + 1)
                val after = x0 - reconstruction(mags[i], plane)
                gain += before * before - after * after
            }
            y0 += 4
        }
    }

    private fun cleanupPass(plane: Int) {
        val m = mq!!
        var y0 = 0
        while (y0 < h) {
            val stripe = minOf(4, h - y0)
            for (x in 0 until w) {
                var y = y0
                var run = stripe == 4
                if (run) {
                    for (yy in y0 until y0 + 4) {
                        val i = (yy + 1) * stride + x + 1
                        if (flags[i] and (SIG or VISITED) != 0 || zc[sums(i, yy)] != 0) { run = false; break }
                    }
                }
                if (run) {
                    var first = -1
                    for (yy in 0 until 4) {
                        if ((mags[(y0 + yy + 1) * stride + x + 1] ushr plane) and 1 != 0) { first = yy; break }
                    }
                    if (first < 0) {
                        m.encode(cx, CTX_RLC, 0)
                        continue
                    }
                    m.encode(cx, CTX_RLC, 1)
                    m.encode(cx, CTX_UNI, first shr 1)
                    m.encode(cx, CTX_UNI, first and 1)
                    y = y0 + first
                    becomeSignificant((y + 1) * stride + x + 1, y, plane)
                    y++
                }
                while (y < y0 + stripe) {
                    val i = (y + 1) * stride + x + 1
                    if (flags[i] and (SIG or VISITED) == 0) {
                        val bit = (mags[i] ushr plane) and 1
                        m.encode(cx, zc[sums(i, y)], bit)
                        if (bit == 1) becomeSignificant(i, y, plane)
                    }
                    flags[i] = flags[i] and VISITED.inv()
                    y++
                }
            }
            y0 += 4
        }
        for (i in 0 until stride * (h + 2)) flags[i] = flags[i] and VISITED.inv()
    }

    /**
     * The raw bits of the selective arithmetic coding bypass (D.6), as JpxDecoder's RawBits reads
     * them: most significant bit first, and a byte after 0xFF holds 7 bits under a stuffed 0.
     */
    private class RawWriter {
        private val out = Jp2ByteBuffer(32)
        private var value = 0
        private var filled = 0
        private var capacity = 8

        /** The bytes that hold at least one bit. */
        val started: Int get() = out.size + if (filled > 0) 1 else 0

        fun bit(b: Int) {
            value = (value shl 1) or b
            filled++
            if (filled == capacity) {
                out.add(value)
                capacity = if (value == 0xFF) 7 else 8
                value = 0
                filled = 0
            }
        }

        /** The segment, its last byte filled with 0 bits; a last 0xFF is dropped, since a decoder reads 1 bits past the end. */
        fun finish(): ByteArray {
            if (filled > 0) out.add(value shl (capacity - filled))
            var n = out.size
            while (n > 0 && out[n - 1] == 0xFF) n--
            return out.toByteArray().copyOf(n)
        }
    }

    private companion object {
        const val SIG = 1
        const val VISITED = 2
        const val REFINED = 4
        const val NEG = 8

        const val SIGNIFICANCE = 0
        const val REFINEMENT = 1
        const val CLEANUP = 2
    }
}

/** A growable byte buffer for the JPEG 2000 encoder. */
internal class Jp2ByteBuffer(capacity: Int) {
    private var buf = ByteArray(maxOf(8, capacity))
    var size = 0
        private set

    operator fun get(i: Int): Int = buf[i].toInt() and 0xFF

    fun add(b: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }

    fun add(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        val n = to - from
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        bytes.copyInto(buf, size, from, to)
        size += n
    }

    fun u16(v: Int) { add(v ushr 8); add(v) }

    fun u32(v: Long) { add((v ushr 24).toInt()); add((v ushr 16).toInt()); add((v ushr 8).toInt()); add(v.toInt()) }

    fun clear() { size = 0 }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
