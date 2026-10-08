package io.github.yuroyami.imagekodec.codec.avif

/** The AV1 loop filter process (specification section 7.14), on the frame's planes in place. */
internal object Av1LoopFilter {
    private const val NEARESTMV = 14
    private const val GLOBALMV = 16
    private const val GLOBAL_GLOBALMV = 23

    fun apply(f: Av1FrameDecoder) {
        val fh = f.fh
        for (plane in 0 until f.numPlanes) {
            if (plane == 0 || fh.loopFilterLevel[1 + plane] != 0) {
                for (pass in 0 until 2) {
                    val rowStep = if (plane == 0) 1 else 1 shl f.subY
                    val colStep = if (plane == 0) 1 else 1 shl f.subX
                    var row = 0
                    while (row < f.miRows) {
                        var col = 0
                        while (col < f.miCols) {
                            edge(f, plane, pass, row, col)
                            col += colStep
                        }
                        row += rowStep
                    }
                }
            }
        }
    }

    private fun edge(f: Av1FrameDecoder, plane: Int, pass: Int, row0: Int, col0: Int) {
        val fh = f.fh
        val subX = if (plane == 0) 0 else f.subX
        val subY = if (plane == 0) 0 else f.subY
        val dx = if (pass == 0) 1 else 0
        val dy = if (pass == 0) 0 else 1
        val x = col0 * Av1.MI_SIZE
        val y = row0 * Av1.MI_SIZE
        val row = row0 or subY
        val col = col0 or subX
        val onScreen = when {
            x >= fh.frameWidth -> false
            y >= fh.frameHeight -> false
            pass == 0 && x == 0 -> false
            pass == 1 && y == 0 -> false
            else -> true
        }
        if (!onScreen) return
        val xP = x shr subX
        val yP = y shr subY
        val prevRow = row - (dy shl subY)
        val prevCol = col - (dx shl subX)
        val m = f.mi(row, col)
        val miSize = f.miSizes[m]
        val txSz = f.loopfilterTxSizes[plane][(row shr subY) * f.miStride + (col shr subX)]
        val planeSize = Av1.subsampledSize(miSize, subX, subY)
        val skip = f.skips[m]
        val isIntra = f.refFrames0[m] <= Av1.INTRA_FRAME
        val prevTxSz = f.loopfilterTxSizes[plane][(prevRow shr subY) * f.miStride + (prevCol shr subX)]
        val isBlockEdge = if (pass == 0) xP % Av1.blockWidth[planeSize] == 0 else yP % Av1.blockHeight[planeSize] == 0
        val isTxEdge = if (pass == 0) xP % Av1.txWidth[txSz] == 0 else yP % Av1.txHeight[txSz] == 0
        val applyFilter = when {
            !isTxEdge -> false
            isBlockEdge || skip == 0 || isIntra -> true
            else -> false
        }
        // The filter size process.
        val baseSize = if (pass == 0) minOf(Av1.txWidth[prevTxSz], Av1.txWidth[txSz]) else minOf(Av1.txHeight[prevTxSz], Av1.txHeight[txSz])
        val filterSize = if (plane == 0) minOf(16, baseSize) else minOf(8, baseSize)
        var strength = strength(f, row, col, plane, pass)
        if (strength[0] == 0) strength = strength(f, prevRow, prevCol, plane, pass)
        val lvl = strength[0]
        if (!applyFilter || lvl <= 0) return
        for (i in 0 until Av1.MI_SIZE) {
            sample(f, xP + dy * i, yP + dx * i, plane, strength[1], strength[2], strength[3], dx, dy, filterSize)
        }
    }

    /** The adaptive filter strength process: lvl, limit, blimit and thresh. */
    private fun strength(f: Av1FrameDecoder, row: Int, col: Int, plane: Int, pass: Int): IntArray {
        val fh = f.fh
        val m = f.mi(row, col)
        val segment = f.segmentIds[m]
        val ref = f.refFrames0[m]
        val mode = f.yModes[m]
        val modeType = if (mode >= NEARESTMV && mode != GLOBALMV && mode != GLOBAL_GLOBALMV) 1 else 0
        val deltaLF = if (!fh.deltaLfMulti) f.deltaLfs[m * 4] else f.deltaLfs[m * 4 + (if (plane == 0) pass else plane + 1)]
        // The adaptive filter strength selection process.
        val i = if (plane == 0) pass else plane + 1
        val baseFilterLevel = (deltaLF + fh.loopFilterLevel[i]).coerceIn(0, Av1.MAX_LOOP_FILTER)
        var lvlSeg = baseFilterLevel
        val feature = Av1.SEG_LVL_ALT_LF_Y_V + i
        if (fh.segFeatureActiveIdx(segment, feature)) {
            lvlSeg = (fh.featureData[segment][feature] + lvlSeg).coerceIn(0, Av1.MAX_LOOP_FILTER)
        }
        if (fh.loopFilterDeltaEnabled) {
            val nShift = lvlSeg shr 5
            lvlSeg += if (ref == Av1.INTRA_FRAME) {
                fh.loopFilterRefDeltas[Av1.INTRA_FRAME] shl nShift
            } else {
                (fh.loopFilterRefDeltas[ref] shl nShift) + (fh.loopFilterModeDeltas[modeType] shl nShift)
            }
            lvlSeg = lvlSeg.coerceIn(0, Av1.MAX_LOOP_FILTER)
        }
        val lvl = lvlSeg
        val sharpness = fh.loopFilterSharpness
        val shift = if (sharpness > 4) 2 else if (sharpness > 0) 1 else 0
        val limit = if (sharpness > 0) (lvl shr shift).coerceIn(1, 9 - sharpness) else maxOf(1, lvl shr shift)
        val blimit = 2 * (lvl + 2) + limit
        val thresh = lvl shr 4
        return intArrayOf(lvl, limit, blimit, thresh)
    }

