package io.github.yuroyami.imagekodec.codec.avif

/**
 * The AV1 loop restoration process (specification section 7.17): the Wiener and self-guided
 * filters, reading UpscaledCdefFrame inside each 64-luma-row stripe and the deblocked
 * UpscaledCurrFrame for the two rows above and below it.
 *
 * The specification walks 4x4 blocks, and notes that larger blocks give the same samples as
 * long as each lies in one restoration unit. Restoration units are whole stripes high, so
 * this filters one rectangle per stripe and unit, which lets the box sums be shared.
 *
 * It works in place: a stripe reads no row of another stripe's CDEF output, only the deblocked
 * rows around it, which [savedRows] keeps before CDEF runs, so each stripe is filtered from a
 * copy of its own rows into the frame.
 */
internal object Av1LoopRestoration {
    private const val FILTER_BITS = 7
    private const val SGRPROJ_RST_BITS = 4
    private const val SGRPROJ_PRJ_BITS = 7
    private const val SGRPROJ_MTABLE_BITS = 20
    private const val SGRPROJ_RECIP_BITS = 12
    private const val SGRPROJ_SGR_BITS = 8

    /**
     * The deblocked rows of each plane that loop restoration reads from outside a stripe: two
     * above and two below every boundary between stripes, by row, at the frame's coded width.
     */
    fun savedRows(f: Av1FrameDecoder): Array<HashMap<Int, ShortArray>> = Array(f.numPlanes) { plane ->
        val rows = HashMap<Int, ShortArray>()
        if (f.fh.frameRestorationType[plane] != Av1FrameHeader.RESTORE_NONE) {
            val subY = if (plane > 0) f.subY else 0
            val planeEndY = Av1.round2(f.fh.frameHeight, subY) - 1
            val stride = f.planeWidth[plane]
            var stripe = 1
            while (true) {
                val boundary = (-8 + stripe * 64) shr subY
                if (boundary - 2 > planeEndY) break
                for (y in boundary - 2..boundary + 1) {
                    if (y in 0..planeEndY && y !in rows) rows[y] = f.frame[plane].copyOfRange(y * stride, (y + 1) * stride)
                }
                stripe++
            }
        }
        rows
    }

    /** Restores every unit of [planes], rows [strides] apart, in place; [saved] are the rows [savedRows] kept. */
    fun apply(f: Av1FrameDecoder, planes: Array<ShortArray>, strides: IntArray, saved: Array<HashMap<Int, ShortArray>>) {
        val fh = f.fh
        if (!fh.usesLr) return
        for (plane in 0 until f.numPlanes) {
            if (fh.frameRestorationType[plane] == Av1FrameHeader.RESTORE_NONE) continue
            Plane(f, plane, planes[plane], saved[plane], strides[plane]).run()
        }
    }

    /**
     * The encoder's statistics of the self-guided filter of [set] on [plane] (#176): for each restoration unit of
     * [unitSize] samples, over its stripes as [apply] filters them, the sums the least-squares fit of the projection
     * needs, into [stats] at 7 a unit. With u the CDEF output and f0, f1 the two box filters' outputs, each in units of
     * 2^-4, and s the source sample: e = 2^11 s - 128 f1 (or 128 u when f1 is off), a = f0 - f1 (or f0 - u) and
     * b = u - f1, each divided by 2^(bitDepth - 8) so the sums stay in a Long; then the sums of a a, a b, b b, a e,
     * b e and e e, and of the squared error of the CDEF output against the source. The filtered sample is
     * (128 f1 + w0 a + w1 b) / 2^11 for the projection weights w0 = xqd[0] and w1 = xqd[1], so the error a
     * projection leaves is the sum of (e - w0 a - w1 b)^2 over 2^(22 - 2 (bitDepth - 8)). The differences keep their
     * fraction: a and b in whole samples, as an earlier version kept them, put errors of up to w0 / 128 of a sample
     * into each term, and the fit and its error went astray by more than the filter's gain.
     */
    fun sgrStatistics(
        f: Av1FrameDecoder, plane: Int, set: Int, unitSize: Int, unitRows: Int, unitCols: Int,
        frame: ShortArray, stride: Int, saved: Map<Int, ShortArray>, source: ShortArray, stats: LongArray,
    ) {
        Plane(f, plane, frame, saved, stride).statistics(set, unitSize, unitRows, unitCols, source, stats)
    }

