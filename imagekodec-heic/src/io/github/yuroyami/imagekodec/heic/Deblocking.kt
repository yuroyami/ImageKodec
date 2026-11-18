package io.github.yuroyami.imagekodec.heic

/**
 * The deblocking filter of 8.7.2, in place on the picture of [d], one CTB row at a time: first the
 * vertical edges of the row, then its horizontal edges, the top edge of the row included. Edges sit on
 * the 8x8 grid (8.7.2), so the samples one edge filters are never those another edge reads, and a row's
 * vertical edges never touch the lines of the rows around it. The edges themselves are the transform and
 * prediction block edges the slice decoder marked in its 4x4 units.
 */
internal class Deblocker(private val d: PictureDecoder) {
    private val sps = d.sps
    private val pps = d.pps
    private val width = d.width
    private val height = d.height
    private val w4 = d.width shr 2
    private val ctbLog2 = sps.ctbLog2
    private val ctbSize = 1 shl ctbLog2
    private val widthInCtbs = sps.picWidthInCtbs
    private val chromaArrayType = d.chromaArrayType
    private val subW = d.subWShift
    private val subH = d.subHShift
    private val flags = d.flags
    private val qpY = d.qpY
    private val bitDepthY = sps.bitDepthY
    private val bitDepthC = sps.bitDepthC
    private val maxY = (1 shl bitDepthY) - 1
    private val maxC = (1 shl bitDepthC) - 1

    /** 8.7.2.5.7 and 8.7.2.5.8: the units whose samples both filters leave as they are. */
    private val keepMask = PictureDecoder.FLAG_BYPASS or (if (sps.pcmLoopFilterDisabled) PictureDecoder.FLAG_PCM else 0) or PALETTE
    private val palette = d.paletteUnits

    /**
     * The flags [f] of unit [u] as the unsigned byte they are (FLAG_CODED makes the Byte negative), with [PALETTE]
     * for a unit of a coding unit in palette mode.
     */
    private fun kept(u: Int, f: Int): Int = (f and 0xFF) or (if (palette != null && palette[u]) PALETTE else 0)

    private val beta = HevcTables.BETA_PRIME
    private val tcTable = HevcTables.TC_PRIME

    /** Filters the edges of CTB row [ry] (8.7.2.1). */
    fun filterRow(ry: Int) {
        val y0 = ry shl ctbLog2
        val y1 = minOf(y0 + ctbSize, height)
        for (rx in 0 until widthInCtbs) verticalEdges(ry * widthInCtbs + rx, rx shl ctbLog2, y0, y1)
        for (rx in 0 until widthInCtbs) horizontalEdges(ry * widthInCtbs + rx, rx shl ctbLog2, y0, y1)
    }

    /**
     * Whether the edge between the CTB [rs] and its neighbour [rsN] is filtered: filterLeftCbEdgeFlag
     * and filterTopCbEdgeFlag of 8.7.2.1, with the flag of the slice of the current CTB.
     */
    private fun acrossCtbs(rs: Int, rsN: Int, h: SliceHeader): Boolean {
        if (!pps.loopFilterAcrossTiles && d.tileIdRs[rs] != d.tileIdRs[rsN]) return false
        return h.loopFilterAcrossSlices || d.ctbSlice[rs] == d.ctbSlice[rsN]
    }

    /**
     * bS of 8.7.2.4 for the edge between the units [up] and [uq], whose flags are [fp] and [fq]; [transform]
     * is the flag that marks the edge of [uq] as a transform block edge. 2 with an intra block on either
     * side; 1 at a transform block edge with coefficients on either side, or when the two blocks predict
     * from different pictures, from a different number of vectors, or with vectors 4 quarter samples apart.
     */
    private fun strength(up: Int, uq: Int, fp: Int, fq: Int, transform: Int): Int {
        if ((fp or fq) and PictureDecoder.FLAG_INTRA != 0) return 2
        if (fq and transform != 0 && (fp or fq) and PictureDecoder.FLAG_CODED != 0) return 1
        val m = d.motion ?: return 0
        val pr0 = m.ref[2 * up] >= 0
        val pr1 = m.ref[2 * up + 1] >= 0
        val qr0 = m.ref[2 * uq] >= 0
        val qr1 = m.ref[2 * uq + 1] >= 0
        val np = (if (pr0) 1 else 0) + (if (pr1) 1 else 0)
        val nq = (if (qr0) 1 else 0) + (if (qr1) 1 else 0)
        if (np != nq) return 1
        // NOTE 1 of 8.7.2.4: the pictures count, not the lists or indices that name them.
        if (np == 1) {
            val pl = if (pr0) 0 else 1
            val ql = if (qr0) 0 else 1
            if (m.refPoc[2 * up + pl] != m.refPoc[2 * uq + ql]) return 1
            return if (far(m.mv[2 * up + pl], m.mv[2 * uq + ql])) 1 else 0
        }
        val p0 = m.refPoc[2 * up]
        val p1 = m.refPoc[2 * up + 1]
        val q0 = m.refPoc[2 * uq]
        val q1 = m.refPoc[2 * uq + 1]
        if (!((p0 == q0 && p1 == q1) || (p0 == q1 && p1 == q0))) return 1
        val mp0 = m.mv[2 * up]
        val mp1 = m.mv[2 * up + 1]
        val mq0 = m.mv[2 * uq]
        val mq1 = m.mv[2 * uq + 1]
        if (p0 != p1) {
            // Two different pictures: compare the vectors that point into the same one.
            return if (p0 == q0) (if (far(mp0, mq0) || far(mp1, mq1)) 1 else 0) else (if (far(mp0, mq1) || far(mp1, mq0)) 1 else 0)
        }
        // Both vectors of each block point into one picture: either pairing may match.
        return if ((far(mp0, mq0) || far(mp1, mq1)) && (far(mp0, mq1) || far(mp1, mq0))) 1 else 0
    }