    private fun sample(f: Av1FrameDecoder, x: Int, y: Int, plane: Int, limit: Int, blimit: Int, thresh: Int, dx: Int, dy: Int, filterSize: Int) {
        val buf = f.frame[plane]
        val stride = f.planeWidth[plane]
        val step = dy * stride + dx
        val at = y * stride + x
        fun s(k: Int) = buf[at + k * step].toInt()
        val bitDepth = f.bitDepth
        val q0 = s(0)
        val q1 = s(1)
        val q2 = s(2)
        val q3 = s(3)
        val p0 = s(-1)
        val p1 = s(-2)
        val p2 = s(-3)
        val p3 = s(-4)
        // The filter mask process.
        val threshBd = thresh shl (bitDepth - 8)
        val hevMask = kotlin.math.abs(p1 - p0) > threshBd || kotlin.math.abs(q1 - q0) > threshBd
        val filterLen = when {
            filterSize == 4 -> 4
            plane != 0 -> 6
            filterSize == 8 -> 8
            else -> 16
        }
        val limitBd = limit shl (bitDepth - 8)
        val blimitBd = blimit shl (bitDepth - 8)
        var mask = kotlin.math.abs(p1 - p0) > limitBd || kotlin.math.abs(q1 - q0) > limitBd ||
            kotlin.math.abs(p0 - q0) * 2 + kotlin.math.abs(p1 - q1) / 2 > blimitBd
        if (filterLen >= 6) mask = mask || kotlin.math.abs(p2 - p1) > limitBd || kotlin.math.abs(q2 - q1) > limitBd
        if (filterLen >= 8) mask = mask || kotlin.math.abs(p3 - p2) > limitBd || kotlin.math.abs(q3 - q2) > limitBd
        if (mask) return
        val thresholdBd = 1 shl (bitDepth - 8)
        var flatMask = false
        if (filterSize >= 8) {
            var m = kotlin.math.abs(p1 - p0) > thresholdBd || kotlin.math.abs(q1 - q0) > thresholdBd ||
                kotlin.math.abs(p2 - p0) > thresholdBd || kotlin.math.abs(q2 - q0) > thresholdBd
            if (filterLen >= 8) m = m || kotlin.math.abs(p3 - p0) > thresholdBd || kotlin.math.abs(q3 - q0) > thresholdBd
            flatMask = !m
        }
        var flatMask2 = false
        if (filterSize >= 16) {
            val q4 = s(4)
            val q5 = s(5)
            val q6 = s(6)
            val p4 = s(-5)
            val p5 = s(-6)
            val p6 = s(-7)
            val m = kotlin.math.abs(p6 - p0) > thresholdBd || kotlin.math.abs(q6 - q0) > thresholdBd ||
                kotlin.math.abs(p5 - p0) > thresholdBd || kotlin.math.abs(q5 - q0) > thresholdBd ||
                kotlin.math.abs(p4 - p0) > thresholdBd || kotlin.math.abs(q4 - q0) > thresholdBd
            flatMask2 = !m
        }
        when {
            filterSize == 4 || !flatMask -> narrow(buf, at, step, hevMask, bitDepth)
            filterSize == 8 || !flatMask2 -> wide(buf, at, step, plane, 3)
            else -> wide(buf, at, step, plane, 4)
        }
    }

    private fun narrow(buf: ShortArray, at: Int, step: Int, hevMask: Boolean, bitDepth: Int) {
        val lo = -(1 shl (bitDepth - 1))
        val hi = (1 shl (bitDepth - 1)) - 1
        fun c(v: Int) = v.coerceIn(lo, hi)
        val off = 0x80 shl (bitDepth - 8)
        val q0 = buf[at].toInt()
        val q1 = buf[at + step].toInt()
        val p0 = buf[at - step].toInt()
        val p1 = buf[at - 2 * step].toInt()
        val ps1 = p1 - off
        val ps0 = p0 - off
        val qs0 = q0 - off
        val qs1 = q1 - off
        var filter = if (hevMask) c(ps1 - qs1) else 0
        filter = c(filter + 3 * (qs0 - ps0))
        val filter1 = c(filter + 4) shr 3
        val filter2 = c(filter + 3) shr 3
        buf[at] = (c(qs0 - filter1) + off).toShort()
        buf[at - step] = (c(ps0 + filter2) + off).toShort()
        if (!hevMask) {
            filter = Av1.round2(filter1, 1)
            buf[at + step] = (c(qs1 - filter) + off).toShort()
            buf[at - 2 * step] = (c(ps1 + filter) + off).toShort()
        }
    }

    private fun wide(buf: ShortArray, at: Int, step: Int, plane: Int, log2Size: Int) {
        val n = if (log2Size == 4) 6 else if (plane == 0) 3 else 2
        val n2 = if (log2Size == 3 && plane == 0) 0 else 1
        val out = IntArray(2 * n)
        for (i in -n until n) {
            var t = 0
            for (j in -n..n) {
                val p = (i + j).coerceIn(-(n + 1), n)
                val tap = if (kotlin.math.abs(j) <= n2) 2 else 1
                t += buf[at + p * step].toInt() * tap
            }
            out[i + n] = Av1.round2(t, log2Size)
        }
        for (i in -n until n) buf[at + i * step] = out[i + n].toShort()
    }
}
