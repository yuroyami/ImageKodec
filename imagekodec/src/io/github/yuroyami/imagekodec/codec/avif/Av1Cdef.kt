package io.github.yuroyami.imagekodec.codec.avif

/**
 * The AV1 constrained directional enhancement filter (specification section 7.15): turns the
 * deblocked CurrFrame into CdefFrame, in place. The specification reads CurrFrame and writes a
 * second frame; a sample's filter reads no further than two rows or columns away, so this
 * filters one band of 8x8 blocks at a time from a copy of the band's unfiltered rows and the
 * two rows on either side, and the frame is never held twice.
 *
 * The direction costs go through Long, as a partial sum squared times a Div_Table entry passes
 * an Int.
 */
internal object Av1Cdef {

    /** The unfiltered rows of one plane around the band being filtered: rows [top] to [top] + 11. */
    private class Band(val stride: Int, height: Int) {
        val rows = ShortArray(stride * (height + 4))
        var top = 0
    }

    fun apply(f: Av1FrameDecoder) {
        val bands = Array(f.numPlanes) { p -> Band(f.planeWidth[p], 8 shr (if (p > 0) f.subY else 0)) }
        var r = 0
        while (r < f.miRows) {
            for (p in 0 until f.numPlanes) load(f, p, bands[p], (r * Av1.MI_SIZE) shr (if (p > 0) f.subY else 0))
            var c = 0
            while (c < f.miCols) {
                val idx = f.cdefIdx[f.mi(r and 15.inv(), c and 15.inv())]
                block(f, bands, r, c, idx)
                c += 2
            }
            r += 2
        }
    }

    /**
     * Fills [b] with the unfiltered rows from two above [y0] to two below the band: the two above
     * kept from the band before, which has been filtered since, the rest still unfiltered in the frame.
     */
    private fun load(f: Av1FrameDecoder, plane: Int, b: Band, y0: Int) {
        val stride = b.stride
        val rows = b.rows.size / stride
        val height = rows - 4
        if (y0 > 0) {
            // The previous band's rows y0 - 2 and y0 - 1 sit at its offsets height and height + 1.
            b.rows.copyInto(b.rows, 0, height * stride, (height + 2) * stride)
        }
        val src = f.frame[plane]
        val planeRows = f.planeHeight[plane]
        for (i in 2 until rows) {
            val y = y0 - 2 + i
            if (y >= planeRows) break
            src.copyInto(b.rows, i * stride, y * stride, (y + 1) * stride)
        }
        b.top = y0 - 2
    }

    private fun block(f: Av1FrameDecoder, bands: Array<Band>, r: Int, c: Int, idx: Int) {
        // A block with no parameters keeps its samples.
        if (idx == -1) return
        val fh = f.fh
        val coeffShift = f.bitDepth - 8
        val skip = f.skips[f.mi(r, c)] != 0 && f.skips[f.mi(r + 1, c)] != 0 &&
            f.skips[f.mi(r, c + 1)] != 0 && f.skips[f.mi(r + 1, c + 1)] != 0
        if (skip) return
        val (yDir, variance) = direction(f, bands[0], r, c)
        var priStr = fh.cdefYPriStrength[idx] shl coeffShift
        var secStr = fh.cdefYSecStrength[idx] shl coeffShift
        var dir = if (priStr == 0) 0 else yDir
        val varStr = if ((variance shr 6) != 0) minOf(floorLog2(variance shr 6), 12) else 0
        priStr = if (variance != 0) (priStr * (4 + varStr) + 8) shr 4 else 0
        var damping = fh.cdefDamping + coeffShift
        filter(f, bands[0], 0, r, c, priStr, secStr, damping, dir)
        if (f.numPlanes == 1) return
        priStr = fh.cdefUvPriStrength[idx] shl coeffShift
        secStr = fh.cdefUvSecStrength[idx] shl coeffShift
        dir = if (priStr == 0) 0 else Av1Tables.cdefUvDir[(f.subX * 2 + f.subY) * 8 + yDir]
        damping = fh.cdefDamping + coeffShift - 1
        filter(f, bands[1], 1, r, c, priStr, secStr, damping, dir)
        filter(f, bands[2], 2, r, c, priStr, secStr, damping, dir)
    }

    private fun floorLog2(x: Int): Int = 31 - x.countLeadingZeroBits()