    /**
     * The encoder's normal equations for one pass of the Wiener filter on [plane] (#176), the other pass held at the
     * taps in [taps] (six a unit, vertical then horizontal, as `LrWiener` keeps them): into [stats] at 9 a unit. The
     * filter's output is y = (128 z3 + a0 g0 + a1 g1 + a2 g2) / 2^14, where z0 to z6 are the seven samples the free
     * pass reads after the held pass has filtered them (in 1/128), gk = zk + z(6-k) - 2 z3, and a0 to a2 the free
     * pass's three taps; so with the target T = 128 (128 s - z3) for the source sample s, a least-squares fit of a
     * to T needs the sums of gk gl and of gk T, kept in that order (g0 g0, g0 g1, g0 g2, g1 g1, g1 g2, g2 g2, g0 T,
     * g1 T, g2 T), each g and T first shifted down by bitDepth - 6 so the sums stay in a Long. The rounding between
     * the passes is left out.
     */
    fun wienerStatistics(
        f: Av1FrameDecoder, plane: Int, vertical: Boolean, unitSize: Int, unitRows: Int, unitCols: Int,
        frame: ShortArray, stride: Int, saved: Map<Int, ShortArray>, source: ShortArray, taps: IntArray, stats: LongArray,
    ) {
        Plane(f, plane, frame, saved, stride).wienerStatistics(vertical, unitSize, unitRows, unitCols, source, taps, stats)
    }

    /**
     * The squared error against [source] of each restoration unit of [plane] (#176) filtered by the Wiener taps in
     * [taps], six a unit, into [errors] at 2 a unit, and after it the error of the unit left unfiltered.
     */
    fun wienerErrors(
        f: Av1FrameDecoder, plane: Int, unitSize: Int, unitRows: Int, unitCols: Int,
        frame: ShortArray, stride: Int, saved: Map<Int, ShortArray>, source: ShortArray, taps: IntArray, errors: LongArray,
    ) {
        Plane(f, plane, frame, saved, stride).wienerErrors(unitSize, unitRows, unitCols, source, taps, errors)
    }

    /**
     * The squared error against [source] of each restoration unit of [plane] (#176) under the self-guided filter of
     * set [sets] at its index (none where it is negative) and the projection in [weights], two a unit, added into
     * [errors].
     */
    fun sgrErrors(
        f: Av1FrameDecoder, plane: Int, unitSize: Int, unitRows: Int, unitCols: Int,
        frame: ShortArray, stride: Int, saved: Map<Int, ShortArray>, source: ShortArray, sets: IntArray, weights: IntArray, errors: LongArray,
    ) {
        Plane(f, plane, frame, saved, stride).sgrErrors(unitSize, unitRows, unitCols, source, sets, weights, errors)
    }

