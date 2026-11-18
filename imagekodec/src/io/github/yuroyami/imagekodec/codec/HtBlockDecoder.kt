package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException

/**
 * The HT block decoder of ITU-T T.814 (HTJ2K): one HT set, its cleanup pass and, when the packet
 * headers gave them, the SigProp and MagRef passes one bit plane below.
 *
 * The cleanup segment holds three streams: MagSgn forward from its start, MEL forward from where
 * the last two bytes say the suffix begins, and VLC backward from its end. The refinement segment
 * holds the SigProp bits forward and the MagRef bits backward. Every reader feeds fill bits past
 * its stream, and every loop is bounded by the block's size, so a damaged block decodes to wrong
 * samples or throws [ImageDecodeException], never reads out of bounds or runs on.
 *
 * The result goes into [mag], [sign] and [lastPlane] as the Part 1 decoder leaves them: each
 * magnitude with its known bits in place, and the plane of the last pass that coded it, from which
 * the decoder adds the half step.
 */
internal object HtBlockDecoder {

    /**
     * Decodes a block of [w] by [h] samples whose cleanup pass codes plane [p] and above. [passes]
     * is 1, 2 or 3: the cleanup, then SigProp, then MagRef, both at plane p - 1, from [refinement].
     * [stripeCausal] is code-block style 0x08, under which SigProp does not look below a stripe.
     */
    fun decode(
        cleanup: ByteArray, refinement: ByteArray?, passes: Int, w: Int, h: Int, p: Int, stripeCausal: Boolean,
        mag: IntArray, sign: IntArray, lastPlane: IntArray,
    ) {
        if (p < 0) fail("an HT code-block with more missing bit planes than its band has")
        if (passes > 1 && p == 0) fail("HT refinement passes below bit plane 0")
        decodeCleanup(cleanup, w, h, p, mag, sign, lastPlane)
        if (passes > 1) refine(refinement ?: ByteArray(0), passes, w, h, p - 1, stripeCausal, mag, sign, lastPlane)
    }

    private fun fail(what: String): Nothing = throw ImageDecodeException("JPEG 2000: $what")

    // ---- the readers ----------------------------------------------------------------------------

    /**
     * MagSgn: bits from the lowest up, forward; a byte after 0xFF holds 7, its top bit stuffed.
     * Past [end] it reads 0xFF.
     */
    private class ForwardBits(private val d: ByteArray, private var pos: Int, private val end: Int, private val fill: Int) {
        private var acc = 0
        private var count = 0
        private var lastFF = false

        private fun refill() {
            val b = if (pos < end) d[pos++].toInt() and 0xFF else fill
            val n = if (lastFF) 7 else 8
            acc = acc or ((b and ((1 shl n) - 1)) shl count)
            count += n
            lastFF = b == 0xFF
        }

        /** The next [n] bits, 0 to 24, first bit lowest. */
        fun bits(n: Int): Int {
            if (n == 0) return 0
            while (count < n) refill()
            val v = acc and ((1 shl n) - 1)
            acc = acc ushr n
            count -= n
            return v
        }

        fun bit(): Int = bits(1)
    }

    /**
     * VLC and MagRef: bits from the lowest up, from the end of a stream back to [start]. A byte
     * whose low 7 bits are all 1 after a byte above 0x8F holds 7 bits. Below [start] it reads 0.
     */
    private class BackwardBits(private val d: ByteArray, private var pos: Int, private val start: Int, unstuff: Boolean) {
        private var acc = 0
        private var count = 0
        private var unstuff = unstuff

        /** Takes the top four bits of byte [pos] first, as the cleanup's VLC stream starts (the low four belong to Scup). */
        fun startWithNibble() {
            val b = (if (pos >= start) d[pos].toInt() and 0xFF else 0) or 0x0F
            pos--
            val n = if (unstuff && b and 0x7F == 0x7F) 3 else 4
            acc = (b ushr 4) and ((1 shl n) - 1)
            count = n
            unstuff = b > 0x8F
        }

        private fun refill() {
            val b = if (pos >= start) d[pos].toInt() and 0xFF else 0
            pos--
            val n = if (unstuff && b and 0x7F == 0x7F) 7 else 8
            acc = acc or ((b and ((1 shl n) - 1)) shl count)
            count += n
            unstuff = b > 0x8F
        }

        fun peek(n: Int): Int {
            while (count < n) refill()
            return acc and ((1 shl n) - 1)
        }

        fun skip(n: Int) {
            while (count < n) refill()
            acc = acc ushr n
            count -= n
        }

        fun bits(n: Int): Int = peek(n).also { skip(n) }
    }

    /** MEL: an adaptive run-length code of quad significance, bits from the top down, forward. */
    private class Mel(private val d: ByteArray, private var pos: Int, private val end: Int) {
        private var byte = 0
        private var left = 0
        private var lastFF = false
        private var k = 0
        private var run = 0
        private var oneAfterRun = false

