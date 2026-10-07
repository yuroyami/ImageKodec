package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.codec.avif.Av1.round2

/**
 * The AV1 intra prediction process (specification section 7.11.2), the palette prediction
 * process (7.11.4) and chroma from luma (7.11.5), writing into the frame's sample planes.
 */
internal object Av1Intra {

    /** AboveRow and LeftCol run from index -16, so the upsampled edge's -2 and the corner's -1 fit. */
    private const val EDGE = 16

    fun predictIntra(
        d: Av1FrameDecoder, plane: Int, x: Int, y: Int,
        haveLeft: Boolean, haveAbove: Boolean, haveAboveRight: Boolean, haveBelowLeft: Boolean,
        mode: Int, log2W: Int, log2H: Int,
    ) {
        val w = 1 shl log2W
        val h = 1 shl log2H
        val sx = if (plane > 0) d.subX else 0
        val sy = if (plane > 0) d.subY else 0
        val maxX = ((d.miCols * Av1.MI_SIZE) shr sx) - 1
        val maxY = ((d.miRows * Av1.MI_SIZE) shr sy) - 1
        val buf = d.frame[plane]
        val stride = d.planeWidth[plane]
        val bitDepth = d.bitDepth
        val aboveRow = IntArray(2 * (w + h) + 2 * EDGE)
        val leftCol = IntArray(2 * (w + h) + 2 * EDGE)
        fun at(px: Int, py: Int) = buf[py * stride + px]

        if (!haveAbove && haveLeft) {
            val v = at(x - 1, y)
            for (i in 0 until w + h) aboveRow[EDGE + i] = v
        } else if (!haveAbove && !haveLeft) {
            val v = (1 shl (bitDepth - 1)) - 1
            for (i in 0 until w + h) aboveRow[EDGE + i] = v
        } else {
            val aboveLimit = minOf(maxX, x + (if (haveAboveRight) 2 * w else w) - 1)
            for (i in 0 until w + h) aboveRow[EDGE + i] = at(minOf(aboveLimit, x + i), y - 1)
        }
        if (!haveLeft && haveAbove) {
            val v = at(x, y - 1)
            for (i in 0 until w + h) leftCol[EDGE + i] = v
        } else if (!haveLeft && !haveAbove) {
            val v = (1 shl (bitDepth - 1)) + 1
            for (i in 0 until w + h) leftCol[EDGE + i] = v
        } else {
            val leftLimit = minOf(maxY, y + (if (haveBelowLeft) 2 * h else h) - 1)
            for (i in 0 until w + h) leftCol[EDGE + i] = at(x - 1, minOf(leftLimit, y + i))
        }
        aboveRow[EDGE - 1] = when {
            haveAbove && haveLeft -> at(x - 1, y - 1)
            haveAbove -> at(x, y - 1)
            haveLeft -> at(x - 1, y)
            else -> 1 shl (bitDepth - 1)
        }
        leftCol[EDGE - 1] = aboveRow[EDGE - 1]

        val pred = IntArray(w * h)
        when {
            plane == 0 && d.useFilterIntra -> recursive(d, aboveRow, leftCol, w, h, pred)
            Av1.isDirectionalMode(mode) -> directional(d, plane, x, y, haveLeft, haveAbove, mode, w, h, maxX, maxY, aboveRow, leftCol, pred)
            mode == Av1.SMOOTH_PRED || mode == Av1.SMOOTH_V_PRED || mode == Av1.SMOOTH_H_PRED ->
                smooth(mode, log2W, log2H, w, h, aboveRow, leftCol, pred)
            mode == Av1.DC_PRED -> dc(d, haveLeft, haveAbove, log2W, log2H, w, h, aboveRow, leftCol, pred)
            else -> paeth(w, h, aboveRow, leftCol, pred)
        }
        for (i in 0 until h) {
            val row = (y + i) * stride + x
            for (j in 0 until w) buf[row + j] = pred[i * w + j]
        }
    }

