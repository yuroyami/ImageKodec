package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.codec.Jp2Headers.STYLE_BYPASS
import io.github.yuroyami.imagekodec.codec.Jp2Headers.STYLE_TERMALL

/**
 * The packet header bits of T.800 B.10.1: most significant bit first, and a byte after 0xFF holds
 * 7 bits under a stuffed 0. A header that ends on a 0xFF byte is followed by a 0 byte, which a
 * decoder skips as it aligns (JpxDecoder's Bio.align).
 */
internal class Jp2HeaderBits(private val out: Jp2ByteBuffer) {
    private var value = 0
    private var filled = 0
    private var capacity = 8
    private var lastFf = false

    fun bit(b: Int) {
        value = (value shl 1) or b
        filled++
        if (filled == capacity) emit()
    }

    fun bits(v: Int, n: Int) {
        for (k in n - 1 downTo 0) bit((v ushr k) and 1)
    }

    private fun emit() {
        out.add(value)
        lastFf = value == 0xFF
        capacity = if (lastFf) 7 else 8
        value = 0
        filled = 0
    }

    /** Ends the header on a byte boundary. */
    fun align() {
        if (filled > 0) {
            value = value shl (capacity - filled)
            filled = capacity
            emit()
        }
        if (lastFf) out.add(0)
        value = 0
        filled = 0
        capacity = 8
        lastFf = false
    }
}

/**
 * A tag tree (T.800 B.10.2) in the coding direction: [encode] writes the bits with which
 * JpxDecoder's TagTree.decode, given the same threshold, learns what it learns, node by node from
 * the root, each node's value the least of its children's.
 */
internal class Jp2TagTreeEncoder(w: Int, h: Int) {
    private val levels: Int
    private val widths: IntArray
    private val offs: IntArray
    private val value: IntArray
    private val low: IntArray
    private val known: BooleanArray

    init {
        val ws = ArrayList<Int>()
        val hs = ArrayList<Int>()
        var lw = maxOf(1, w)
        var lh = maxOf(1, h)
        while (true) {
            ws.add(lw); hs.add(lh)
            if (lw == 1 && lh == 1) break
            lw = (lw + 1) / 2; lh = (lh + 1) / 2
        }
        levels = ws.size
        widths = ws.toIntArray()
        offs = IntArray(levels)
        var total = 0
        for (l in 0 until levels) { offs[l] = total; total += widths[l] * hs[l] }
        value = IntArray(total) { Int.MAX_VALUE }
        low = IntArray(total)
        known = BooleanArray(total)
    }

    private fun idx(l: Int, x: Int, y: Int) = offs[l] + y * widths[l] + x

    /** Sets leaf ([x], [y]) to [v] and lowers every node above it to at most [v]; a fresh coding starts. */
    fun set(x: Int, y: Int, v: Int) {
        for (l in 0 until levels) {
            val i = idx(l, x shr l, y shr l)
            if (l == 0 || v < value[i]) value[i] = v
        }
    }

    /** Forgets what has been coded and every value. */
    fun clear() {
        value.fill(Int.MAX_VALUE)
        low.fill(0)
        known.fill(false)
    }

    /** Writes what tells a decoder whether leaf ([x], [y]) is below [threshold]; true when it is. */
    fun encode(out: Jp2HeaderBits, x: Int, y: Int, threshold: Int): Boolean {
        var bound = 0
        for (l in levels - 1 downTo 0) {
            val i = idx(l, x shr l, y shr l)
            if (low[i] < bound) low[i] = bound
            while (!known[i] && low[i] < threshold) {
                if (value[i] == low[i]) { out.bit(1); known[i] = true } else { out.bit(0); low[i]++ }
            }
            if (!known[i]) return false
            bound = low[i]
        }
        return true
    }

    /** Writes the leaf's value in full, with the rising thresholds JpxDecoder's decodeValue reads it with. */
    fun encodeValue(out: Jp2HeaderBits, x: Int, y: Int) {
        var t = 1
        while (!encode(out, x, y, t)) t++
    }
}

/**
 * Tier 2 of one tile (T.800 Annex B): the packets of every layer, resolution, component and
 * precinct in the progression order, each a header that says which code-block passes it carries
 * and how long they are, then those passes' bytes. With [out] null it only counts the bytes, for
 * the rate control's trial runs.
 */
internal class Jp2PacketWriter(private val tile: Jp2Encoder.Tile, private val layers: Int, private val style: Int, private val sop: Boolean, private val eph: Boolean) {

    private val bypass = style and STYLE_BYPASS != 0
    private val termAll = style and STYLE_TERMALL != 0
    private val ht = style and Jp2Headers.STYLE_HT != 0

    /**
     * Whether pass [k] of [b] ends a codeword segment, as JpxDecoder sizes the segments: an HT set's
     * cleanup pass ends one. HT truncation points not yet chosen count as one segment, for the rate
     * control's estimates.
     */
    private fun endsSegment(k: Int, b: Jp2Encoder.Block): Boolean {
        val total = b.coded.passes
        if (ht) return k == total - 1 || (k == 0 && b.coded.htPlane >= 0)
        return k == total - 1 || termAll || (bypass && k >= 9 && (k - 9) % 3 != 1)
    }