    /** The horizontal or the vertical components of two packed vectors differ by 4 quarter samples or more. */
    private fun far(a: Int, b: Int): Boolean =
        abs(MotionField.x(a) - MotionField.x(b)) >= 4 || abs(MotionField.y(a) - MotionField.y(b)) >= 4

    private fun verticalEdges(rs: Int, x0: Int, y0: Int, y1: Int) {
        val h = d.ctbHeader[rs] ?: return
        if (h.deblockingFilterDisabled) return
        val x1 = minOf(x0 + ctbSize, width)
        val first = if (x0 > 0 && acrossCtbs(rs, rs - 1, h)) x0 else x0 + 8
        val betaOffset = h.betaOffsetDiv2 shl 1
        val tcOffset = h.tcOffsetDiv2 shl 1
        val luma = d.planes[0]
        val stride = d.strides[0]
        var x = first
        while (x < x1) {
            var y = y0
            while (y < y1) {
                val uq = (y shr 2) * w4 + (x shr 2)
                val fq = flags[uq].toInt()
                if (fq and PictureDecoder.FLAG_EDGE_LEFT != 0) {
                    val fp = flags[uq - 1].toInt()
                    val bs = strength(uq - 1, uq, fp, fq, PictureDecoder.FLAG_TRANSFORM_LEFT)
                    if (bs > 0) filterLuma(luma, y * stride + x, 1, stride, bs, qpY[uq - 1].toInt(), qpY[uq].toInt(), betaOffset, tcOffset, kept(uq - 1, fp), kept(uq, fq))
                }
                y += 4
            }
            x += 8
        }
        if (chromaArrayType == 0) return
        // 8.7.2.5.1: chroma edges on the 8x8 grid of the chroma samples, each four chroma lines long.
        val step = 8 shl subW
        x = if (first == x0) x0 else x0 + step
        while (x < x1) {
            var yc = y0 shr subH
            val yc1 = y1 shr subH
            while (yc < yc1) {
                val y = yc shl subH
                val uq = (y shr 2) * w4 + (x shr 2)
                val fq = flags[uq].toInt()
                if (fq and PictureDecoder.FLAG_EDGE_LEFT != 0) {
                    val fp = flags[uq - 1].toInt()
                    if ((fp or fq) and PictureDecoder.FLAG_INTRA != 0) {
                        val at = yc * d.strides[1] + (x shr subW)
                        val qpP = qpY[uq - 1].toInt()
                        val qpQ = qpY[uq].toInt()
                        filterChroma(d.planes[1], at, 1, d.strides[1], qpP, qpQ, pps.cbQpOffset, tcOffset, kept(uq - 1, fp), kept(uq, fq))
                        filterChroma(d.planes[2], at, 1, d.strides[2], qpP, qpQ, pps.crQpOffset, tcOffset, kept(uq - 1, fp), kept(uq, fq))
                    }
                }
                yc += 4
            }
            x += step
        }
    }