    private fun paeth(w: Int, h: Int, aboveRow: IntArray, leftCol: IntArray, pred: IntArray) {
        val topLeft = aboveRow[EDGE - 1]
        for (i in 0 until h) for (j in 0 until w) {
            val base = aboveRow[EDGE + j] + leftCol[EDGE + i] - topLeft
            val pLeft = kotlin.math.abs(base - leftCol[EDGE + i])
            val pTop = kotlin.math.abs(base - aboveRow[EDGE + j])
            val pTopLeft = kotlin.math.abs(base - topLeft)
            pred[i * w + j] = when {
                pLeft <= pTop && pLeft <= pTopLeft -> leftCol[EDGE + i]
                pTop <= pTopLeft -> aboveRow[EDGE + j]
                else -> topLeft
            }
        }
    }

    private fun recursive(d: Av1FrameDecoder, aboveRow: IntArray, leftCol: IntArray, w: Int, h: Int, pred: IntArray) {
        val w4 = w shr 2
        val h2 = h shr 1
        val p = IntArray(7)
        val taps = Av1Tables.intraFilterTaps
        val mode = d.filterIntraMode
        val max = (1 shl d.bitDepth) - 1
        for (i2 in 0 until h2) for (j4 in 0 until w4) {
            for (i in 0 until 7) {
                p[i] = if (i < 5) {
                    when {
                        i2 == 0 -> aboveRow[EDGE + (j4 shl 2) + i - 1]
                        j4 == 0 && i == 0 -> leftCol[EDGE + (i2 shl 1) - 1]
                        else -> pred[((i2 shl 1) - 1) * w + (j4 shl 2) + i - 1]
                    }
                } else {
                    if (j4 == 0) leftCol[EDGE + (i2 shl 1) + i - 5]
                    else pred[((i2 shl 1) + i - 5) * w + (j4 shl 2) - 1]
                }
            }
            for (i1 in 0 until 2) for (j1 in 0 until 4) {
                var pr = 0
                for (i in 0 until 7) pr += taps[(mode * 8 + (i1 shl 2) + j1) * 7 + i] * p[i]
                pred[((i2 shl 1) + i1) * w + (j4 shl 2) + j1] = Av1.round2Signed(pr, Av1.INTRA_FILTER_SCALE_BITS).coerceIn(0, max)
            }
        }
    }

    private fun smoothWeights(log2: Int): IntArray = when (log2) {
        2 -> Av1Tables.smWeightsTx4x4
        3 -> Av1Tables.smWeightsTx8x8
        4 -> Av1Tables.smWeightsTx16x16
        5 -> Av1Tables.smWeightsTx32x32
        else -> Av1Tables.smWeightsTx64x64
    }

    private fun smooth(mode: Int, log2W: Int, log2H: Int, w: Int, h: Int, aboveRow: IntArray, leftCol: IntArray, pred: IntArray) {
        val wx = smoothWeights(log2W)
        val wy = smoothWeights(log2H)
        for (i in 0 until h) for (j in 0 until w) {
            pred[i * w + j] = when (mode) {
                Av1.SMOOTH_PRED -> round2(
                    wy[i] * aboveRow[EDGE + j] + (256 - wy[i]) * leftCol[EDGE + h - 1] +
                        wx[j] * leftCol[EDGE + i] + (256 - wx[j]) * aboveRow[EDGE + w - 1], 9,
                )
                Av1.SMOOTH_V_PRED -> round2(wy[i] * aboveRow[EDGE + j] + (256 - wy[i]) * leftCol[EDGE + h - 1], 8)
                else -> round2(wx[j] * leftCol[EDGE + i] + (256 - wx[j]) * aboveRow[EDGE + w - 1], 8)
            }
        }
    }

    private fun dc(
        d: Av1FrameDecoder, haveLeft: Boolean, haveAbove: Boolean, log2W: Int, log2H: Int, w: Int, h: Int,
        aboveRow: IntArray, leftCol: IntArray, pred: IntArray,
    ) {
        val max = (1 shl d.bitDepth) - 1
        val v = if (haveLeft && haveAbove) {
            var sum = 0
            for (k in 0 until h) sum += leftCol[EDGE + k]
            for (k in 0 until w) sum += aboveRow[EDGE + k]
            sum += (w + h) shr 1
            sum / (w + h)
        } else if (haveLeft) {
            var sum = 0
            for (k in 0 until h) sum += leftCol[EDGE + k]
            ((sum + (h shr 1)) shr log2H).coerceIn(0, max)
        } else if (haveAbove) {
            var sum = 0
            for (k in 0 until w) sum += aboveRow[EDGE + k]
            ((sum + (w shr 1)) shr log2W).coerceIn(0, max)
        } else {
            1 shl (d.bitDepth - 1)
        }
        pred.fill(v, 0, w * h)
    }

