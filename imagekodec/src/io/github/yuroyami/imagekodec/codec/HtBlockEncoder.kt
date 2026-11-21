package io.github.yuroyami.imagekodec.codec

/**
 * The HT block coder of ITU-T T.814 (HTJ2K) in the encoding direction: one HT set, a cleanup pass
 * at a chosen bit plane and, below it, the SigProp and MagRef passes. Every context, codeword and
 * stream is the one [HtBlockDecoder] reads, so the decoder's walk through a block is this walk
 * with the bits read instead of written.
 *
 * For the rate control it also measures every HT set the block could be coded with, in the pass
 * numbering of the Part 1 coder: truncation point k stops where Part 1's k-th pass would, and the
 * set that stops there costs its bytes and leaves the same reconstruction, so [Jp2Encoder]'s hull
 * and thresholds work on it unchanged. HT sets do not nest, so a block takes one set, whole or with
 * its refinement passes cut; [Jp2Encoder] codes that set once the threshold is known.
 */
internal class HtBlockEncoder(private val stripeCausal: Boolean) {

    /**
     * One HT set of a block of [planes] magnitude bit planes: the cleanup segment, and the SigProp
     * and MagRef bytes of the refinement segment.
     */
    class HtSet(val planes: Int, val cleanup: ByteArray, val sigProp: ByteArray, val magRef: ByteArray)

    private var w = 0
    private var h = 0
    private var mags = IntArray(0)
    private var negative = BooleanArray(0)