    private class Plane(
        val f: Av1FrameDecoder,
        val plane: Int,
        val out: ShortArray,
        val saved: Map<Int, ShortArray>,
        val stride: Int,
    ) {
        val subX = if (plane > 0) f.subX else 0
        val subY = if (plane > 0) f.subY else 0
        val bitDepth = f.bitDepth
        val maxValue = (1 shl bitDepth) - 1
        val planeEndX = Av1.round2(f.fh.upscaledWidth, subX) - 1
        val planeEndY = Av1.round2(f.fh.frameHeight, subY) - 1
        var stripeStartY = 0
        var stripeEndY = 0

        /** The stripe's own rows before restoration, from row [stripeTop] on: UpscaledCdefFrame for this stripe. */
        private var cdef = ShortArray(0)
        private var stripeTop = 0

        // The rows and columns get_source_sample reads for the rectangle being filtered,
        // three beyond each side: which frame and where in it.
        private var rowFrom = arrayOfNulls<ShortArray>(0)
        private var rowAt = IntArray(0)
        private var colAt = IntArray(0)

        fun run() {
            val unitSize = f.fh.loopRestorationSize[plane]
            val unitRows = f.lrUnitRows[plane]
            val unitCols = f.lrUnitCols[plane]
            var stripeNum = 0
            while (true) {
                stripeStartY = (-8 + stripeNum * 64) shr subY
                if (stripeStartY > planeEndY) break
                stripeEndY = stripeStartY + (64 shr subY) - 1
                val y0 = maxOf(0, stripeStartY)
                val y1 = minOf(planeEndY, stripeEndY)
                val n = (y1 - y0 + 1) * stride
                if (cdef.size < n) cdef = ShortArray(n)
                out.copyInto(cdef, 0, y0 * stride, (y1 + 1) * stride)
                stripeTop = y0
                // The unit row of every 4x4 block in the stripe, from its first.
                val lumaY = maxOf(0, stripeNum * 64 - 8)
                val unitRow = minOf(unitRows - 1, ((lumaY + 8) shr subY) / unitSize)
                for (unitCol in 0 until unitCols) {
                    val x0 = unitCol * unitSize
                    if (x0 > planeEndX) break
                    val x1 = if (unitCol == unitCols - 1) planeEndX else minOf(planeEndX, x0 + unitSize - 1)
                    val u = unitRow * unitCols + unitCol
                    when (f.lrType[plane]!![u]) {
                        Av1FrameHeader.RESTORE_WIENER -> {
                            prepare(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
                            wiener(u, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
                        }
                        Av1FrameHeader.RESTORE_SGRPROJ -> {
                            prepare(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
                            selfGuided(u, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
                        }
                        else -> {}
                    }
                }
                stripeNum++
            }
        }

        /** See [sgrStatistics]: the same walk over stripes and units as [run], gathering instead of filtering. */
        fun statistics(set: Int, unitSize: Int, unitRows: Int, unitCols: Int, src: ShortArray, stats: LongArray) {
            val params = Av1Tables.sgrParams
            val r0 = params[set * 4]
            val r1 = params[set * 4 + 2]
            val depthShift = bitDepth - 8
            var stripeNum = 0
            while (true) {
                stripeStartY = (-8 + stripeNum * 64) shr subY
                if (stripeStartY > planeEndY) break
                stripeEndY = stripeStartY + (64 shr subY) - 1
                val y0 = maxOf(0, stripeStartY)
                val y1 = minOf(planeEndY, stripeEndY)
                val n = (y1 - y0 + 1) * stride
                if (cdef.size < n) cdef = ShortArray(n)
                out.copyInto(cdef, 0, y0 * stride, (y1 + 1) * stride)
                stripeTop = y0
                val lumaY = maxOf(0, stripeNum * 64 - 8)
                val unitRow = minOf(unitRows - 1, ((lumaY + 8) shr subY) / unitSize)
                for (unitCol in 0 until unitCols) {
                    val x0 = unitCol * unitSize
                    if (x0 > planeEndX) break
                    val x1 = if (unitCol == unitCols - 1) planeEndX else minOf(planeEndX, x0 + unitSize - 1)
                    val w = x1 - x0 + 1
                    val h = y1 - y0 + 1
                    prepare(x0, y0, w, h)
                    val flt0 = if (r0 != 0) boxFilter(x0, y0, w, h, set, 0) else null
                    val flt1 = if (r1 != 0) boxFilter(x0, y0, w, h, set, 1) else null
                    val at = (unitRow * unitCols + unitCol) * 7
                    var aa = 0L
                    var ab = 0L
                    var bb = 0L
                    var ae = 0L
                    var be = 0L
                    var ee = 0L
                    var none = 0L
                    for (i in 0 until h) {
                        val row = (y0 + i - stripeTop) * stride + x0
                        val srow = (y0 + i) * stride + x0
                        for (j in 0 until w) {
                            val c = cdef[row + j].toInt()
                            val uu = c shl SGRPROJ_RST_BITS
                            val f0 = flt0?.get(i * w + j) ?: uu
                            val f1 = flt1?.get(i * w + j) ?: uu
                            val sv = src[srow + j].toInt()
                            val e = (((sv shl 11) - 128 * f1) shr depthShift).toLong()
                            val a = ((f0 - f1) shr depthShift).toLong()
                            val b = ((uu - f1) shr depthShift).toLong()
                            aa += a * a
                            ab += a * b
                            bb += b * b
                            ae += a * e
                            be += b * e
                            ee += e * e
                            val d = (sv - c).toLong()
                            none += d * d
                        }
                    }
                    stats[at] += aa
                    stats[at + 1] += ab
                    stats[at + 2] += bb
                    stats[at + 3] += ae
                    stats[at + 4] += be
                    stats[at + 5] += ee
                    stats[at + 6] += none
                }
                stripeNum++
            }
        }

        /**
         * Calls [body] with each unit and the rectangle of it in each stripe, in the order [run] filters them, after
         * the stripe's rows are copied and the rectangle prepared.
         */
        private inline fun forEachRectangle(unitSize: Int, unitRows: Int, unitCols: Int, body: (u: Int, x0: Int, y0: Int, w: Int, h: Int) -> Unit) {
            var stripeNum = 0
            while (true) {
                stripeStartY = (-8 + stripeNum * 64) shr subY
                if (stripeStartY > planeEndY) break
                stripeEndY = stripeStartY + (64 shr subY) - 1
                val y0 = maxOf(0, stripeStartY)
                val y1 = minOf(planeEndY, stripeEndY)
                val n = (y1 - y0 + 1) * stride
                if (cdef.size < n) cdef = ShortArray(n)
                out.copyInto(cdef, 0, y0 * stride, (y1 + 1) * stride)
                stripeTop = y0
                val lumaY = maxOf(0, stripeNum * 64 - 8)
                val unitRow = minOf(unitRows - 1, ((lumaY + 8) shr subY) / unitSize)
                for (unitCol in 0 until unitCols) {
                    val x0 = unitCol * unitSize
                    if (x0 > planeEndX) break
                    val x1 = if (unitCol == unitCols - 1) planeEndX else minOf(planeEndX, x0 + unitSize - 1)
                    val w = x1 - x0 + 1
                    val h = y1 - y0 + 1
                    prepare(x0, y0, w, h)
                    body(unitRow * unitCols + unitCol, x0, y0, w, h)
                }
                stripeNum++
            }
        }

        private var held = IntArray(0)

        /** See [Av1LoopRestoration.wienerStatistics]. */
        fun wienerStatistics(vertical: Boolean, unitSize: Int, unitRows: Int, unitCols: Int, src: ShortArray, taps: IntArray, stats: LongArray) {
            val shift = bitDepth - 6
            forEachRectangle(unitSize, unitRows, unitCols) { u, x0, y0, w, h ->
                // The held pass over every sample the free pass reads: rows -3 to h + 2 when the free pass is
                // vertical, columns -3 to w + 2 when it is horizontal.
                val hw = if (vertical) w else w + 6
                val hh = if (vertical) h + 6 else h
                if (held.size < hw * hh) held = IntArray(hw * hh)
                val z = held
                val k = coefficients(taps, u * 6 + (if (vertical) 3 else 0))
                if (vertical) {
                    for (r in 0 until hh) for (c in 0 until hw) {
                        var s = 0
                        for (t in 0 until 7) s += k[t] * source(r, c + t)
                        z[r * hw + c] = s
                    }
                } else {
                    for (r in 0 until hh) for (c in 0 until hw) {
                        var s = 0
                        for (t in 0 until 7) s += k[t] * source(r + t, c)
                        z[r * hw + c] = s
                    }
                }
                // The step from one of the seven samples the free pass reads to the next.
                val step = if (vertical) hw else 1
                var s00 = 0L
                var s01 = 0L
                var s02 = 0L
                var s11 = 0L
                var s12 = 0L
                var s22 = 0L
                var s0t = 0L
                var s1t = 0L
                var s2t = 0L
                for (i in 0 until h) {
                    val srow = (y0 + i) * stride + x0
                    for (j in 0 until w) {
                        val at = i * hw + j
                        val z3 = z[at + 3 * step]
                        val g0 = ((z[at] + z[at + 6 * step] - 2 * z3) shr shift).toLong()
                        val g1 = ((z[at + step] + z[at + 5 * step] - 2 * z3) shr shift).toLong()
                        val g2 = ((z[at + 2 * step] + z[at + 4 * step] - 2 * z3) shr shift).toLong()
                        val t = (((src[srow + j] * 128 - z3) * 128) shr shift).toLong()
                        s00 += g0 * g0
                        s01 += g0 * g1
                        s02 += g0 * g2
                        s11 += g1 * g1
                        s12 += g1 * g2
                        s22 += g2 * g2
                        s0t += g0 * t
                        s1t += g1 * t
                        s2t += g2 * t
                    }
                }
                val o = u * 9
                stats[o] += s00
                stats[o + 1] += s01
                stats[o + 2] += s02
                stats[o + 3] += s11
                stats[o + 4] += s12
                stats[o + 5] += s22
                stats[o + 6] += s0t
                stats[o + 7] += s1t
                stats[o + 8] += s2t
            }
        }

        /** See [Av1LoopRestoration.wienerErrors]. */
        fun wienerErrors(unitSize: Int, unitRows: Int, unitCols: Int, src: ShortArray, taps: IntArray, errors: LongArray) {
            forEachRectangle(unitSize, unitRows, unitCols) { u, x0, y0, w, h ->
                if (filtered.size < w * h) filtered = IntArray(w * h)
                wienerFilter(taps, u * 6, w, h, filtered)
                var e = 0L
                var none = 0L
                for (i in 0 until h) {
                    val srow = (y0 + i) * stride + x0
                    val crow = (y0 + i - stripeTop) * stride + x0
                    // At most 64 squares of 13-bit differences in an Int before each is added up.
                    var j = 0
                    while (j < w) {
                        val end = minOf(w, j + 64)
                        var rowE = 0
                        var rowN = 0
                        while (j < end) {
                            val s = src[srow + j].toInt()
                            val d = s - filtered[i * w + j]
                            val n = s - cdef[crow + j]
                            rowE += d * d
                            rowN += n * n
                            j++
                        }
                        e += rowE
                        none += rowN
                    }
                }
                errors[u * 2] += e
                errors[u * 2 + 1] += none
            }
        }

        /** The get source sample process for the rows and columns within three of the rectangle. */
        private fun prepare(x: Int, y: Int, w: Int, h: Int) {
            if (rowAt.size < h + 6) {
                rowFrom = arrayOfNulls(h + 6)
                rowAt = IntArray(h + 6)
            }
            if (colAt.size < w + 6) colAt = IntArray(w + 6)
            for (r in 0 until h + 6) {
                var yy = (y + r - 3).coerceIn(0, planeEndY)
                if (yy < stripeStartY || yy > stripeEndY) {
                    yy = if (yy < stripeStartY) maxOf(stripeStartY - 2, yy) else minOf(stripeEndY + 2, yy)
                    rowFrom[r] = saved[yy] ?: error("AV1 loop restoration: row $yy was not kept")
                    rowAt[r] = 0
                } else {
                    rowFrom[r] = cdef
                    rowAt[r] = (yy - stripeTop) * stride
                }
            }
            for (c in 0 until w + 6) colAt[c] = (x + c - 3).coerceIn(0, planeEndX)
        }

        /** get_source_sample at ([c] - 3, [r] - 3) from the rectangle's corner. */
        private fun source(r: Int, c: Int): Int = rowFrom[r]!![rowAt[r] + colAt[c]].toInt()

        private fun wiener(u: Int, x: Int, y: Int, w: Int, h: Int) {
            if (filtered.size < w * h) filtered = IntArray(w * h)
            wienerFilter(f.lrWiener[plane]!!, u * 6, w, h, filtered)
            for (r in 0 until h) {
                val row = (y + r) * stride + x
                for (c in 0 until w) out[row + c] = filtered[r * w + c].toShort()
            }
        }

        private var filtered = IntArray(0)
        private var intermediate = IntArray(0)

        /**
         * The Wiener filter of section 7.17.4 over the prepared rectangle of [w] by [h], with the vertical taps at [at]
         * in [coeffs] and the horizontal ones after them, into [result] a row of [w] apart.
         */
        fun wienerFilter(coeffs: IntArray, at: Int, w: Int, h: Int, result: IntArray) {
            val vfilter = coefficients(coeffs, at)
            val hfilter = coefficients(coeffs, at + 3)
            val round0 = if (bitDepth == 12) 5 else 3
            val round1 = if (bitDepth == 12) 9 else 11
            val offset = 1 shl (bitDepth + FILTER_BITS - round0 - 1)
            val limit = (1 shl (bitDepth + 1 + FILTER_BITS - round0)) - 1
            if (intermediate.size < (h + 6) * w) intermediate = IntArray((h + 6) * w)
            val mid = intermediate
            for (r in 0 until h + 6) {
                for (c in 0 until w) {
                    var s = 0
                    for (t in 0 until 7) s += hfilter[t] * source(r, c + t)
                    mid[r * w + c] = Av1.round2(s, round0).coerceIn(-offset, limit - offset)
                }
            }
            for (r in 0 until h) {
                for (c in 0 until w) {
                    var s = 0
                    for (t in 0 until 7) s += vfilter[t] * mid[(r + t) * w + c]
                    result[r * w + c] = Av1.round2(s, round1).coerceIn(0, maxValue)
                }
            }
        }

        /** The Wiener coefficient process: seven symmetric taps of unit gain from three. */
        private fun coefficients(coeffs: IntArray, at: Int): IntArray {
            val filter = IntArray(7)
            filter[3] = 128
            for (i in 0 until 3) {
                val c = coeffs[at + i]
                filter[i] = c
                filter[6 - i] = c
                filter[3] -= 2 * c
            }
            return filter
        }

        private fun selfGuided(u: Int, x: Int, y: Int, w: Int, h: Int) {
            val xqd = f.lrSgrXqd[plane]!!
            if (filtered.size < w * h) filtered = IntArray(w * h)
            sgrFilter(f.lrSgrSet[plane]!![u], xqd[u * 2], xqd[u * 2 + 1], x, y, w, h, filtered)
            for (i in 0 until h) {
                val row = (y + i) * stride + x
                for (j in 0 until w) out[row + j] = filtered[i * w + j].toShort()
            }
        }

        /**
         * The self-guided filter of section 7.17.3 with [set] and the projection [w0] and [w1] over the prepared
         * rectangle of [w] by [h] at ([x], [y]), into [result] a row of [w] apart.
         */
        fun sgrFilter(set: Int, w0: Int, w1: Int, x: Int, y: Int, w: Int, h: Int, result: IntArray) {
            val w2 = (1 shl SGRPROJ_PRJ_BITS) - w0 - w1
            val params = Av1Tables.sgrParams
            val r0 = params[set * 4]
            val r1 = params[set * 4 + 2]
            val flt0 = if (r0 != 0) boxFilter(x, y, w, h, set, 0) else null
            val flt1 = if (r1 != 0) boxFilter(x, y, w, h, set, 1) else null
            for (i in 0 until h) {
                val source = (y + i - stripeTop) * stride + x
                for (j in 0 until w) {
                    val uu = cdef[source + j].toInt() shl SGRPROJ_RST_BITS
                    var v = w1 * uu
                    v += if (flt0 != null) w0 * flt0[i * w + j] else w0 * uu
                    v += if (flt1 != null) w2 * flt1[i * w + j] else w2 * uu
                    result[i * w + j] = Av1.round2(v, SGRPROJ_RST_BITS + SGRPROJ_PRJ_BITS).coerceIn(0, maxValue)
                }
            }
        }

        /** See [Av1LoopRestoration.sgrErrors]. */
        fun sgrErrors(unitSize: Int, unitRows: Int, unitCols: Int, src: ShortArray, sets: IntArray, weights: IntArray, errors: LongArray) {
            forEachRectangle(unitSize, unitRows, unitCols) { u, x0, y0, w, h ->
                if (sets[u] < 0) return@forEachRectangle
                if (filtered.size < w * h) filtered = IntArray(w * h)
                sgrFilter(sets[u], weights[u * 2], weights[u * 2 + 1], x0, y0, w, h, filtered)
                errors[u] += squaredError(src, x0, y0, w, h)
            }
        }

        /** The squared error of [filtered], [w] by [h], against [src] at ([x0], [y0]). */
        private fun squaredError(src: ShortArray, x0: Int, y0: Int, w: Int, h: Int): Long {
            var e = 0L
            for (i in 0 until h) {
                val srow = (y0 + i) * stride + x0
                // At most 64 squares of 13-bit differences in an Int before each is added up.
                var j = 0
                while (j < w) {
                    val end = minOf(w, j + 64)
                    var row = 0
                    while (j < end) {
                        val d = src[srow + j] - filtered[i * w + j]
                        row += d * d
                        j++
                    }
                    e += row
                }
            }
            return e
        }

        /** The box filter process for one [pass] of [set]: F over the rectangle, h rows of w. */
        private fun boxFilter(x: Int, y: Int, w: Int, h: Int, set: Int, pass: Int): IntArray {
            val params = Av1Tables.sgrParams
            val r = params[set * 4 + pass * 2]
            val eps = params[set * 4 + pass * 2 + 1]
            val n = (2 * r + 1) * (2 * r + 1)
            val n2e = n * n * eps
            val s = ((1 shl SGRPROJ_MTABLE_BITS) + n2e / 2) / n2e
            val oneOverN = ((1 shl SGRPROJ_RECIP_BITS) + n / 2) / n
            val aw = w + 2
            // A and B cover one sample around the rectangle: entry (i + 1, j + 1) is A[i][j].
            val boxA = IntArray((h + 2) * aw)
            val boxB = IntArray((h + 2) * aw)
            // Horizontal sums of 2r + 1 samples and of their squares, for source rows -3 to h + 2.
            val sumW = w + 2
            val rowSum = IntArray((h + 6) * sumW)
            val rowSq = IntArray((h + 6) * sumW)
            for (rr in 0 until h + 6) {
                for (j in -1..w) {
                    var b = 0
                    var a = 0
                    for (dx in -r..r) {
                        val c = source(rr, j + dx + 3)
                        b += c
                        a += c * c
                    }
                    rowSum[rr * sumW + j + 1] = b
                    rowSq[rr * sumW + j + 1] = a
                }
            }
            val bdShift = bitDepth - 8
            for (i in -1..h) {
                // Pass 0 reads A and B on odd rows only.
                if (pass == 0 && (i and 1) == 0) continue
                for (j in -1..w) {
                    var a = 0
                    var b = 0
                    for (dy in -r..r) {
                        val at = (i + dy + 3) * sumW + j + 1
                        a += rowSq[at]
                        b += rowSum[at]
                    }
                    a = Av1.round2(a, 2 * bdShift)
                    val d = Av1.round2(b, bdShift)
                    val p = maxOf(0, a * n - d * d)
                    val z = Av1.round2(p.toLong() * s, SGRPROJ_MTABLE_BITS).toInt()
                    val a2 = when {
                        z >= 255 -> 256
                        z == 0 -> 1
                        else -> ((z shl SGRPROJ_SGR_BITS) + z / 2) / (z + 1)
                    }
                    val b2 = ((1 shl SGRPROJ_SGR_BITS) - a2).toLong() * b * oneOverN
                    boxA[(i + 1) * aw + j + 1] = a2
                    boxB[(i + 1) * aw + j + 1] = Av1.round2(b2, SGRPROJ_RECIP_BITS).toInt()
                }
            }
            val out = IntArray(w * h)
            for (i in 0 until h) {
                val odd = (i and 1) != 0
                val shift = if (pass == 0 && odd) 4 else 5
                val row = (y + i - stripeTop) * stride + x
                val up = i * aw
                val mid = (i + 1) * aw
                val down = (i + 2) * aw
                for (j in 0 until w) {
                    val c = j + 1
                    val a: Int
                    val b: Int
                    if (pass == 0) {
                        if (odd) {
                            a = 6 * boxA[mid + c] + 5 * (boxA[mid + c - 1] + boxA[mid + c + 1])
                            b = 6 * boxB[mid + c] + 5 * (boxB[mid + c - 1] + boxB[mid + c + 1])
                        } else {
                            a = 6 * (boxA[up + c] + boxA[down + c]) +
                                5 * (boxA[up + c - 1] + boxA[up + c + 1] + boxA[down + c - 1] + boxA[down + c + 1])
                            b = 6 * (boxB[up + c] + boxB[down + c]) +
                                5 * (boxB[up + c - 1] + boxB[up + c + 1] + boxB[down + c - 1] + boxB[down + c + 1])
                        }
                    } else {
                        a = 4 * (boxA[mid + c] + boxA[up + c] + boxA[down + c] + boxA[mid + c - 1] + boxA[mid + c + 1]) +
                            3 * (boxA[up + c - 1] + boxA[up + c + 1] + boxA[down + c - 1] + boxA[down + c + 1])
                        b = 4 * (boxB[mid + c] + boxB[up + c] + boxB[down + c] + boxB[mid + c - 1] + boxB[mid + c + 1]) +
                            3 * (boxB[up + c - 1] + boxB[up + c + 1] + boxB[down + c - 1] + boxB[down + c + 1])
                    }
                    val v = a * cdef[row + j].toInt() + b
                    out[i * w + j] = Av1.round2(v, SGRPROJ_SGR_BITS + shift - SGRPROJ_RST_BITS)
                }
            }
            return out
        }
    }
}