    /** The bytes of the tile's packets of layers up to [lastLayer], written to [out] when it is given. */
    fun write(out: Jp2ByteBuffer?, lastLayer: Int): Int {
        val headerBuf = Jp2ByteBuffer(256)
        val bits = Jp2HeaderBits(headerBuf)
        // Fresh tag trees and block states for this run.
        for (comp in tile.comps) for (res in comp.resolutions) for (band in res.bands) for (precinct in band.precincts) {
            precinct.inclusion.clear()
            precinct.zeros.clear()
            for ((i, b) in precinct.blocks.withIndex()) {
                if (b == null) continue
                b.lBlock = 3
                b.sent = 0
                b.sentBefore = 0
                b.included = false
                val gx = i % precinct.cbW
                val gy = i / precinct.cbW
                val first = b.layerEnd.indexOfFirst { it > 0 }
                precinct.inclusion.set(gx, gy, if (first < 0) layers else first)
                // An HT set's cleanup pass codes the plane the zero bit planes point at (T.814), not the block's top one.
                val zeros = when {
                    b.coded.passes == 0 -> MAX_ZEROS
                    b.coded.htPlane >= 0 -> band.magnitudePlanes - 1 - b.coded.htPlane
                    else -> band.magnitudePlanes - b.coded.planes
                }
                precinct.zeros.set(gx, gy, zeros)
            }
        }
        var total = 0
        var sequence = 0
        for (packet in tile.order) {
            val l = packet.layer
            if (l > lastLayer) continue
            val res = tile.comps[packet.comp].resolutions[packet.res]
            headerBuf.clear()
            if (sop) {
                headerBuf.u16(0xFF91); headerBuf.u16(4); headerBuf.u16(sequence and 0xFFFF)
            }
            sequence++
            var any = false
            for (band in res.bands) {
                val precinct = band.precincts[packet.precinct]
                for (b in precinct.blocks) if (b != null && b.layerEnd[l] > b.sent) { any = true; break }
                if (any) break
            }
            var body = 0
            if (!any) {
                bits.bit(0)
            } else {
                bits.bit(1)
                for (band in res.bands) {
                    val precinct = band.precincts[packet.precinct]
                    for ((i, b) in precinct.blocks.withIndex()) {
                        if (b == null) continue
                        val gx = i % precinct.cbW
                        val gy = i / precinct.cbW
                        val newPasses = b.layerEnd[l] - b.sent
                        if (!b.included) {
                            if (!precinct.inclusion.encode(bits, gx, gy, l + 1)) continue
                            precinct.zeros.encodeValue(bits, gx, gy)
                            b.included = true
                        } else {
                            bits.bit(if (newPasses > 0) 1 else 0)
                            if (newPasses == 0) continue
                        }
                        body += header(bits, b, newPasses)
                    }
                }
            }
            bits.align()
            if (eph) headerBuf.u16(0xFF92)
            total += headerBuf.size + body
            if (out != null) {
                out.add(headerBuf.toByteArray())
                for (band in res.bands) {
                    val precinct = band.precincts[packet.precinct]
                    for (b in precinct.blocks) {
                        if (b == null) continue
                        val from = b.byteAt(b.sentBefore)
                        val to = b.byteAt(b.sent)
                        if (to > from) out.add(b.coded.data, from, to)
                    }
                }
            }
            for (band in res.bands) for (b in band.precincts[packet.precinct].blocks) if (b != null) b.sentBefore = b.sent
        }
        return total
    }

    /**
     * The number of passes (B.10.6), the Lblock increase and the length of each codeword segment the
     * new passes reach (B.10.7), for a block that adds [n] passes; the bytes they bring.
     */
    private fun header(bits: Jp2HeaderBits, b: Jp2Encoder.Block, n: Int): Int {
        when {
            n == 1 -> bits.bit(0)
            n == 2 -> bits.bits(0b10, 2)
            n <= 5 -> { bits.bits(0b11, 2); bits.bits(n - 3, 2) }
            n <= 36 -> { bits.bits(0b1111, 4); bits.bits(n - 6, 5) }
            else -> { bits.bits(0b1111, 4); bits.bits(0b11111, 5); bits.bits(n - 37, 7) }
        }
        // The pieces: runs of new passes, each ending at a segment end or at the last new pass.
        var start = b.sent
        val end = b.sent + n
        var pieces = 0
        var need = b.lBlock
        while (start < end) {
            var stop = start
            while (stop < end - 1 && !endsSegment(stop, b)) stop++
            val take = stop - start + 1
            val length = b.byteAt(stop + 1) - b.byteAt(start)
            val bitsFor = 32 - length.countLeadingZeroBits()
            val floorLog = 31 - take.countLeadingZeroBits()
            if (bitsFor - floorLog > need) need = bitsFor - floorLog
            pieceTake[pieces] = take
            pieceLength[pieces] = length
            pieces++
            start = stop + 1
        }
        repeat(need - b.lBlock) { bits.bit(1) }
        bits.bit(0)
        b.lBlock = need
        var bytes = 0
        for (k in 0 until pieces) {
            bits.bits(pieceLength[k], need + 31 - pieceTake[k].countLeadingZeroBits())
            bytes += pieceLength[k]
        }
        b.sent = end
        return bytes
    }

    private val pieceTake = IntArray(MAX_PASSES)
    private val pieceLength = IntArray(MAX_PASSES)

    private companion object {
        /** A zero bit-plane count for a block no packet includes: it only bounds the nodes above it. */
        const val MAX_ZEROS = 255

        /** B.10.6 counts at most 164 new passes a packet. */
        const val MAX_PASSES = 165
    }
}