    private fun load(values: IntArray, offset: Int, rowStride: Int, width: Int, height: Int): Int {
        w = width
        h = height
        val n = w * h
        if (mags.size < n) { mags = IntArray(n); negative = BooleanArray(n) }
        var max = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = values[offset + y * rowStride + x]
            val m = if (v < 0) -v else v
            mags[y * w + x] = m
            negative[y * w + x] = v < 0
            if (m > max) max = m
        }
        return 32 - max.countLeadingZeroBits()
    }

    /**
     * The magnitude bit planes of the block of [width] by [height] signed quantization indices
     * [values] (row by row, [offset] and [rowStride] into the band), and its HT set with the cleanup
     * pass at plane [plane] and [passes] passes, 1 to 3; null for a block of zeros.
     */
    fun encode(values: IntArray, offset: Int, rowStride: Int, width: Int, height: Int, plane: Int, passes: Int): HtSet? {
        val planes = load(values, offset, rowStride, width, height)
        if (planes == 0) return null
        require(plane in 0 until planes) { "an HT cleanup pass at plane $plane of a block of $planes planes" }
        require(passes == 1 || plane > 0) { "HT refinement passes below plane 0" }
        return codeSet(planes, plane, passes)
    }

    /**
     * Every truncation point of the block as a [CodedBlock] without data: point k, from 1 to
     * `3 * planes - 2`, is the HT set whose cleanup pass codes the plane Part 1's k-th pass would
     * end on, with the refinement passes up to that pass. [magnitudes], when given, holds each
     * coefficient's magnitude in steps before the floor, which the distortion is measured against;
     * with [reversible] the last plane leaves no half step.
     */
    fun measure(
        values: IntArray, offset: Int, rowStride: Int, width: Int, height: Int, magnitudes: DoubleArray?, reversible: Boolean,
    ): CodedBlock {
        val planes = load(values, offset, rowStride, width, height)
        if (planes == 0) return CodedBlock.EMPTY
        val n = w * h
        val exact = DoubleArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            exact[y * w + x] = magnitudes?.get(offset + y * rowStride + x) ?: mags[y * w + x].toDouble()
        }
        var energy = 0.0
        for (i in 0 until n) energy += exact[i] * exact[i]
        val total = 3 * planes - 2
        val rates = IntArray(total)
        val gains = DoubleArray(total)
        val sig = BooleanArray(n)
        for (plane in planes - 1 downTo 0) {
            val set = codeSet(planes, plane, if (plane > 0) 3 else 1)
            // The squared error left by the cleanup pass alone, then what SigProp and MagRef take off it.
            var error = 0.0
            for (i in 0 until n) {
                val m = mags[i] ushr plane
                sig[i] = m != 0
                error += square(exact[i] - (if (m != 0) reconstruct(m shl plane, plane, reversible) else 0.0))
            }
            val k = if (plane == planes - 1) 0 else 3 * (planes - 2 - plane) + 3
            rates[k] = set.cleanup.size
            gains[k] = energy - error
            if (plane == 0) continue
            // SigProp at plane - 1, under the decoder's membership rule.
            var sigPropGain = 0.0
            visitSigProp(plane - 1, sig) { i ->
                if (mags[i] ushr (plane - 1) and 1 == 1) {
                    sigPropGain += square(exact[i]) - square(exact[i] - reconstruct(1 shl (plane - 1), plane - 1, reversible))
                }
            }
            var magRefGain = 0.0
            for (i in 0 until n) {
                val m = mags[i] ushr plane
                if (m == 0) continue
                magRefGain += square(exact[i] - reconstruct(m shl plane, plane, reversible)) -
                    square(exact[i] - reconstruct((mags[i] ushr (plane - 1)) shl (plane - 1), plane - 1, reversible))
            }
            // Points k + 1 and k + 2 add this set's SigProp and MagRef passes.
            val sp = k + 1
            val mr = k + 2
            if (sp < total) {
                rates[sp] = set.cleanup.size + set.sigProp.size
                gains[sp] = gains[k] + sigPropGain
            }
            if (mr < total) {
                rates[mr] = set.cleanup.size + set.sigProp.size + set.magRef.size
                gains[mr] = gains[k] + sigPropGain + magRefGain
            }
        }
        return CodedBlock(ByteArray(0), total, planes, rates, gains)
    }

    private fun square(v: Double): Double = v * v

    /** What a decoder reconstructs of a magnitude [m] whose last coded plane is [plane] (E.1.1, r = 1/2). */
    private fun reconstruct(m: Int, plane: Int, reversible: Boolean): Double =
        if (reversible) (m + ((1 shl plane) shr 1)).toDouble() else m + (1 shl plane) * 0.5

    // ---- the cleanup pass -----------------------------------------------------------------------

    private fun codeSet(planes: Int, plane: Int, passes: Int): HtSet {
        val cleanup = cleanup(plane)
        if (passes == 1) return HtSet(planes, cleanup, ByteArray(0), ByteArray(0))
        val sig = BooleanArray(w * h) { mags[it] ushr plane != 0 }
        val clean = sig.copyOf()
        val sigProp = ForwardWriter(fill = 0)
        val fresh = IntArray(16)
        val b = plane - 1
        var y0 = 0
        // The same walk as visitSigProp, with the group's signs after it.
        while (y0 < h) {
            val y1 = minOf(y0 + 4, h)
            var gx = 0
            while (gx < w) {
                var count = 0
                for (x in gx until minOf(gx + 4, w)) for (y in y0 until y1) {
                    val i = y * w + x
                    if (sig[i] || !hasSignificantNeighbour(sig, x, y, y0)) continue
                    val bit = (mags[i] ushr b) and 1
                    sigProp.bits(bit, 1)
                    if (bit == 1) {
                        sig[i] = true
                        fresh[count++] = i
                    }
                }
                for (k in 0 until count) sigProp.bits(if (negative[fresh[k]]) 1 else 0, 1)
                gx += 4
            }
            y0 += 4
        }
        val sp = sigProp.finish(endsWithFF = false)
        if (passes == 2) return HtSet(planes, cleanup, sp, ByteArray(0))
        val magRef = BackwardWriter()
        y0 = 0
        while (y0 < h) {
            for (x in 0 until w) for (y in y0 until minOf(y0 + 4, h)) {
                val i = y * w + x
                if (clean[i]) magRef.bits((mags[i] ushr b) and 1, 1)
            }
            y0 += 4
        }
        return HtSet(planes, cleanup, sp, magRef.finish())
    }

    /** Whether a neighbour of ([x], [y]) is significant; under the stripe-causal style the rows below the stripe of [y0] are not seen. */
    private fun hasSignificantNeighbour(sig: BooleanArray, x: Int, y: Int, y0: Int): Boolean {
        val lowest = if (stripeCausal) minOf(y0 + 4, h) - 1 else h - 1
        for (ny in y - 1..minOf(y + 1, lowest)) {
            if (ny < 0) continue
            for (nx in x - 1..x + 1) if (nx in 0 until w && (nx != x || ny != y) && sig[ny * w + nx]) return true
        }
        return false
    }

    /** Calls [visit] on every SigProp member at [plane], in the decoder's order, updating [sig] as it goes. */
    private inline fun visitSigProp(plane: Int, sig: BooleanArray, visit: (Int) -> Unit) {
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(y0 + 4, h)
            for (x in 0 until w) for (y in y0 until y1) {
                val i = y * w + x
                if (sig[i] || !hasSignificantNeighbour(sig, x, y, y0)) continue
                visit(i)
                if ((mags[i] ushr plane) and 1 == 1) sig[i] = true
            }
            y0 += 4
        }
    }

    private fun cleanup(p: Int): ByteArray {
        val magSgn = ForwardWriter(fill = 1)
        val mel = MelWriter()
        val vlc = VlcWriter()
        val qw = (w + 1) / 2
        val above = IntArray(w)
        val row = IntArray(w)
        val rho = IntArray(2)
        val bound = IntArray(2)
        val offset = IntArray(2)
        val chosen = IntArray(2)
        val v = IntArray(8)
        val e = IntArray(8)
        var y0 = 0
        while (y0 < h) {
            val initial = y0 == 0
            val table = if (initial) HtVlc.encodeInitial else HtVlc.encodeLater
            var left = 0
            row.fill(0)
            var g = 0
            while (g < qw) {
                val pair = if (g + 1 < qw) 2 else 1
                rho[1] = 0
                offset[1] = 0
                for (j in 0 until pair) {
                    val x = 2 * (g + j)
                    var r = 0
                    var emax = 0
                    for (n in 0 until 4) {
                        val sx = x + (n ushr 1)
                        val sy = y0 + (n and 1)
                        val m = if (sx < w && sy < h) mags[sy * w + sx] ushr p else 0
                        v[4 * j + n] = 0
                        e[4 * j + n] = 0
                        if (m == 0) continue
                        r = r or (1 shl n)
                        val vn = 2 * (m - 1) + (if (negative[sy * w + sx]) 1 else 0)
                        v[4 * j + n] = vn
                        e[4 * j + n] = 32 - (vn or 1).countLeadingZeroBits()
                        if (e[4 * j + n] > emax) emax = e[4 * j + n]
                    }
                    val c = if (initial) {
                        (left and 1) or ((left ushr 1) and 1) or (((left ushr 2) and 1) shl 1) or (((left ushr 3) and 1) shl 2)
                    } else {
                        (sigAt(above, x - 1) or sigAt(above, x)) or
                            ((((left ushr 2) or (left ushr 3)) and 1) shl 1) or ((sigAt(above, x + 1) or sigAt(above, x + 2)) shl 2)
                    }
                    // The exponent bound the decoder infers before any offset: 1, or for a quad of two or
                    // more significant samples, one less than the largest exponent above it.
                    var kappa = 1
                    if (!initial && r and (r - 1) != 0) {
                        var ea = 0
                        for (px in x - 1..x + 2) if (px in 0 until w && above[px] > ea) ea = above[px]
                        if (ea - 1 > kappa) kappa = ea - 1
                    }
                    rho[j] = r
                    left = r
                    offset[j] = 0
                    // In context 0 a MEL symbol says whether the quad is significant, and only then a codeword follows.
                    if (c == 0) {
                        mel.symbol(if (r != 0) 1 else 0)
                        if (r == 0) continue
                    }
                    val u = if (emax > kappa) 1 else 0
                    bound[j] = maxOf(emax, kappa)
                    if (u == 1) offset[j] = emax - kappa
                    chosen[j] = pick(table[(c * 16 + r) * 2 + u], v, 4 * j, bound[j])
                    vlc.bits(HtVlc.codeword(chosen[j]), HtVlc.length(chosen[j]))
                }
                codeU(offset[0], offset[1], initial, vlc, mel)
                for (j in 0 until pair) {
                    if (rho[j] == 0) continue
                    val ek = HtVlc.ek(chosen[j])
                    val x = 2 * (g + j)
                    for (n in 0 until 4) {
                        if (rho[j] ushr n and 1 == 0) continue
                        val m = bound[j] - (ek ushr n and 1)
                        val vn = v[4 * j + n]
                        if (m > 16) { magSgn.bits(vn and 0xFFFF, 16); magSgn.bits(vn ushr 16, m - 16) } else magSgn.bits(vn, m)
                        if (n and 1 == 1) row[x + (n ushr 1)] = e[4 * j + n]
                    }
                }
                g += 2
            }
            for (x in 0 until w) above[x] = row[x]
            y0 += 2
        }
        val ms = magSgn.finish(endsWithFF = true)
        val melBytes = mel.finish()
        val vlcBytes = vlc.finish()
        val scup = melBytes.size + vlcBytes.size + 1
        // A block of 4096 samples codes at most about 2.6 KB of MEL and VLC, below this bound.
        check(scup <= 4079) { "HT MEL and VLC of $scup bytes" }
        val out = ByteArray(ms.size + scup)
        ms.copyInto(out, 0)
        melBytes.copyInto(out, ms.size)
        var o = out.size - 2
        for (b in vlcBytes) out[o--] = b
        // Scup in the last byte and the low four bits of the one before, where VLC left them free.
        out[out.size - 2] = ((out[out.size - 2].toInt() and 0xF0) or (scup and 0x0F)).toByte()
        out[out.size - 1] = (scup ushr 4).toByte()
        return out
    }

    private fun sigAt(above: IntArray, x: Int): Int = if (x in 0 until w && above[x] > 0) 1 else 0

    /**
     * The codeword among [rows] that leaves the fewest bits: each e_k bit it sets spares the top bit
     * of that sample's MagSgn value, which e_1 then gives, so it must match the value.
     */
    private fun pick(rows: IntArray, v: IntArray, base: Int, u: Int): Int {
        var best = -1
        var bestCost = Int.MAX_VALUE
        for (row in rows) {
            val ek = HtVlc.ek(row)
            val e1 = HtVlc.e1(row)
            var ok = true
            for (n in 0 until 4) {
                if (ek ushr n and 1 == 0) continue
                if ((v[base + n] ushr (u - 1)) and 1 != (e1 ushr n and 1)) { ok = false; break }
            }
            if (!ok) continue
            val cost = HtVlc.length(row) - ek.countOneBits()
            if (cost < bestCost) { best = row; bestCost = cost }
        }
        check(best >= 0) { "no HT codeword fits a quad" }
        return best
    }

    /** The exponent offsets [ua] and [ub] of a quad pair, 0 for a quad without one, as [HtBlockDecoder] reads them. */
    private fun codeU(ua: Int, ub: Int, initial: Boolean, vlc: VlcWriter, mel: MelWriter) {
        if (initial && ua > 0 && ub > 0) {
            if (ua > 2 && ub > 2) {
                mel.symbol(1)
                prefix(vlc, ua - 2); prefix(vlc, ub - 2); suffix(vlc, ua - 2); suffix(vlc, ub - 2)
            } else {
                mel.symbol(0)
                prefix(vlc, ua)
                if (ua > 2) {
                    vlc.bits(ub - 1, 1)
                } else {
                    prefix(vlc, ub)
                }
                suffix(vlc, ua)
                if (ua <= 2) suffix(vlc, ub)
            }
        } else {
            if (ua > 0) prefix(vlc, ua)
            if (ub > 0) prefix(vlc, ub)
            if (ua > 0) suffix(vlc, ua)
            if (ub > 0) suffix(vlc, ub)
        }
    }

    /** The U-VLC prefix of [u]: 1, 01, 001 or 000, first bit lowest. */
    private fun prefix(vlc: VlcWriter, u: Int) {
        check(u in 1..36) { "an HT exponent offset of $u" }
        when {
            u == 1 -> vlc.bits(1, 1)
            u == 2 -> vlc.bits(2, 2)
            u <= 4 -> vlc.bits(4, 3)
            else -> vlc.bits(0, 3)
        }
    }

    private fun suffix(vlc: VlcWriter, u: Int) {
        when {
            u in 3..4 -> vlc.bits(u - 3, 1)
            u >= 5 -> vlc.bits(u - 5, 5)
        }
    }

    // ---- the writers ----------------------------------------------------------------------------

    /**
     * MagSgn and SigProp: bits from the lowest up, forward; a byte after 0xFF takes 7. The last byte
     * is padded with [fill] bits.
     */
    private class ForwardWriter(private val fill: Int) {
        private val out = Jp2ByteBuffer(64)
        private var acc = 0
        private var count = 0
        private var room = 8

        fun bits(v: Int, n: Int) {
            var value = v
            var left = n
            while (left > 0) {
                val take = minOf(left, room - count)
                acc = acc or ((value and ((1 shl take) - 1)) shl count)
                count += take
                value = value ushr take
                left -= take
                if (count == room) {
                    out.add(acc)
                    room = if (acc == 0xFF) 7 else 8
                    acc = 0
                    count = 0
                }
            }
        }

        /**
         * The bytes. With [endsWithFF] a final 0xFF is left off: the decoder reads 0xFF past the end
         * of MagSgn. Otherwise a final 0xFF gets a stuffed zero byte after it, so no marker can form
         * with what follows.
         */
        fun finish(endsWithFF: Boolean): ByteArray {
            if (count > 0) out.add(acc or (if (fill == 1) ((1 shl room) - 1) and ((1 shl count) - 1).inv() else 0))
            var size = out.size
            if (size > 0 && out[size - 1] == 0xFF) {
                if (endsWithFF) size-- else out.add(0)
            }
            return if (size == out.size) out.toByteArray() else out.toByteArray().copyOf(size)
        }
    }

    /**
     * VLC: bits from the lowest up, written from the end of the segment back. It starts after the
     * four bits that Scup takes, and a byte whose low 7 bits are all 1 after a byte above 0x8F takes
     * 7, its top bit a stuffed 0. The byte after it, which holds Scup, counts as above 0x8F.
     */
    private class VlcWriter {
        private val out = Jp2ByteBuffer(64)
        private var acc = 0x0F
        private var count = 4
        private var after8F = true

        fun bits(v: Int, n: Int) {
            acc = acc or ((v and ((1 shl n) - 1)) shl count)
            count += n
            while (count >= 8) emit()
        }

        private fun emit() {
            val b: Int
            if (after8F && acc and 0x7F == 0x7F) {
                b = 0x7F
                acc = acc ushr 7
                count -= 7
            } else {
                b = acc and 0xFF
                acc = acc ushr 8
                count -= 8
            }
            out.add(b)
            after8F = b > 0x8F
        }

        /** The bytes in the order written, the last byte of the segment's VLC first. */
        fun finish(): ByteArray {
            if (count > 0) {
                var b = acc and 0xFF
                if (after8F && b and 0x7F == 0x7F) b = b and 0x7F
                out.add(b)
            }
            return out.toByteArray()
        }
    }

    /** MagRef: bits from the lowest up, from the end of the refinement segment back, stuffed as VLC is. */
    private class BackwardWriter {
        private val out = Jp2ByteBuffer(64)
        private var acc = 0
        private var count = 0
        private var after8F = true

        fun bits(v: Int, n: Int) {
            repeat(n) { k ->
                acc = acc or (((v ushr k) and 1) shl count)
                count++
                if (count == 7 && after8F && acc == 0x7F) {
                    out.add(0x7F)
                    after8F = false
                    acc = 0
                    count = 0
                } else if (count == 8) {
                    out.add(acc)
                    after8F = acc > 0x8F
                    acc = 0
                    count = 0
                }
            }
        }

        fun finish(): ByteArray {
            if (count > 0) out.add(acc)
            val bytes = out.toByteArray()
            bytes.reverse()
            return bytes
        }
    }

    /** MEL: the run-length coder of quad significance, bits from the top down. */
    private class MelWriter {
        private val out = Jp2ByteBuffer(32)
        private var acc = 0
        private var count = 0
        private var room = 8
        private var k = 0
        private var run = 0

        private fun bit(b: Int) {
            acc = (acc shl 1) or b
            count++
            if (count == room) {
                out.add(acc)
                room = if (acc == 0xFF) 7 else 8
                acc = 0
                count = 0
            }
        }

        fun symbol(s: Int) {
            val e = HtVlc.MEL_EXPONENT[k]
            if (s == 0) {
                run++
                if (run == 1 shl e) {
                    bit(1)
                    run = 0
                    if (k < 12) k++
                }
            } else {
                bit(0)
                for (i in e - 1 downTo 0) bit((run ushr i) and 1)
                run = 0
                if (k > 0) k--
            }
        }

        /** The bytes: a run left open ends as a full one, which the decoder never needs to finish. */
        fun finish(): ByteArray {
            if (run > 0) bit(1)
            if (count > 0) out.add(acc shl (room - count))
            else if (out.size > 0 && out[out.size - 1] == 0xFF) out.add(0)
            return out.toByteArray()
        }
    }
}
