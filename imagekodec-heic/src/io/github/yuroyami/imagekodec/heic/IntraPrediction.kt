package io.github.yuroyami.imagekodec.heic

/**
 * Intra sample prediction, 8.4.4.2, written straight into the picture. The reference samples p of
 * 8.4.4.2.1 sit in one line from p[ -1 ][ 2N - 1 ] up the left column to p[ -1 ][ -1 ] and then
 * along the top row to p[ 2N - 1 ][ -1 ]: the order in which 8.4.4.2.2 substitutes them, and in
 * which 8.4.4.2.3 filters them.
 */
internal class IntraPredictor(private val d: PictureDecoder) {
    /** 8.4.4.2.1, step 1: intra_smoothing_disabled_flag turns the filter of 8.4.4.2.3 off. */
    private val smoothing = !d.sps.intraSmoothingDisabled
    /** intra_boundary_filtering_disabled_flag turns off the edge filters of the DC, vertical and horizontal modes (8.4.4.2.5, 8.4.4.2.6). */
    private val boundaryFilter = !d.sps.intraBoundaryFilteringDisabled
    private val line = IntArray(4 * 32 + 1)
    private val filtered = IntArray(4 * 32 + 1)
    private val ok = BooleanArray(4 * 32 + 1)
    private val ref = IntArray(3 * 32 + 1)

    /**
     * Predicts the n by n block, n = 1 shl [log2], at ([xTb], [yTb]) of component [cIdx], in that
     * component's samples, with intra prediction mode [mode]. [bypassRdpcm] is a unit with
     * cu_transquant_bypass_flag under implicit_rdpcm_enabled_flag, which 8.4.4.2.6 predicts without
     * the edge filters of the vertical and horizontal modes. An encoder that tries several modes on one block
     * passes [sameBlock] after the first, which keeps the reference samples gathered for it.
     */
    fun predict(cIdx: Int, xTb: Int, yTb: Int, log2: Int, mode: Int, bypassRdpcm: Boolean, sameBlock: Boolean = false) {
        val n = 1 shl log2
        val plane = d.planes[cIdx]
        val stride = d.strides[cIdx]
        val sw = if (cIdx == 0) 0 else d.subWShift
        val sh = if (cIdx == 0) 0 else d.subHShift
        val bitDepth = if (cIdx == 0) d.sps.bitDepthY else d.sps.bitDepthC
        val corner = 2 * n
        val total = 4 * n + 1
        if (!sameBlock) gather(plane, stride, xTb, yTb, n, sw, sh, bitDepth)
        var p = line
        // 8.4.4.2.3 filters luma, and chroma only in 4:4:4.
        if (smoothing && (cIdx == 0 || d.chromaArrayType == 3) && mode != INTRA_DC && n != 4) {
            val minDist = minOf(abs(mode - 26), abs(mode - 10))
            val threshold = when (n) { 8 -> 7; 16 -> 1; else -> 0 }
            if (minDist > threshold) {
                p = filtered
                val l = line
                val bottom = l[0]
                val right = l[total - 1]
                val c = l[corner]
                val limit = 1 shl (d.sps.bitDepthY - 5)
                if (d.sps.strongIntraSmoothing && cIdx == 0 && n == 32 &&
                    abs(c + right - 2 * l[corner + n]) < limit && abs(c + bottom - 2 * l[corner - n]) < limit
                ) {
                    // 8-36 to 8-40: bilinear lines from the corner to the far ends.
                    p[corner] = c
                    for (y in 0 until 63) p[corner - 1 - y] = ((63 - y) * c + (y + 1) * bottom + 32) shr 6
                    p[0] = bottom
                    for (x in 0 until 63) p[corner + 1 + x] = ((63 - x) * c + (x + 1) * right + 32) shr 6
                    p[total - 1] = right
                } else {
                    // 8-41 to 8-45: [1 2 1] along the line, its two ends kept.
                    p[0] = l[0]
                    for (i in 1 until total - 1) p[i] = (l[i - 1] + 2 * l[i] + l[i + 1] + 2) shr 2
                    p[total - 1] = l[total - 1]
                }
            }
        }
        val max = (1 shl bitDepth) - 1
        val base = yTb * stride + xTb
        when (mode) {
            INTRA_PLANAR -> {
                // 8-46.
                val topRight = p[corner + 1 + n]
                val bottomLeft = p[corner - 1 - n]
                for (y in 0 until n) {
                    val left = p[corner - 1 - y]
                    val row = base + y * stride
                    for (x in 0 until n) {
                        val v = (n - 1 - x) * left + (x + 1) * topRight + (n - 1 - y) * p[corner + 1 + x] + (y + 1) * bottomLeft + n
                        plane[row + x] = (v shr (log2 + 1)).toShort()
                    }
                }
            }
            INTRA_DC -> {
                // 8-47 to 8-52.
                var sum = n
                for (i in 0 until n) sum += p[corner + 1 + i] + p[corner - 1 - i]
                val dc = sum shr (log2 + 1)
                for (y in 0 until n) {
                    val row = base + y * stride
                    for (x in 0 until n) plane[row + x] = dc.toShort()
                }
                if (cIdx == 0 && n < 32 && boundaryFilter) {
                    plane[base] = ((p[corner - 1] + 2 * dc + p[corner + 1] + 2) shr 2).toShort()
                    for (x in 1 until n) plane[base + x] = ((p[corner + 1 + x] + 3 * dc + 2) shr 2).toShort()
                    for (y in 1 until n) plane[base + y * stride] = ((p[corner - 1 - y] + 3 * dc + 2) shr 2).toShort()
                }
            }
            else -> angular(plane, stride, base, p, n, mode, cIdx == 0 && n < 32 && !bypassRdpcm && boundaryFilter, max)
        }
    }

