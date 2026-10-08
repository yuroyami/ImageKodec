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
            val coeffs = f.lrWiener[plane]!!
            val vfilter = coefficients(coeffs, u * 6)
            val hfilter = coefficients(coeffs, u * 6 + 3)
            val round0 = if (bitDepth == 12) 5 else 3
            val round1 = if (bitDepth == 12) 9 else 11
            val offset = 1 shl (bitDepth + FILTER_BITS - round0 - 1)
            val limit = (1 shl (bitDepth + 1 + FILTER_BITS - round0)) - 1
            val intermediate = IntArray((h + 6) * w)
            for (r in 0 until h + 6) {
                for (c in 0 until w) {
                    var s = 0
                    for (t in 0 until 7) s += hfilter[t] * source(r, c + t)
                    intermediate[r * w + c] = Av1.round2(s, round0).coerceIn(-offset, limit - offset)
                }
            }
            for (r in 0 until h) {
                val row = (y + r) * stride + x
                for (c in 0 until w) {
                    var s = 0
                    for (t in 0 until 7) s += vfilter[t] * intermediate[(r + t) * w + c]
                    out[row + c] = Av1.round2(s, round1).coerceIn(0, maxValue).toShort()
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
            val set = f.lrSgrSet[plane]!![u]
            val xqd = f.lrSgrXqd[plane]!!
            val w0 = xqd[u * 2]
            val w1 = xqd[u * 2 + 1]
            val w2 = (1 shl SGRPROJ_PRJ_BITS) - w0 - w1
            val params = Av1Tables.sgrParams
            val r0 = params[set * 4]
            val r1 = params[set * 4 + 2]
            val flt0 = if (r0 != 0) boxFilter(x, y, w, h, set, 0) else null
            val flt1 = if (r1 != 0) boxFilter(x, y, w, h, set, 1) else null
            for (i in 0 until h) {
                val row = (y + i) * stride + x
                val source = (y + i - stripeTop) * stride + x
                for (j in 0 until w) {
                    val uu = cdef[source + j].toInt() shl SGRPROJ_RST_BITS
                    var v = w1 * uu
                    v += if (flt0 != null) w0 * flt0[i * w + j] else w0 * uu
                    v += if (flt1 != null) w2 * flt1[i * w + j] else w2 * uu
                    out[row + j] = Av1.round2(v, SGRPROJ_RST_BITS + SGRPROJ_PRJ_BITS).coerceIn(0, maxValue).toShort()
                }
            }
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