        private fun bit(): Int {
            if (left == 0) {
                byte = if (pos < end) d[pos++].toInt() and 0xFF else 0xFF
                left = if (lastFF) 7 else 8
                lastFF = byte == 0xFF
            }
            left--
            return (byte ushr left) and 1
        }

        fun symbol(): Int {
            while (true) {
                if (run > 0) { run--; return 0 }
                if (oneAfterRun) { oneAfterRun = false; return 1 }
                val e = HtVlc.MEL_EXPONENT[k]
                if (bit() == 1) {
                    run = 1 shl e
                    if (k < 12) k++
                } else {
                    var r = 0
                    repeat(e) { r = (r shl 1) or bit() }
                    run = r
                    oneAfterRun = true
                    if (k > 0) k--
                }
            }
        }
    }

    // ---- the cleanup pass -----------------------------------------------------------------------

    private fun decodeCleanup(d: ByteArray, w: Int, h: Int, p: Int, mag: IntArray, sign: IntArray, lastPlane: IntArray) {
        val lcup = d.size
        if (lcup < 2) fail("an HT cleanup segment of $lcup bytes")
        val scup = ((d[lcup - 1].toInt() and 0xFF) shl 4) or (d[lcup - 2].toInt() and 0x0F)
        if (scup < 2 || scup > lcup || scup > MAX_SCUP) fail("an HT cleanup segment of $lcup bytes whose MEL and VLC take $scup")
        val suffix = lcup - scup
        val magSgn = ForwardBits(d, 0, suffix, 0xFF)
        val mel = Mel(d, suffix, lcup)
        val vlc = BackwardBits(d, lcup - 2, suffix, unstuff = true)
        vlc.startWithNibble()

        val qw = (w + 1) / 2
        // The magnitude exponent E of each sample of the last row decoded, which the next
        // line-pair's contexts and exponent bounds read; 0 for an insignificant sample.
        val above = IntArray(w)
        val row = IntArray(w)
        val rho = IntArray(2)
        val entry = IntArray(2)
        val u = IntArray(2)
        var y0 = 0
        while (y0 < h) {
            val initial = y0 == 0
            val lookup = if (initial) HtVlc.decodeInitial else HtVlc.decodeLater
            var left = 0
            row.fill(0)
            var g = 0
            while (g < qw) {
                val pair = if (g + 1 < qw) 2 else 1
                for (j in 0 until pair) {
                    val q = g + j
                    val c = if (initial) {
                        (left and 1) or ((left ushr 1) and 1) or (((left ushr 2) and 1) shl 1) or (((left ushr 3) and 1) shl 2)
                    } else {
                        val x = 2 * q
                        fun s(px: Int) = if (px in 0 until w && above[px] > 0) 1 else 0
                        (s(x - 1) or s(x)) or ((((left ushr 2) or (left ushr 3)) and 1) shl 1) or ((s(x + 1) or s(x + 2)) shl 2)
                    }
                    if (c == 0 && mel.symbol() == 0) {
                        entry[j] = 0
                    } else {
                        entry[j] = lookup[(c shl 7) or vlc.peek(7)]
                        vlc.skip(entry[j] and 7)
                    }
                    rho[j] = (entry[j] ushr 4) and 15
                    left = rho[j]
                }
                if (pair == 1) { entry[1] = 0; rho[1] = 0 }
                decodeU(entry, initial, vlc, mel, u)
                for (j in 0 until pair) {
                    if (rho[j] == 0) continue
                    val q = g + j
                    val x = 2 * q
                    var kappa = 1
                    if (!initial && rho[j] and (rho[j] - 1) != 0) {
                        var e = 0
                        for (px in x - 1..x + 2) if (px in 0 until w && above[px] > e) e = above[px]
                        if (e - 1 > kappa) kappa = e - 1
                    }
                    val bound = kappa + u[j]
                    if (bound + p > MAX_PLANES) fail("an HT quad whose magnitudes pass ${MAX_PLANES - p} bits")
                    val ek = (entry[j] ushr 12) and 15
                    val e1 = (entry[j] ushr 16) and 15
                    for (n in 0 until 4) {
                        if (rho[j] ushr n and 1 == 0) continue
                        val known = ek ushr n and 1
                        val m = bound - known
                        var v = if (m > 16) magSgn.bits(16) or (magSgn.bits(m - 16) shl 16) else magSgn.bits(m)
                        if (known == 1) v = v or ((e1 ushr n and 1) shl m)
                        val sx = x + (n ushr 1)
                        val sy = y0 + (n and 1)
                        // A pattern that marks a sample outside the block is damage.
                        if (sx >= w || sy >= h) fail("an HT quad significant outside its code-block")
                        val i = sy * w + sx
                        mag[i] = ((v ushr 1) + 1) shl p
                        sign[i] = v and 1
                        lastPlane[i] = p
                        if (n and 1 == 1) row[sx] = 32 - (v or 1).countLeadingZeroBits()
                    }
                }
                g += 2
            }
            // The next line-pair's contexts and exponent bounds read this pair's bottom row.
            for (x in 0 until w) above[x] = row[x]
            y0 += 2
        }
    }

