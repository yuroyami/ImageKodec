package io.github.yuroyami.imagekodec.codec.jxr

/**
 * The forward transform of a JPEG XR encoder: the overlap pre-filters and the forward core
 * transform of ITU-T T.832 Annex D, on whole image planes.
 *
 * Every operation here is the exact inverse of an operation the decoder runs, so a plane coded
 * with a quantizer of 1 decodes to itself. The pre-filters follow Annex D.5 and are laid out on
 * the plane as clause 9.9.3 and 9.9.6 lay out the post-filters: a 4x4 filter where four blocks
 * meet, a 4-point filter across each block edge along the two outer rows and columns, and a
 * 4-point filter on each 2x2 corner. Soft tiles filter across tile edges, so one image-wide
 * layout serves every tiling. The core transforms undo [JxrTransform.idct4x4Stage1] and
 * [JxrTransform.idct4x4Stage2] step by step, so the coefficients come out where the decoder
 * reads them.
 */
internal object JxrForward {

    // --- Lifting primitives of Annex D.4 and D.5 ----------------------------------------------

    /** TOdd (Table D.9), the inverse of the decoder's invOdd on the same four samples. */
    private fun fwdOdd(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        a += d
        b -= c
        d = ((a + 1) shr 1) - d
        c += (b + 1) shr 1
        d -= (c * 3 + 4) shr 3
        c += (d * 3 + 4) shr 3
        b -= (a * 3 + 4) shr 3
        a += (b * 3 + 4) shr 3
        c -= (a + 1) shr 1
        d += b shr 1
        a += c
        b -= d
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    /** TOddOdd (Table D.10), the inverse of the decoder's invOddOdd, whose outputs b and c are negated. */
    private fun fwdOddOdd(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = -p[ib]
        var c = -p[ic]
        var d = p[id]
        d += a
        c -= b
        val t1 = d shr 1
        val t2 = c shr 1
        a -= t1
        b += t2
        a += (b * 3 + 4) shr 3
        b -= (a * 3 + 3) shr 2
        a += (b * 3 + 3) shr 3
        b -= t2
        a += t1
        c += b
        d -= a
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    /** FwdTOddOdd (Table D.17), the inverse of the post-filter's rotation of the 4x4 overlap filter. */
    private fun fwdOddOddPre(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        d += a
        c -= b
        val t1 = d shr 1
        val t2 = c shr 1
        a -= t1
        b += t2
        a += (b * 3 + 4) shr 3
        b -= (a * 3 + 2) shr 2
        a += (b * 3 + 6) shr 3
        b -= t2
        a += t1
        c += b
        d -= a
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    /** T2x2h with rounding 0 (clause 9.9.7.2), which is its own inverse. */
    private fun t2x2h(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) = JxrTransform.dct2x2dn(p, ia, ib, ic, id)

    /** T2x2h with rounding 1, which is its own inverse as well. */
    private fun t2x2hUp(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        val cc = p[ic]
        var d = p[id]
        a += d
        b -= cc
        val t = (a - b + 1) shr 1
        val c = t - d
        d = t - cc
        a -= d
        b += c
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    /** T2x2hEnc (Table D.14). */
    private fun t2x2hEnc(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        a += p[id]
        b -= p[ic]
        val t1 = p[id]
        val t2 = p[ic]
        val c = ((a - b) shr 1) - t1
        val d = t2 + (b shr 1)
        b += c
        a -= (d * 3 + 4) shr 3
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    /** FwdRotate (Table D.15). */
    private fun fwdRotate(p: IntArray, ia: Int, ib: Int) {
        p[ib] -= (p[ia] + 1) shr 1
        p[ia] += (p[ib] + 1) shr 1
    }

    /** FwdScale (Table D.16). */
    private fun fwdScale(p: IntArray, ia: Int, ib: Int) {
        var a = p[ia]
        var b = p[ib]
        b -= (a * 3) shr 4
        b -= a shr 7
        b += a shr 10
        a -= (b * 3) shr 3
        b = (a shr 1) - b
        a -= b
        p[ia] = a
        p[ib] = b
    }

    /** OverlapPreFilter4 (Table D.19) on four samples. */
    fun preFilter4(p: IntArray, i0: Int, i1: Int, i2: Int, i3: Int) {
        p[i0] += p[i3]
        p[i1] += p[i2]
        p[i3] -= (p[i0] + 1) shr 1
        p[i2] -= (p[i1] + 1) shr 1
        fwdRotate(p, i2, i3)
        p[i3] = -p[i3]
        p[i2] = -p[i2]
        p[i0] -= p[i3]
        p[i1] -= p[i2]
        p[i3] += p[i0] shr 1
        p[i2] += p[i1] shr 1
        p[i0] -= (p[i3] * 3 + 4) shr 3
        p[i1] -= (p[i2] * 3 + 4) shr 3
        fwdScale(p, i0, i3)
        fwdScale(p, i1, i2)
        p[i3] += (p[i0] + 1) shr 1
        p[i2] += (p[i1] + 1) shr 1
        p[i0] -= p[i3]
        p[i1] -= p[i2]
    }

    /** OverlapPreFilter4x4 (Table D.18) on the 16 samples of [q], in raster order. */
    private fun preFilter4x4(q: IntArray) {
        t2x2hEnc(q, 0, 3, 12, 15)
        t2x2hEnc(q, 1, 2, 13, 14)
        t2x2hEnc(q, 4, 7, 8, 11)
        t2x2hEnc(q, 5, 6, 9, 10)
        fwdScale(q, 0, 15)
        fwdScale(q, 1, 14)
        fwdScale(q, 4, 11)
        fwdScale(q, 5, 10)
        fwdRotate(q, 13, 12)
        fwdRotate(q, 9, 8)
        fwdRotate(q, 7, 3)
        fwdRotate(q, 6, 2)
        fwdOddOddPre(q, 10, 11, 14, 15)
        t2x2h(q, 0, 12, 3, 15)
        t2x2h(q, 1, 2, 13, 14)
        t2x2h(q, 4, 7, 8, 11)
        t2x2h(q, 5, 6, 9, 10)
    }

    /** OverlapPreFilter2x2 (Table D.20). */
    private fun preFilter2x2(p: IntArray, i0: Int, i1: Int, i2: Int, i3: Int) {
        p[i0] += p[i3]
        p[i1] += p[i2]
        p[i3] -= (p[i0] + 1) shr 1
        p[i2] -= (p[i1] + 1) shr 1
        p[i1] -= (p[i0] + 2) shr 2
        p[i0] -= p[i1] shr 5
        p[i0] -= p[i1] shr 9
        p[i0] -= p[i1] shr 13
        p[i0] -= (p[i1] + 1) shr 1
        p[i1] -= (p[i0] + 2) shr 2
        p[i3] += (p[i0] + 1) shr 1
        p[i2] += (p[i1] + 1) shr 1
        p[i0] -= p[i3]
        p[i1] -= p[i2]
    }

    /** OverlapPreFilter2 (Table D.21). */
    private fun preFilter2(p: IntArray, i0: Int, i1: Int) {
        p[i1] -= (p[i0] + 2) shr 2
        p[i0] -= p[i1] shr 13
        p[i0] -= p[i1] shr 9
        p[i0] -= p[i1] shr 5
        p[i0] -= (p[i1] + 1) shr 1
        p[i1] -= (p[i0] + 2) shr 2
    }

    // --- Core transforms in the decoder's layout ----------------------------------------------

    /** The inverse of [JxrTransform.idct4x4Stage1]: one block's 16 samples, in the decoder's in-block order, to coefficients. */
    fun fct4x4(p: IntArray, o: Int) {
        for (k in 3 downTo 0) t2x2h(p, o + k, o + k + 4, o + k + 8, o + k + 12)
        fwdOddOdd(p, o + 15, o + 14, o + 13, o + 12)
        fwdOdd(p, o + 10, o + 8, o + 11, o + 9)
        fwdOdd(p, o + 5, o + 4, o + 7, o + 6)
        t2x2hUp(p, o + 0, o + 1, o + 2, o + 3)
    }

    /** The inverse of [JxrTransform.idct4x4Stage2]: the 16 block DCs of a macroblock, 16 apart, to its DC and lowpass coefficients. */
    fun fct4x4Stage2(p: IntArray, o: Int) {
        t2x2h(p, o + 80, o + 144, o + 96, o + 160)
        t2x2h(p, o + 16, o + 208, o + 32, o + 224)
        t2x2h(p, o + 64, o + 128, o + 112, o + 176)
        t2x2h(p, o + 0, o + 192, o + 48, o + 240)
        t2x2hUp(p, o + 0, o + 64, o + 16, o + 80)
        fwdOddOdd(p, o + 160, o + 224, o + 176, o + 240)
        fwdOdd(p, o + 128, o + 192, o + 144, o + 208)
        fwdOdd(p, o + 32, o + 48, o + 96, o + 112)
    }

    // --- Planes ---------------------------------------------------------------------------------

    /**
     * Pre-filters a raster plane of [w] by [h] samples, both multiples of 4 and at least 8 by 8,
     * as clause 9.9.6 post-filters it, with [blockSide] samples to a block: 4 for the samples,
     * where the filter straddles block corners at an offset of 2.
     */
    fun preFilterPlane(p: IntArray, w: Int, h: Int) {
        val q = IntArray(16)
        // Interior: a 4x4 filter centred on every corner where four blocks meet.
        var y = 2
        while (y + 4 <= h - 2) {
            var x = 2
            while (x + 4 <= w - 2) {
                for (r in 0 until 4) for (c in 0 until 4) q[r * 4 + c] = p[(y + r) * w + x + c]
                preFilter4x4(q)
                for (r in 0 until 4) for (c in 0 until 4) p[(y + r) * w + x + c] = q[r * 4 + c]
                x += 4
            }
            y += 4
        }
        // The two outer columns on each side, across each block edge down them.
        y = 2
        while (y + 4 <= h - 2) {
            for (x in intArrayOf(0, 1, w - 2, w - 1)) {
                val at = y * w + x
                preFilter4(p, at, at + w, at + 2 * w, at + 3 * w)
            }
            y += 4
        }
        // The two outer rows at top and bottom, across each block edge along them.
        var x = 2
        while (x + 4 <= w - 2) {
            for (yy in intArrayOf(0, 1, h - 2, h - 1)) {
                val at = yy * w + x
                preFilter4(p, at, at + 1, at + 2, at + 3)
            }
            x += 4
        }
        // The four 2x2 corners.
        for ((cx, cy) in arrayOf(0 to 0, w - 2 to 0, 0 to h - 2, w - 2 to h - 2)) {
            val at = cy * w + cx
            preFilter4(p, at, at + 1, at + w, at + w + 1)
        }
    }

    /**
     * Pre-filters the DC plane of a 4:2:0 chroma channel, two by two values a macroblock, of [w]
     * by [h] values (clause 9.9.3.4): a 2x2 filter where four macroblocks meet, 2-point filters
     * along the outer rows and columns, and at the corners the difference with the neighbour
     * along the row that the decoder adds back (OverlapPostFilter1).
     */
    fun preFilterChroma420(p: IntArray, w: Int, h: Int) {
        val tl = 0
        val tr = w - 1
        val bl = (h - 1) * w
        val br = (h - 1) * w + w - 1
        // The decoder ends with the corner additions top left, top right, bottom left, bottom right; undo them backwards.
        p[br] -= p[br - 1]
        p[bl] -= p[bl + 1]
        p[tr] -= p[tr - 1]
        p[tl] -= p[tl + 1]
        var y = 1
        while (y + 1 < h) {
            var x = 1
            while (x + 1 < w) {
                val at = y * w + x
                preFilter2x2(p, at, at + 1, at + w, at + w + 1)
                x += 2
            }
            preFilter2(p, y * w, (y + 1) * w)
            preFilter2(p, y * w + w - 1, (y + 1) * w + w - 1)
            y += 2
        }
        var x = 1
        while (x + 1 < w) {
            preFilter2(p, x, x + 1)
            preFilter2(p, (h - 1) * w + x, (h - 1) * w + x + 1)
            x += 2
        }
        // The decoder starts with the corner differences in the same order; their inverse comes last, backwards.
        p[br] += p[br - 1]
        p[bl] += p[bl + 1]
        p[tr] += p[tr - 1]
        p[tl] += p[tl + 1]
    }
}