    private fun directional(
        d: Av1FrameDecoder, plane: Int, x: Int, y: Int, haveLeft: Boolean, haveAbove: Boolean,
        mode: Int, w: Int, h: Int, maxX: Int, maxY: Int, aboveRow: IntArray, leftCol: IntArray, pred: IntArray,
    ) {
        val angleDelta = if (plane == 0) d.angleDeltaY else d.angleDeltaUV
        val pAngle = Av1Tables.modeToAngle[mode] + angleDelta * Av1.ANGLE_STEP
        var upsampleAbove = 0
        var upsampleLeft = 0
        if (d.seq.enableIntraEdgeFilter) {
            val filterType = filterType(d, plane)
            if (pAngle != 90 && pAngle != 180) {
                if (pAngle > 90 && pAngle < 180 && (w + h) >= 24) {
                    // The filter corner process.
                    val s = leftCol[EDGE] * 5 + aboveRow[EDGE - 1] * 6 + aboveRow[EDGE] * 5
                    val v = round2(s, 4)
                    leftCol[EDGE - 1] = v
                    aboveRow[EDGE - 1] = v
                }
                if (haveAbove) {
                    val strength = edgeFilterStrength(w, h, filterType, pAngle - 90)
                    val numPx = minOf(w, maxX - x + 1) + (if (pAngle < 90) h else 0) + 1
                    edgeFilter(aboveRow, numPx, strength)
                }
                if (haveLeft) {
                    val strength = edgeFilterStrength(w, h, filterType, pAngle - 180)
                    val numPx = minOf(h, maxY - y + 1) + (if (pAngle > 180) w else 0) + 1
                    edgeFilter(leftCol, numPx, strength)
                }
            }
            // At 90 and 180 degrees neither edge upsamples: the selection needs a difference from 1 to 39.
            upsampleAbove = if (useUpsample(w, h, filterType, pAngle - 90)) 1 else 0
            if (upsampleAbove == 1) edgeUpsample(d, aboveRow, w + (if (pAngle < 90) h else 0))
            upsampleLeft = if (useUpsample(w, h, filterType, pAngle - 180)) 1 else 0
            if (upsampleLeft == 1) edgeUpsample(d, leftCol, h + (if (pAngle > 180) w else 0))
        }
        val dr = Av1Tables.drIntraDerivative
        val dx = when {
            pAngle < 90 -> dr[pAngle]
            pAngle in 91..179 -> dr[180 - pAngle]
            else -> 0
        }
        val dy = when {
            pAngle in 91..179 -> dr[pAngle - 90]
            pAngle > 180 -> dr[270 - pAngle]
            else -> 0
        }
        if (pAngle < 90) {
            val maxBaseX = (w + h - 1) shl upsampleAbove
            for (i in 0 until h) {
                val idx = (i + 1) * dx
                val shift = ((idx shl upsampleAbove) shr 1) and 0x1F
                for (j in 0 until w) {
                    val base = (idx shr (6 - upsampleAbove)) + (j shl upsampleAbove)
                    pred[i * w + j] = if (base < maxBaseX) {
                        round2(aboveRow[EDGE + base] * (32 - shift) + aboveRow[EDGE + base + 1] * shift, 5)
                    } else {
                        aboveRow[EDGE + maxBaseX]
                    }
                }
            }
        } else if (pAngle in 91..179) {
            for (i in 0 until h) for (j in 0 until w) {
                var idx = (j shl 6) - (i + 1) * dx
                var base = idx shr (6 - upsampleAbove)
                if (base >= -(1 shl upsampleAbove)) {
                    val shift = ((idx shl upsampleAbove) shr 1) and 0x1F
                    pred[i * w + j] = round2(aboveRow[EDGE + base] * (32 - shift) + aboveRow[EDGE + base + 1] * shift, 5)
                } else {
                    idx = (i shl 6) - (j + 1) * dy
                    base = idx shr (6 - upsampleLeft)
                    val shift = ((idx shl upsampleLeft) shr 1) and 0x1F
                    pred[i * w + j] = round2(leftCol[EDGE + base] * (32 - shift) + leftCol[EDGE + base + 1] * shift, 5)
                }
            }
        } else if (pAngle > 180) {
            for (j in 0 until w) {
                val idx = (j + 1) * dy
                val shift = ((idx shl upsampleLeft) shr 1) and 0x1F
                for (i in 0 until h) {
                    val base = (idx shr (6 - upsampleLeft)) + (i shl upsampleLeft)
                    pred[i * w + j] = round2(leftCol[EDGE + base] * (32 - shift) + leftCol[EDGE + base + 1] * shift, 5)
                }
            }
        } else if (pAngle == 90) {
            for (i in 0 until h) for (j in 0 until w) pred[i * w + j] = aboveRow[EDGE + j]
        } else {
            for (i in 0 until h) for (j in 0 until w) pred[i * w + j] = leftCol[EDGE + i]
        }
    }