    /** 8.4.4.2.6 for predModeIntra 2 to 34; [edgeFilter] when disableIntraBoundaryFilter is 0 for a luma block below 32. */
    private fun angular(plane: ShortArray, stride: Int, base: Int, p: IntArray, n: Int, mode: Int, edgeFilter: Boolean, max: Int) {
        val corner = 2 * n
        val angle = HevcTables.INTRA_PRED_ANGLE[mode - 2]
        val r = ref
        // ref[ x ] sits at r[ x + n ], as the extension of 8-54 and 8-62 reaches x = -n.
        val vertical = mode >= 18
        // The main reference runs along the top row for the vertical modes and down the left column for
        // the others; the side one, which 8-54 and 8-62 project onto it, the other way.
        val dir = if (vertical) 1 else -1
        for (x in 0..n) r[n + x] = p[corner + dir * x]
        val last = (n * angle) shr 5
        if (angle < 0) {
            if (last < -1) {
                val inv = invAngle(angle)
                for (x in last..-1) r[n + x] = p[corner - dir * ((x * inv + 128) shr 8)]
            }
        } else {
            for (x in n + 1..2 * n) r[n + x] = p[corner + dir * x]
        }
        for (k in 0 until n) {
            val iIdx = ((k + 1) * angle) shr 5
            val iFact = ((k + 1) * angle) and 31
            for (j in 0 until n) {
                val a = r[n + j + iIdx + 1]
                val v = if (iFact != 0) ((32 - iFact) * a + iFact * r[n + j + iIdx + 2] + 16) shr 5 else a
                // Vertical modes run k over rows and j along them; the others the transpose.
                if (vertical) plane[base + k * stride + j] = v.toShort() else plane[base + j * stride + k] = v.toShort()
            }
        }
        if (edgeFilter) {
            val c = p[corner]
            if (mode == 26) {
                // 8-60.
                val top = p[corner + 1]
                for (y in 0 until n) plane[base + y * stride] = (top + ((p[corner - 1 - y] - c) shr 1)).coerceIn(0, max).toShort()
            } else if (mode == 10) {
                // 8-68.
                val left = p[corner - 1]
                for (x in 0 until n) plane[base + x] = (left + ((p[corner + 1 + x] - c) shr 1)).coerceIn(0, max).toShort()
            }
        }
    }

    /**
     * Fills [line] with the reference samples of 8.4.4.2.1 and substitutes those not available
     * (8.4.4.2.2). Availability is the same over a minimum transform block, 4 luma samples, so it is
     * asked once a unit.
     */
    private fun gather(plane: ShortArray, stride: Int, xTb: Int, yTb: Int, n: Int, sw: Int, sh: Int, bitDepth: Int) {
        val l = line
        val corner = 2 * n
        val total = 4 * n + 1
        val xCurr = xTb shl sw
        val yCurr = yTb shl sh
        var any = false
        var all = true
        // The left column, p[ -1 ][ y ] at line[ 2N - 1 - y ], in units of 4 luma rows.
        val unitY = 4 shr sh
        var y = 0
        while (y < 2 * n) {
            val a = d.intraAvailable(xCurr, yCurr, (xTb - 1) shl sw, (yTb + y) shl sh)
            for (k in y until y + unitY) {
                ok[corner - 1 - k] = a
                if (a) l[corner - 1 - k] = plane[(yTb + k) * stride + xTb - 1].toInt() and 0xFFFF
            }
            if (a) any = true else all = false
            y += unitY
        }
        val c = d.intraAvailable(xCurr, yCurr, (xTb - 1) shl sw, (yTb - 1) shl sh)
        ok[corner] = c
        if (c) {
            l[corner] = plane[(yTb - 1) * stride + xTb - 1].toInt() and 0xFFFF
            any = true
        } else {
            all = false
        }
        // The top row, p[ x ][ -1 ] at line[ 2N + 1 + x ].
        val unitX = 4 shr sw
        var x = 0
        while (x < 2 * n) {
            val a = d.intraAvailable(xCurr, yCurr, (xTb + x) shl sw, (yTb - 1) shl sh)
            val row = (yTb - 1) * stride + xTb
            for (k in x until x + unitX) {
                ok[corner + 1 + k] = a
                if (a) l[corner + 1 + k] = plane[row + k].toInt() and 0xFFFF
            }
            if (a) any = true else all = false
            x += unitX
        }
        if (all) return
        if (!any) {
            l.fill(1 shl (bitDepth - 1), 0, total)
            return
        }
        // 8.4.4.2.2: the first sample takes the first available one; each other the one before it.
        if (!ok[0]) {
            var i = 1
            while (!ok[i]) i++
            l[0] = l[i]
        }
        for (i in 1 until total) if (!ok[i]) l[i] = l[i - 1]
    }

    companion object {
        const val INTRA_PLANAR = 0
        const val INTRA_DC = 1

        private fun abs(v: Int) = if (v < 0) -v else v

        /**
         * invAngle of Table 8-6 for a negative [angle]: 256 * 32 / intraPredAngle rounded to the nearest
         * integer, which gives every entry of the table.
         */
        fun invAngle(angle: Int): Int = -((8192 + (-angle) / 2) / -angle)
    }
}