    private fun horizontalEdges(rs: Int, x0: Int, y0: Int, y1: Int) {
        val h = d.ctbHeader[rs] ?: return
        if (h.deblockingFilterDisabled) return
        val x1 = minOf(x0 + ctbSize, width)
        val first = if (y0 > 0 && acrossCtbs(rs, rs - widthInCtbs, h)) y0 else y0 + 8
        val betaOffset = h.betaOffsetDiv2 shl 1
        val tcOffset = h.tcOffsetDiv2 shl 1
        val luma = d.planes[0]
        val stride = d.strides[0]
        var y = first
        while (y < y1) {
            val row = (y shr 2) * w4
            var x = x0
            while (x < x1) {
                val uq = row + (x shr 2)
                val fq = flags[uq].toInt()
                if (fq and PictureDecoder.FLAG_EDGE_TOP != 0) {
                    val fp = flags[uq - w4].toInt()
                    val bs = strength(uq - w4, uq, fp, fq, PictureDecoder.FLAG_TRANSFORM_TOP)
                    if (bs > 0) filterLuma(luma, y * stride + x, stride, 1, bs, qpY[uq - w4].toInt(), qpY[uq].toInt(), betaOffset, tcOffset, kept(uq - w4, fp), kept(uq, fq))
                }
                x += 4
            }
            y += 8
        }
        if (chromaArrayType == 0) return
        // 8.7.2.5.2: the same grid across the rows of chroma samples.
        val step = 8 shl subH
        y = if (first == y0) y0 else y0 + step
        while (y < y1) {
            val row = (y shr 2) * w4
            var xc = x0 shr subW
            val xc1 = x1 shr subW
            while (xc < xc1) {
                val uq = row + ((xc shl subW) shr 2)
                val fq = flags[uq].toInt()
                if (fq and PictureDecoder.FLAG_EDGE_TOP != 0) {
                    val fp = flags[uq - w4].toInt()
                    // bS 2, the only strength a chroma edge is filtered at: an intra block on either side.
                    if ((fp or fq) and PictureDecoder.FLAG_INTRA != 0) {
                        val at = (y shr subH) * d.strides[1] + xc
                        val qpP = qpY[uq - w4].toInt()
                        val qpQ = qpY[uq].toInt()
                        filterChroma(d.planes[1], at, d.strides[1], 1, qpP, qpQ, pps.cbQpOffset, tcOffset, kept(uq - w4, fp), kept(uq, fq))
                        filterChroma(d.planes[2], at, d.strides[2], 1, qpP, qpQ, pps.crQpOffset, tcOffset, kept(uq - w4, fp), kept(uq, fq))
                    }
                }
                xc += 4
            }
            y += step
        }
    }

    /**
     * One luma edge segment of four lines (8.7.2.5.3, 8.7.2.5.4, 8.7.2.5.6 and 8.7.2.5.7): q0 of the
     * first line at [at], [across] the step from p0 to q0 and [along] the step from line to line.
     */
    private fun filterLuma(s: ShortArray, at: Int, across: Int, along: Int, bS: Int, qpP: Int, qpQ: Int, betaOffset: Int, tcOffset: Int, fp: Int, fq: Int) {
        val qpL = (qpQ + qpP + 1) shr 1
        val b = beta[clip(0, 51, qpL + betaOffset)] shl (bitDepthY - 8)
        val tc = tcTable[clip(0, 53, qpL + 2 * (bS - 1) + tcOffset)] shl (bitDepthY - 8)
        // With tC 0 every filter below clips its change to nothing.
        if (tc == 0) return
        val a3 = at + 3 * along
        val dp0 = abs(u(s, at - 3 * across) - 2 * u(s, at - 2 * across) + u(s, at - across))
        val dp3 = abs(u(s, a3 - 3 * across) - 2 * u(s, a3 - 2 * across) + u(s, a3 - across))
        val dq0 = abs(u(s, at + 2 * across) - 2 * u(s, at + across) + u(s, at))
        val dq3 = abs(u(s, a3 + 2 * across) - 2 * u(s, a3 + across) + u(s, a3))
        val dpq0 = dp0 + dq0
        val dpq3 = dp3 + dq3
        if (dpq0 + dpq3 >= b) return
        val strong = decision(s, at, across, 2 * dpq0, b, tc) && decision(s, a3, across, 2 * dpq3, b, tc)
        val side = (b + (b shr 1)) shr 3
        val dEp = dp0 + dp3 < side
        val dEq = dq0 + dq3 < side
        val keepP = fp and keepMask != 0
        val keepQ = fq and keepMask != 0
        val max = maxY
        for (k in 0 until 4) {
            val q0i = at + k * along
            val p0 = s[q0i - across].toInt() and 0xFFFF
            val p1 = s[q0i - 2 * across].toInt() and 0xFFFF
            val p2 = s[q0i - 3 * across].toInt() and 0xFFFF
            val q0 = s[q0i].toInt() and 0xFFFF
            val q1 = s[q0i + across].toInt() and 0xFFFF
            val q2 = s[q0i + 2 * across].toInt() and 0xFFFF
            if (strong) {
                // 8-389 to 8-394.
                val tc2 = 2 * tc
                if (!keepP) {
                    val p3 = s[q0i - 4 * across].toInt() and 0xFFFF
                    s[q0i - across] = clip(p0 - tc2, p0 + tc2, (p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) shr 3).toShort()
                    s[q0i - 2 * across] = clip(p1 - tc2, p1 + tc2, (p2 + p1 + p0 + q0 + 2) shr 2).toShort()
                    s[q0i - 3 * across] = clip(p2 - tc2, p2 + tc2, (2 * p3 + 3 * p2 + p1 + p0 + q0 + 4) shr 3).toShort()
                }
                if (!keepQ) {
                    val q3 = s[q0i + 3 * across].toInt() and 0xFFFF
                    s[q0i] = clip(q0 - tc2, q0 + tc2, (p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) shr 3).toShort()
                    s[q0i + across] = clip(q1 - tc2, q1 + tc2, (p0 + q0 + q1 + q2 + 2) shr 2).toShort()
                    s[q0i + 2 * across] = clip(q2 - tc2, q2 + tc2, (p0 + q0 + q1 + 3 * q2 + 2 * q3 + 4) shr 3).toShort()
                }
            } else {
                // 8-395 to 8-402.
                var delta = (9 * (q0 - p0) - 3 * (q1 - p1) + 8) shr 4
                if (abs(delta) >= tc * 10) continue
                delta = clip(-tc, tc, delta)
                val half = tc shr 1
                if (!keepP) {
                    s[q0i - across] = clip(0, max, p0 + delta).toShort()
                    if (dEp) s[q0i - 2 * across] = clip(0, max, p1 + clip(-half, half, (((p2 + p0 + 1) shr 1) - p1 + delta) shr 1)).toShort()
                }
                if (!keepQ) {
                    s[q0i] = clip(0, max, q0 - delta).toShort()
                    if (dEq) s[q0i + across] = clip(0, max, q1 + clip(-half, half, (((q2 + q0 + 1) shr 1) - q1 - delta) shr 1)).toShort()
                }
            }
        }
    }