    /**
     * The exponent offsets u of a quad pair whose VLC entries are [entry]: none for a quad with
     * u_off 0. In the first line-pair, a pair where both have it reads a MEL symbol first: 1 means
     * both offsets are above 2.
     */
    private fun decodeU(entry: IntArray, initial: Boolean, vlc: BackwardBits, mel: Mel, u: IntArray) {
        val a = (entry[0] ushr 8) and 1
        val b = (entry[1] ushr 8) and 1
        u[0] = 0
        u[1] = 0
        if (initial && a == 1 && b == 1) {
            if (mel.symbol() == 1) {
                val pa = prefix(vlc)
                val pb = prefix(vlc)
                u[0] = 2 + pa + suffix(vlc, pa)
                u[1] = 2 + pb + suffix(vlc, pb)
            } else {
                val pa = prefix(vlc)
                if (pa > 2) {
                    u[1] = 1 + vlc.bits(1)
                    u[0] = pa + suffix(vlc, pa)
                } else {
                    val pb = prefix(vlc)
                    u[0] = pa + suffix(vlc, pa)
                    u[1] = pb + suffix(vlc, pb)
                }
            }
        } else {
            val pa = if (a == 1) prefix(vlc) else 0
            val pb = if (b == 1) prefix(vlc) else 0
            if (a == 1) u[0] = pa + suffix(vlc, pa)
            if (b == 1) u[1] = pb + suffix(vlc, pb)
        }
        // An offset past 32 takes extension bits, which only magnitudes far beyond the 30 bits this
        // decoder keeps would need, so it is refused before they are read.
        if (u[0] > 32 || u[1] > 32) fail("an HT exponent offset beyond 32")
    }

    /** The U-VLC prefix: 1, 01, 001 and 000 give 1, 2, 3 and 5. */
    private fun prefix(vlc: BackwardBits): Int = when {
        vlc.bits(1) == 1 -> 1
        vlc.bits(1) == 1 -> 2
        vlc.bits(1) == 1 -> 3
        else -> 5
    }

    /** The U-VLC suffix: one bit after prefix 3, five after prefix 5. */
    private fun suffix(vlc: BackwardBits, prefix: Int): Int = when (prefix) {
        3 -> vlc.bits(1)
        5 -> vlc.bits(5)
        else -> 0
    }

    // ---- the refinement passes ------------------------------------------------------------------

    /**
     * SigProp and, with [passes] 3, MagRef at [plane]. SigProp visits each stripe of four rows in
     * groups of four columns, each column top down: an insignificant sample with a significant
     * neighbour, counting those this pass has already made significant, reads its significance;
     * the signs of the group's new samples follow the group. MagRef reads the next bit of each
     * sample the cleanup made significant, in the same stripe order.
     */
    private fun refine(
        d: ByteArray, passes: Int, w: Int, h: Int, plane: Int, stripeCausal: Boolean,
        mag: IntArray, sign: IntArray, lastPlane: IntArray,
    ) {
        // Significance after the cleanup: MagRef refines exactly these.
        val clean = BooleanArray(w * h) { mag[it] != 0 }
        val sigProp = ForwardBits(d, 0, d.size, 0)
        val fresh = IntArray(16)
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(y0 + 4, h)
            // Under the stripe-causal style the rows below the stripe count as insignificant.
            val lowest = if (stripeCausal) y1 - 1 else h - 1
            var gx = 0
            while (gx < w) {
                var n = 0
                for (x in gx until minOf(gx + 4, w)) for (y in y0 until y1) {
                    val i = y * w + x
                    if (mag[i] != 0) continue
                    var near = false
                    for (ny in y - 1..minOf(y + 1, lowest)) {
                        if (ny < 0) continue
                        for (nx in x - 1..x + 1) if (nx in 0 until w && (nx != x || ny != y) && mag[ny * w + nx] != 0) near = true
                    }
                    if (!near) continue
                    if (sigProp.bit() == 1) {
                        mag[i] = 1 shl plane
                        lastPlane[i] = plane
                        fresh[n++] = i
                    }
                }
                for (k in 0 until n) sign[fresh[k]] = sigProp.bit()
                gx += 4
            }
            y0 += 4
        }
        if (passes < 3) return
        val magRef = BackwardBits(d, d.size - 1, 0, unstuff = true)
        y0 = 0
        while (y0 < h) {
            for (x in 0 until w) for (y in y0 until minOf(y0 + 4, h)) {
                val i = y * w + x
                if (!clean[i]) continue
                mag[i] = mag[i] or (magRef.bits(1) shl plane)
                lastPlane[i] = plane
            }
            y0 += 4
        }
    }

    /** The largest Scup (T.814): a larger one would make the last byte of the segment 0xFF. */
    private const val MAX_SCUP = 4079

    /** Magnitude bits a decoded sample may have, so that twice it, with the half step, fits an Int. */
    private const val MAX_PLANES = 30
}