    /** The intra filter type process: 1 when the block above or to the left is smooth predicted. */
    private fun filterType(d: Av1FrameDecoder, plane: Int): Int {
        var aboveSmooth = false
        var leftSmooth = false
        if (if (plane == 0) d.availU else d.availUChroma) {
            var r = d.miRow - 1
            var c = d.miCol
            if (plane > 0) {
                if (d.subX == 1 && (d.miCol and 1) == 0) c++
                if (d.subY == 1 && (d.miRow and 1) == 1) r--
            }
            aboveSmooth = isSmooth(d, r, c, plane)
        }
        if (if (plane == 0) d.availL else d.availLChroma) {
            var r = d.miRow
            var c = d.miCol - 1
            if (plane > 0) {
                if (d.subX == 1 && (d.miCol and 1) == 1) c--
                if (d.subY == 1 && (d.miRow and 1) == 0) r++
            }
            leftSmooth = isSmooth(d, r, c, plane)
        }
        return if (aboveSmooth || leftSmooth) 1 else 0
    }

    private fun isSmooth(d: Av1FrameDecoder, row: Int, col: Int, plane: Int): Boolean {
        val m = d.mi(row, col)
        val mode = if (plane == 0) d.yModes[m] else {
            if (d.refFrames0[m] > Av1.INTRA_FRAME) return false
            d.uvModes[m]
        }
        return mode == Av1.SMOOTH_PRED || mode == Av1.SMOOTH_V_PRED || mode == Av1.SMOOTH_H_PRED
    }

    private fun edgeFilterStrength(w: Int, h: Int, filterType: Int, delta: Int): Int {
        val dd = kotlin.math.abs(delta)
        val blkWh = w + h
        var strength = 0
        if (filterType == 0) {
            when {
                blkWh <= 8 -> if (dd >= 56) strength = 1
                blkWh <= 12 -> if (dd >= 40) strength = 1
                blkWh <= 16 -> if (dd >= 40) strength = 1
                blkWh <= 24 -> {
                    if (dd >= 8) strength = 1
                    if (dd >= 16) strength = 2
                    if (dd >= 32) strength = 3
                }
                blkWh <= 32 -> {
                    strength = 1
                    if (dd >= 4) strength = 2
                    if (dd >= 32) strength = 3
                }
                else -> strength = 3
            }
        } else {
            when {
                blkWh <= 8 -> {
                    if (dd >= 40) strength = 1
                    if (dd >= 64) strength = 2
                }
                blkWh <= 16 -> {
                    if (dd >= 20) strength = 1
                    if (dd >= 48) strength = 2
                }
                blkWh <= 24 -> if (dd >= 4) strength = 3
                else -> strength = 3
            }
        }
        return strength
    }