    private fun direction(f: Av1FrameDecoder, b: Band, r: Int, c: Int): Pair<Int, Int> {
        val cost = LongArray(8)
        val partial = Array(8) { LongArray(15) }
        val x0 = c shl Av1.MI_SIZE_LOG2
        val y0 = r shl Av1.MI_SIZE_LOG2
        val buf = b.rows
        val stride = b.stride
        for (i in 0 until 8) for (j in 0 until 8) {
            val x = ((buf[(y0 + i - b.top) * stride + x0 + j].toInt() shr (f.bitDepth - 8)) - 128).toLong()
            partial[0][i + j] += x
            partial[1][i + j / 2] += x
            partial[2][i] += x
            partial[3][3 + i - j / 2] += x
            partial[4][7 + i - j] += x
            partial[5][3 - i / 2 + j] += x
            partial[6][j] += x
            partial[7][i / 2 + j] += x
        }
        val div = Av1Tables.divTable
        for (i in 0 until 8) {
            cost[2] += partial[2][i] * partial[2][i]
            cost[6] += partial[6][i] * partial[6][i]
        }
        cost[2] *= div[8]
        cost[6] *= div[8]
        for (i in 0 until 7) {
            cost[0] += (partial[0][i] * partial[0][i] + partial[0][14 - i] * partial[0][14 - i]) * div[i + 1]
            cost[4] += (partial[4][i] * partial[4][i] + partial[4][14 - i] * partial[4][14 - i]) * div[i + 1]
        }
        cost[0] += partial[0][7] * partial[0][7] * div[8]
        cost[4] += partial[4][7] * partial[4][7] * div[8]
        var i = 1
        while (i < 8) {
            for (j in 0 until 5) cost[i] += partial[i][3 + j] * partial[i][3 + j]
            cost[i] *= div[8]
            for (j in 0 until 3) {
                cost[i] += (partial[i][j] * partial[i][j] + partial[i][10 - j] * partial[i][10 - j]) * div[2 * j + 2]
            }
            i += 2
        }
        var bestCost = 0L
        var yDir = 0
        for (k in 0 until 8) {
            if (cost[k] > bestCost) {
                bestCost = cost[k]
                yDir = k
            }
        }
        val variance = ((bestCost - cost[(yDir + 4) and 7]) shr 10).toInt()
        return yDir to variance
    }

    private fun constrain(diff: Int, threshold: Int, damping: Int): Int {
        if (threshold == 0) return 0
        val dampingAdj = maxOf(0, damping - floorLog2(threshold))
        val ad = kotlin.math.abs(diff)
        val v = (threshold - (ad shr dampingAdj)).coerceIn(0, ad)
        return if (diff < 0) -v else v
    }

    private fun filter(f: Av1FrameDecoder, b: Band, plane: Int, r: Int, c: Int, priStr: Int, secStr: Int, damping: Int, dir: Int) {
        val coeffShift = f.bitDepth - 8
        val subX = if (plane > 0) f.subX else 0
        val subY = if (plane > 0) f.subY else 0
        val x0 = (c * Av1.MI_SIZE) shr subX
        val y0 = (r * Av1.MI_SIZE) shr subY
        val w = 8 shr subX
        val h = 8 shr subY
        val src = b.rows
        val top = b.top
        val dst = f.frame[plane]
        val stride = f.planeWidth[plane]
        val directions = Av1Tables.cdefDirections
        val priTaps = Av1Tables.cdefPriTaps
        val secTaps = Av1Tables.cdefSecTaps
        val tapSet = (priStr shr coeffShift) and 1
        for (i in 0 until h) for (j in 0 until w) {
            var sum = 0
            val x = src[(y0 + i - top) * stride + x0 + j].toInt()
            var max = x
            var min = x
            for (k in 0 until 2) {
                var sign = -1
                while (sign <= 1) {
                    // The primary tap along the block's direction, then the two secondary taps beside it.
                    for (t in 0 until 3) {
                        val d = when (t) {
                            0 -> dir
                            1 -> (dir - 2) and 7
                            else -> (dir + 2) and 7
                        }
                        val yy = y0 + i + sign * directions[(d * 2 + k) * 2]
                        val xx = x0 + j + sign * directions[(d * 2 + k) * 2 + 1]
                        val candR = (yy shl subY) shr Av1.MI_SIZE_LOG2
                        val candC = (xx shl subX) shr Av1.MI_SIZE_LOG2
                        if (candC >= 0 && candC < f.miCols && candR >= 0 && candR < f.miRows) {
                            val p = src[(yy - top) * stride + xx].toInt()
                            sum += if (t == 0) priTaps[tapSet * 2 + k] * constrain(p - x, priStr, damping)
                            else secTaps[tapSet * 2 + k] * constrain(p - x, secStr, damping)
                            max = maxOf(p, max)
                            min = minOf(p, min)
                        }
                    }
                    sign += 2
                }
            }
            dst[(y0 + i) * stride + x0 + j] = (x + ((8 + sum - (if (sum < 0) 1 else 0)) shr 4)).coerceIn(min, max).toShort()
        }
    }
}