    /** dSam of 8.7.2.5.6 for the line whose q0 is at [q0i]. */
    private fun decision(s: ShortArray, q0i: Int, across: Int, dpq: Int, b: Int, tc: Int): Boolean {
        val p0 = s[q0i - across].toInt() and 0xFFFF
        val p3 = s[q0i - 4 * across].toInt() and 0xFFFF
        val q0 = s[q0i].toInt() and 0xFFFF
        val q3 = s[q0i + 3 * across].toInt() and 0xFFFF
        return dpq < (b shr 2) && abs(p3 - p0) + abs(q0 - q3) < (b shr 3) && abs(p0 - q0) < ((5 * tc + 1) shr 1)
    }

    /** One chroma edge segment of four lines, 8.7.2.5.5 and 8.7.2.5.8, laid out as in [filterLuma]. */
    private fun filterChroma(s: ShortArray, at: Int, across: Int, along: Int, qpP: Int, qpQ: Int, cQpPicOffset: Int, tcOffset: Int, fp: Int, fq: Int) {
        val qPi = ((qpQ + qpP + 1) shr 1) + cQpPicOffset
        val qpC = if (chromaArrayType == 1) {
            // Table 8-10.
            if (qPi < 30) qPi else if (qPi > 43) qPi - 6 else HevcTables.QPC_30_TO_43[qPi - 30]
        } else {
            minOf(qPi, 51)
        }
        // 8-383 with bS 2, the only strength a chroma edge is filtered at.
        val tc = tcTable[clip(0, 53, qpC + 2 + tcOffset)] shl (bitDepthC - 8)
        if (tc == 0) return
        val keepP = fp and keepMask != 0
        val keepQ = fq and keepMask != 0
        val max = maxC
        for (k in 0 until 4) {
            val q0i = at + k * along
            val p0 = s[q0i - across].toInt() and 0xFFFF
            val p1 = s[q0i - 2 * across].toInt() and 0xFFFF
            val q0 = s[q0i].toInt() and 0xFFFF
            val q1 = s[q0i + across].toInt() and 0xFFFF
            val delta = clip(-tc, tc, (((q0 - p0) shl 2) + p1 - q1 + 4) shr 3)
            if (!keepP) s[q0i - across] = clip(0, max, p0 + delta).toShort()
            if (!keepQ) s[q0i] = clip(0, max, q0 - delta).toShort()
        }
    }

    private companion object {
        /** A bit past the unit flags that marks a unit in palette mode, which the filters keep as it is. */
        const val PALETTE = 256

        fun clip(lo: Int, hi: Int, v: Int): Int = if (v < lo) lo else if (v > hi) hi else v
        fun abs(v: Int): Int = if (v < 0) -v else v

        /** The sample at [i], unsigned: one of 16 bits sits in its Short as a negative value. */
        fun u(s: ShortArray, i: Int): Int = s[i].toInt() and 0xFFFF
    }
}