    private fun useUpsample(w: Int, h: Int, filterType: Int, delta: Int): Boolean {
        val dd = kotlin.math.abs(delta)
        val blkWh = w + h
        return when {
            dd <= 0 || dd >= 40 -> false
            filterType == 0 -> blkWh <= 16
            else -> blkWh <= 8
        }
    }

    /** The intra edge filter process on the first [sz] entries from index -1 of [buf]. */
    private fun edgeFilter(buf: IntArray, sz: Int, strength: Int) {
        if (strength == 0) return
        val edge = IntArray(sz)
        for (i in 0 until sz) edge[i] = buf[EDGE + i - 1]
        val kernel = Av1Tables.intraEdgeKernel
        for (i in 1 until sz) {
            var s = 0
            for (j in 0 until 5) {
                val k = (i - 2 + j).coerceIn(0, sz - 1)
                s += kernel[(strength - 1) * 5 + j] * edge[k]
            }
            buf[EDGE + i - 1] = (s + 8) shr 4
        }
    }

    private fun edgeUpsample(d: Av1FrameDecoder, buf: IntArray, numPx: Int) {
        val dup = IntArray(numPx + 3)
        dup[0] = buf[EDGE - 1]
        for (i in -1 until numPx) dup[i + 2] = buf[EDGE + i]
        dup[numPx + 2] = buf[EDGE + numPx - 1]
        val max = (1 shl d.bitDepth) - 1
        buf[EDGE - 2] = dup[0]
        for (i in 0 until numPx) {
            var s = -dup[i] + 9 * dup[i + 1] + 9 * dup[i + 2] - dup[i + 3]
            s = round2(s, 4).coerceIn(0, max)
            buf[EDGE + 2 * i - 1] = s
            buf[EDGE + 2 * i] = dup[i + 2]
        }
    }

    fun predictPalette(d: Av1FrameDecoder, plane: Int, startX: Int, startY: Int, x: Int, y: Int, txSz: Int) {
        val w = Av1.txWidth[txSz]
        val h = Av1.txHeight[txSz]
        val palette = when (plane) {
            0 -> d.paletteColorsY
            1 -> d.paletteColorsU
            else -> d.paletteColorsV
        }
        val map = if (plane == 0) d.colorMapY else d.colorMapUV
        val buf = d.frame[plane]
        val stride = d.planeWidth[plane]
        for (i in 0 until h) for (j in 0 until w) {
            buf[(startY + i) * stride + startX + j] = palette[map[(y * 4 + i) * 64 + x * 4 + j]]
        }
    }

    fun predictChromaFromLuma(d: Av1FrameDecoder, plane: Int, startX: Int, startY: Int, txSz: Int) {
        val w = Av1.txWidth[txSz]
        val h = Av1.txHeight[txSz]
        val subX = d.subX
        val subY = d.subY
        val alpha = if (plane == 1) d.cflAlphaU else d.cflAlphaV
        val luma = d.frame[0]
        val lumaStride = d.planeWidth[0]
        val l = IntArray(w * h)
        var lumaAvg = 0
        for (i in 0 until h) {
            val lumaY = minOf((startY + i) shl subY, d.maxLumaH - (1 shl subY))
            for (j in 0 until w) {
                val lumaX = minOf((startX + j) shl subX, d.maxLumaW - (1 shl subX))
                var t = 0
                for (dy in 0..subY) for (dx in 0..subX) t += luma[(lumaY + dy) * lumaStride + lumaX + dx]
                val v = t shl (3 - subX - subY)
                l[i * w + j] = v
                lumaAvg += v
            }
        }
        lumaAvg = round2(lumaAvg, Av1.txWidthLog2[txSz] + Av1.txHeightLog2[txSz])
        val buf = d.frame[plane]
        val stride = d.planeWidth[plane]
        val max = (1 shl d.bitDepth) - 1
        for (i in 0 until h) for (j in 0 until w) {
            val at = (startY + i) * stride + startX + j
            val scaledLuma = Av1.round2Signed(alpha * (l[i * w + j] - lumaAvg), 6)
            buf[at] = (buf[at] + scaledLuma).coerceIn(0, max)
        }
    }
}
