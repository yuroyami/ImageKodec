package io.github.yuroyami.imagekodec.heic

/**
 * Chooses the SAO parameters of each CTB (7.4.9.3) on the deblocked picture of [rec], one CTB row at a time,
 * just before [SaoFilter] applies them. For each component it gathers, per band and per edge class and
 * category of 8.7.3, the sum and count of the differences between the source [orig] and the deblocked samples,
 * which give the change of squared error of any offset: n * o^2 - 2 * o * sum. The offsets, the band position,
 * the edge class and the choice among off, band, edge and a merge with the CTB to the left or above then go by
 * that change plus lambda times the bins of sao( ), with the contexts of [costs].
 */
internal class SaoEncoder(
    private val rec: PictureDecoder,
    private val orig: Array<ShortArray>,
    qp: Int,
    private val costs: CabacEncoder,
) {
    private val sps = rec.sps
    private val params = rec.sao!!
    private val comps = if (rec.chromaArrayType == 0) 1 else 3
    private val widthInCtbs = sps.picWidthInCtbs
    private val heightInCtbs = sps.picHeightInCtbs
    private val ctbLog2 = sps.ctbLog2
    private val planeWidth = IntArray(comps) { c -> if (c == 0) rec.width else rec.width shr rec.subWShift }
    private val planeHeight = IntArray(comps) { c -> if (c == 0) rec.height else rec.height shr rec.subHShift }
    private val ctbWidth = IntArray(comps) { c -> if (c == 0) 1 shl ctbLog2 else (1 shl ctbLog2) shr rec.subWShift }
    private val ctbHeight = IntArray(comps) { c -> if (c == 0) 1 shl ctbLog2 else (1 shl ctbLog2) shr rec.subHShift }
    private val bitDepth = IntArray(comps) { c -> if (c == 0) sps.bitDepthY else sps.bitDepthC }
    private val lambda = LongArray(comps) { c -> HevcEncoderTables.LAMBDA_Q8[qp].toLong() shl (2 * (bitDepth[c] - 8)) }

    /** The deblocked last line of the CTB row before, which SAO has since changed in the picture. */
    private val lastLine = Array(comps) { c -> ShortArray(planeWidth[c]) }

    // Per component of the current CTB: band sums and counts, then edge sums and counts per class and edgeIdx.
    private val bandSum = Array(comps) { LongArray(32) }
    private val bandCount = Array(comps) { IntArray(32) }
    private val edgeSum = Array(comps) { LongArray(4 * 5) }
    private val edgeCount = Array(comps) { IntArray(4 * 5) }

    private val candidate = IntArray(18)
    private val best = IntArray(18)

    /** Chooses the parameters of CTB row [ry], whose rows are deblocked and the row below too. */
    fun decideRow(ry: Int) {
        for (rx in 0 until widthInCtbs) decideCtb(rx, ry)
        for (c in 0 until comps) {
            val y1 = minOf((ry + 1) * ctbHeight[c], planeHeight[c])
            rec.planes[c].copyInto(lastLine[c], 0, (y1 - 1) * rec.strides[c], (y1 - 1) * rec.strides[c] + planeWidth[c])
        }
    }

    private fun decideCtb(rx: Int, ry: Int) {
        val rs = ry * widthInCtbs + rx
        for (c in 0 until comps) gather(c, rx, ry)
        val at = rs * 18
        // The parameters coded here: each component's best on its own, Cb and Cr sharing a type and class.
        best.fill(0)
        var cost = lumaParams(best)
        if (comps == 3) cost += chromaParams(best)
        var bins = 0
        if (rx > 0) bins += costs.costOf(Ctx.SAO_MERGE, 0)
        if (ry > 0) bins += costs.costOf(Ctx.SAO_MERGE, 0)
        cost += lambda[0] * bins
        var choice = best
        if (rx > 0) {
            params.copyInto(candidate, 0, at - 18, at - 18 + 18)
            val merged = distortion(candidate) + lambda[0] * costs.costOf(Ctx.SAO_MERGE, 1)
            if (merged < cost) {
                cost = merged
                choice = candidate.copyOf()
            }
        }
        if (ry > 0) {
            val up = at - widthInCtbs * 18
            params.copyInto(candidate, 0, up, up + 18)
            val mergeBins = (if (rx > 0) costs.costOf(Ctx.SAO_MERGE, 0) else 0) + costs.costOf(Ctx.SAO_MERGE, 1)
            val merged = distortion(candidate) + lambda[0] * mergeBins
            if (merged < cost) {
                choice = candidate.copyOf()
            }
        }
        choice.copyInto(params, at)
    }

    /** The change of squared error, in 2^16, of applying the parameters [p] to the current CTB. */
    private fun distortion(p: IntArray): Long {
        var d = 0L
        for (c in 0 until comps) {
            val o = c * 6
            when (p[o]) {
                1 -> for (i in 0 until 4) {
                    val band = (p[o + 1] + i) and 31
                    d += delta(bandSum[c][band], bandCount[c][band], p[o + 2 + i])
                }
                2 -> {
                    val k = p[o + 1]
                    for ((i, e) in EDGE_IDX.withIndex()) d += delta(edgeSum[c][k * 5 + e], edgeCount[c][k * 5 + e], p[o + 2 + i])
                }
            }
        }
        return d shl 16
    }

    private fun delta(sum: Long, count: Int, offset: Int): Long = count.toLong() * offset * offset - 2L * offset * sum

    /** The best luma parameters into [out], returning their cost. */
    private fun lumaParams(out: IntArray): Long {
        val typeOff = costs.costOf(Ctx.SAO_TYPE, 0)
        val typeOn = costs.costOf(Ctx.SAO_TYPE, 1) + 256
        var bestCost = lambda[0] * typeOff
        val tmp = IntArray(6)
        // Band offset.
        val bo = bandParams(0, tmp)
        val boCost = bo + lambda[0] * (typeOn + 5 * 256)
        if (boCost < bestCost) {
            bestCost = boCost
            tmp.copyInto(out, 0)
        }
        for (k in 0 until 4) {
            val eo = edgeParams(0, k, tmp) + lambda[0] * (typeOn + 2 * 256)
            if (eo < bestCost) {
                bestCost = eo
                tmp.copyInto(out, 0)
            }
        }
        return bestCost
    }

    /** The best chroma parameters into [out] at 6 and 12, one type and edge class for both, returning their cost. */
    private fun chromaParams(out: IntArray): Long {
        val lam = lambda[1]
        val typeOff = costs.costOf(Ctx.SAO_TYPE, 0)
        val typeOn = costs.costOf(Ctx.SAO_TYPE, 1) + 256
        var bestCost = lam * typeOff
        val cb = IntArray(6)
        val cr = IntArray(6)
        out.fill(0, 6, 18)
        val bo = bandParams(1, cb) + bandParams(2, cr) + lam * (typeOn + 10 * 256)
        if (bo < bestCost) {
            bestCost = bo
            cb.copyInto(out, 6)
            cr.copyInto(out, 12)
        }
        for (k in 0 until 4) {
            val eo = edgeParams(1, k, cb) + edgeParams(2, k, cr) + lam * (typeOn + 2 * 256)
            if (eo < bestCost) {
                bestCost = eo
                cb.copyInto(out, 6)
                cr.copyInto(out, 12)
            }
        }
        return bestCost
    }

    /**
     * The offset of least cost for a category of [count] samples whose differences add to [sum], in [lo]..[hi]:
     * the rounded mean and the values between it and 0, each with the bins of its sao_offset_abs (TR, cMax of
     * 7.4.9.3) and, for [signed], sao_offset_sign. Sets [chosenOffset] and returns its cost.
     */
    private fun offset(c: Int, sum: Long, count: Int, lo: Int, hi: Int, signed: Boolean): Long {
        chosenOffset = 0
        var bestCost = lambda[c] * 256
        if (count == 0) return bestCost
        val cMax = (1 shl (minOf(bitDepth[c], 10) - 5)) - 1
        var o = if (sum >= 0) ((sum + count / 2) / count).toInt() else -((-sum + count / 2) / count).toInt()
        o = o.coerceIn(maxOf(lo, -cMax), minOf(hi, cMax))
        val step = if (o > 0) -1 else 1
        while (o != 0) {
            val a = if (o < 0) -o else o
            val bins = a + (if (a < cMax) 1 else 0) + (if (signed) 1 else 0)
            val cost = (delta(sum, count, o) shl 16) + lambda[c] * (bins shl 8)
            if (cost < bestCost) {
                bestCost = cost
                chosenOffset = o
            }
            o += step
        }
        return bestCost
    }

    private var chosenOffset = 0

    /** Band offset for component [c]: the four bands in a row that gain most, into [out]; returns the cost of their offsets. */
    private fun bandParams(c: Int, out: IntArray): Long {
        val gain = LongArray(32)
        val chosen = IntArray(32)
        val cMax = (1 shl (minOf(bitDepth[c], 10) - 5)) - 1
        for (b in 0 until 32) {
            gain[b] = offset(c, bandSum[c][b], bandCount[c][b], -cMax, cMax, true)
            chosen[b] = chosenOffset
        }
        var bestStart = 0
        var bestCost = Long.MAX_VALUE
        for (p in 0 until 32) {
            var s = 0L
            for (i in 0 until 4) s += gain[(p + i) and 31]
            if (s < bestCost) {
                bestCost = s
                bestStart = p
            }
        }
        out.fill(0)
        out[0] = 1
        out[1] = bestStart
        var cost = 0L
        for (i in 0 until 4) {
            val b = (bestStart + i) and 31
            out[2 + i] = chosen[b]
            cost += gain[b]
        }
        return cost
    }

    /** Edge offset class [k] for component [c] into [out]; returns the cost of its offsets. */
    private fun edgeParams(c: Int, k: Int, out: IntArray): Long {
        out.fill(0)
        out[0] = 2
        out[1] = k
        var cost = 0L
        val cMax = (1 shl (minOf(bitDepth[c], 10) - 5)) - 1
        for ((i, e) in EDGE_IDX.withIndex()) {
            // 7.4.9.3: the first two categories add, the last two take away.
            val positive = i < 2
            val o = offset(c, edgeSum[c][k * 5 + e], edgeCount[c][k * 5 + e], if (positive) 0 else -cMax, if (positive) cMax else 0, false)
            out[2 + i] = chosenOffset
            cost += o
        }
        return cost
    }

    /** Gathers the band and edge statistics of component [c] of the CTB ([rx], [ry]). */
    private fun gather(c: Int, rx: Int, ry: Int) {
        val bs = bandSum[c]
        val bc = bandCount[c]
        val es = edgeSum[c]
        val ec = edgeCount[c]
        bs.fill(0)
        bc.fill(0)
        es.fill(0)
        ec.fill(0)
        val plane = rec.planes[c]
        val src = orig[c]
        val stride = rec.strides[c]
        val w = planeWidth[c]
        val h = planeHeight[c]
        val x0 = rx * ctbWidth[c]
        val y0 = ry * ctbHeight[c]
        val x1 = minOf(x0 + ctbWidth[c], w)
        val y1 = minOf(y0 + ctbHeight[c], h)
        val shift = bitDepth[c] - 5
        val above = lastLine[c]
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val v = plane[y * stride + x].toInt() and 0xFFFF
                val diff = (src[y * stride + x].toInt() and 0xFFFF) - v
                bs[v shr shift] += diff.toLong()
                bc[v shr shift]++
                for (k in 0 until 4) {
                    val hx = H_POS[k]
                    val vy = V_POS[k]
                    val ax = x + hx
                    val ay = y + vy
                    val bx = x - hx
                    val by = y - vy
                    if (ax < 0 || ax >= w || bx < 0 || bx >= w || ay < 0 || ay >= h || by < 0 || by >= h) continue
                    val a = if (ay < y0) above[ax].toInt() and 0xFFFF else plane[ay * stride + ax].toInt() and 0xFFFF
                    val b = if (by < y0) above[bx].toInt() and 0xFFFF else plane[by * stride + bx].toInt() and 0xFFFF
                    val e = 2 + (if (v > a) 1 else if (v < a) -1 else 0) + (if (v > b) 1 else if (v < b) -1 else 0)
                    if (e == 2) continue
                    es[k * 5 + e] += diff.toLong()
                    ec[k * 5 + e]++
                }
            }
        }
    }

    private companion object {
        /** Table 8-13: hPos[ 0 ] and vPos[ 0 ] of each SaoEoClass. */
        val H_POS = intArrayOf(-1, 0, -1, 1)
        val V_POS = intArrayOf(0, -1, -1, -1)

        /** The edgeIdx of 8-411 that SaoOffsetVal[ 1 ] to [ 4 ] apply to, after the reordering of 8-412. */
        val EDGE_IDX = intArrayOf(0, 1, 3, 4)
    }
}
