package io.github.yuroyami.imagekodec.heic

/**
 * The quantizer of the encoder: transform coefficients to coefficient levels, either by rounding with a dead
 * zone or by rate-distortion optimised quantisation (RDOQ), and then sign data hiding (7.3.8.11).
 *
 * The coefficients come from [ForwardTransform] at their orthonormal value times 2^tShift, tShift =
 * 15 - BitDepth - Log2( nTbS ). A level l stands for l * Qstep with Qstep = levelScale[ qP % 6 ] * 2^( qP / 6 ) / 64
 * (8.6.3), so a coefficient c becomes c * QUANT[ qP % 6 ] / 2^qBits with QUANT the reciprocals of levelScale
 * in 2^20 and qBits = 14 + qP / 6 + tShift. Errors are kept in units of a level with 10 fractional bits, which
 * is the sample domain divided by Qstep: the transforms are orthonormal up to their scale.
 *
 * RDOQ is this encoder's design, not a port. It visits the coefficients in the order the decoder parses them
 * (7.3.8.11), from the last one back to the first, and for each picks among the rounded level, one less, and
 * 0 for small levels, the one of least distortion plus lambda times the bins it costs, with the contexts the
 * decoder will use: sigCtx from the coded sub-blocks to the right and below, the greater1 context set from the
 * sub-block before, the Rice parameter from the levels before. Then it drops a whole sub-block where coding none
 * of it costs less, and moves the last significant coefficient back where that costs less. Rates come from the
 * CABAC states of the encoder's estimate at the start of the coding unit.
 */
internal class Quantizer(private val est: CabacEncoder) {
    private val value = IntArray(32 * 32)
    private val costSel = IntArray(32 * 32)
    private val sigPart = IntArray(32 * 32)
    private val zeroCost = IntArray(32 * 32)
    private val subFlags = IntArray(64)
    private val lastXCost = IntArray(32)
    private val lastYCost = IntArray(32)

    /**
     * Quantizes the coefficients [c] of an n by n block, n = 1 shl [log2], of component [cIdx] into the signed
     * levels [levels] (raster order), at qP [qp] (with QpBdOffset) for a block of [bitDepth] bits. [lambda] is
     * the Lagrange multiplier of the picture in 1/256 for squared sample errors; [rdoq] chooses RDOQ over plain
     * rounding, [hide] sign data hiding. [cbfCtx] is the context of the block's coded block flag, or -1 when the
     * flag is not coded. Returns the number of levels other than 0.
     */
    fun quantize(
        c: IntArray, levels: IntArray, log2: Int, cIdx: Int, scanIdx: Int, qp: Int, bitDepth: Int,
        lambda: Long, rdoq: Boolean, hide: Boolean, cbfCtx: Int,
    ): Int {
        val n = 1 shl log2
        val total = n * n
        val q = QUANT[qp % 6]
        val qBits = 14 + qp / 6 + 15 - bitDepth - log2
        val v = value
        for (i in 0 until total) {
            val a = c[i]
            val abs = if (a < 0) -a else a
            v[i] = (if (abs > 65535) 65535 else abs) * q
        }
        var count = if (rdoq) rdoq(c, levels, log2, cIdx, scanIdx, qp, qBits, lambda, cbfCtx) else {
            // A dead zone of two thirds below the rounding point, as intra residuals favour small levels.
            val offset = 171 shl (qBits - 9)
            var nz = 0
            for (i in 0 until total) {
                var l = (v[i] + offset) shr qBits
                if (l > 32767) l = 32767
                levels[i] = if (c[i] < 0) -l else l
                if (l != 0) nz++
            }
            nz
        }
        if (count > 0 && hide) hideSigns(c, levels, log2, scanIdx, qBits)
        if (count > 0) {
            count = 0
            for (i in 0 until total) if (levels[i] != 0) count++
        }
        return count
    }

    private fun rdoq(c: IntArray, levels: IntArray, log2: Int, cIdx: Int, scanIdx: Int, qp: Int, qBits: Int, lambda: Long, cbfCtx: Int): Int {
        val n = 1 shl log2
        val chroma = cIdx > 0
        val v = value
        val frac = qBits - 10
        val half = 1 shl (qBits - 1)
        val ls = PictureDecoder.LEVEL_SCALE[qp % 6].toLong()
        // lambda over Qstep squared, in 2^12 per 1/256 bit: the multiplier of a rate against level errors in 2^20.
        val lam = ((lambda shl 16) / (ls * ls shl (2 * (qp / 6)))).coerceIn(1L, Int.MAX_VALUE.toLong() shr 8).toInt()
        val subLog2 = log2 - 2
        val subScan = HevcScan.order(subLog2, scanIdx)
        val scan = HevcScan.order(2, scanIdx)
        val subSide = 1 shl subLog2
        // The last position the plain rounding leaves.
        var lastG = -1
        for (g in (1 shl (2 * log2)) - 1 downTo 0) {
            val sub = subScan[g shr 4]
            val pos = scan[g and 15]
            val r = (((sub shr 8) shl 2) + (pos shr 8)) * n + ((sub and 0xFF) shl 2) + (pos and 0xFF)
            if (v[r] + half shr qBits != 0) {
                lastG = g
                break
            }
        }
        levels.fill(0, 0, n * n)
        if (lastG < 0) return 0
        lastCosts(log2, chroma)
        val e = est
        val sigBase = Ctx.SIG_COEFF + (if (chroma) 27 else 0)
        val lastSub = lastG shr 4
        subFlags.fill(0, 0, subSide * subSide)
        var prevG1 = 1
        var firstSubblock = true
        for (i in lastSub downTo 0) {
            val sub = subScan[i]
            val xS = sub and 0xFF
            val yS = sub shr 8
            val right = if (xS < subSide - 1) subFlags[yS * subSide + xS + 1] else 0
            val below = if (yS < subSide - 1) subFlags[(yS + 1) * subSide + xS] else 0
            val prevCsbf = right or (below shl 1)
            var ctxSet = if (i == 0 || chroma) 0 else 2
            if (!firstSubblock && prevG1 == 0) ctxSet++
            val g1Base = Ctx.GREATER1 + ctxSet * 4 + (if (chroma) 16 else 0)
            val g2Ctx = Ctx.GREATER2 + ctxSet + (if (chroma) 4 else 0)
            var g1Ctx = 1
            var numNz = 0
            var g1Seen = false
            var rice = 0
            var subSel = 0
            var subZero = 0
            val startK = if (i == lastSub) lastG and 15 else 15
            for (k in 15 downTo 0) {
                val g = (i shl 4) + k
                val pos = scan[k]
                val xP = pos and 0xFF
                val yP = pos shr 8
                val r = ((yS shl 2) + yP) * n + (xS shl 2) + xP
                if (k > startK) {
                    costSel[g] = 0
                    sigPart[g] = 0
                    zeroCost[g] = 0
                    continue
                }
                val vv = v[r]
                val lMax = (vv + half) shr qBits
                val scaled = vv shr frac
                val d0 = if (lMax <= 2) scaled * scaled else ZERO_CAP
                val isLast = g == lastG
                // The last position codes no sig_coeff_flag (and 9.3.4.2.5 gives none for the 4x4 corner).
                val sigCtx = if (isLast) 0 else sigBase + PictureDecoder.sigContext(log2, cIdx, scanIdx, xS, yS, xP, yP, prevCsbf)
                val sig0 = if (isLast) 0 else e.costOf(sigCtx, 0)
                val sig1 = if (isLast) 0 else e.costOf(sigCtx, 1)
                var best = 0
                var bestCost = if (isLast || lMax > 2) Int.MAX_VALUE else d0 + lam * sig0
                var l = if (lMax > 32767) 32767 else lMax
                val lowest = if (lMax >= 2) lMax - 1 else 1
                while (l >= lowest && l >= 1) {
                    val d = scaled - (l shl 10)
                    var rate = sig1 + 256
                    if (numNz < 8) {
                        val g1 = g1Base + (if (g1Ctx > 3) 3 else g1Ctx)
                        if (l == 1) {
                            rate += e.costOf(g1, 0)
                        } else {
                            rate += e.costOf(g1, 1)
                            if (!g1Seen) {
                                rate += e.costOf(g2Ctx, if (l > 2) 1 else 0)
                                if (l > 2) rate += remainingBits(l - 3, rice) shl 8
                            } else {
                                rate += remainingBits(l - 2, rice) shl 8
                            }
                        }
                    } else {
                        rate += remainingBits(l - 1, rice) shl 8
                    }
                    val cost = d * d + lam * rate
                    if (cost < bestCost) {
                        bestCost = cost
                        best = l
                    }
                    l--
                }
                if (best == 0 && bestCost == Int.MAX_VALUE) {
                    best = 1
                    bestCost = 0
                }
                levels[r] = if (c[r] < 0) -best else best
                costSel[g] = bestCost
                sigPart[g] = if (best != 0) lam * sig1 else 0
                zeroCost[g] = d0
                subSel += bestCost
                subZero += d0
                if (best != 0) {
                    val firstG1 = numNz < 8 && best > 1 && !g1Seen
                    val coded = if (numNz < 8) (if (best == 1) false else !firstG1 || best > 2) else true
                    if (numNz < 8) {
                        if (g1Ctx > 0) g1Ctx = if (best > 1) 0 else g1Ctx + 1
                        if (best > 1) g1Seen = true
                    }
                    if (coded && best > 3 * (1 shl rice)) rice = minOf(rice + 1, 4)
                    numNz++
                }
            }
            var codedSub = numNz > 0
            if (codedSub && i > 0 && i < lastSub) {
                // coded_sub_block_flag: all of the sub-block against none of it.
                val ctx = Ctx.CODED_SUB_BLOCK + minOf(right + below, 1) + (if (chroma) 2 else 0)
                val keep = subSel.toLong() + lam.toLong() * e.costOf(ctx, 1)
                val drop = subZero.toLong() + lam.toLong() * e.costOf(ctx, 0)
                if (drop < keep) {
                    codedSub = false
                    for (k in 0..15) {
                        val g = (i shl 4) + k
                        val pos = scan[k]
                        levels[((yS shl 2) + (pos shr 8)) * n + (xS shl 2) + (pos and 0xFF)] = 0
                        costSel[g] = zeroCost[g]
                        sigPart[g] = 0
                    }
                }
            }
            subFlags[yS * subSide + xS] = if (codedSub || i == 0 || i == lastSub) 1 else 0
            if (codedSub) {
                prevG1 = g1Ctx
                firstSubblock = false
            }
        }
        // The last significant coefficient: each position that holds a level against the bins it saves.
        var acc = 0L
        var best = Long.MAX_VALUE
        var bestG = -1
        val swap = scanIdx == HevcScan.VERTICAL
        for (g in 0..lastG) {
            val sub = subScan[g shr 4]
            val pos = scan[g and 15]
            val x = ((sub and 0xFF) shl 2) + (pos and 0xFF)
            val y = ((sub shr 8) shl 2) + (pos shr 8)
            if (levels[y * n + x] != 0) {
                val lastCost = if (swap) lastXCost[y] + lastYCost[x] else lastXCost[x] + lastYCost[y]
                val j = acc + costSel[g] - sigPart[g] - zeroCost[g] + lam.toLong() * lastCost
                if (j < best) {
                    best = j
                    bestG = g
                }
            }
            acc += (costSel[g] - zeroCost[g]).toLong()
        }
        if (bestG < 0) {
            levels.fill(0, 0, n * n)
            return 0
        }
        // No level at all against the best last position, when the coded block flag is coded.
        if (cbfCtx >= 0 && lam.toLong() * (e.costOf(cbfCtx, 0) - e.costOf(cbfCtx, 1)) < best) {
            levels.fill(0, 0, n * n)
            return 0
        }
        var nz = 0
        for (g in 0..lastG) {
            val sub = subScan[g shr 4]
            val pos = scan[g and 15]
            val r = (((sub shr 8) shl 2) + (pos shr 8)) * n + ((sub and 0xFF) shl 2) + (pos and 0xFF)
            if (g > bestG) levels[r] = 0 else if (levels[r] != 0) nz++
        }
        return nz
    }

    /** The cost in 1/256 bits of each last_sig_coeff_x and _y value: prefix bins in their contexts and suffix bits. */
    private fun lastCosts(log2: Int, chroma: Boolean) {
        val n = 1 shl log2
        val cMax = (log2 shl 1) - 1
        val ctxOffset = if (chroma) 15 else 3 * (log2 - 2) + ((log2 - 1) shr 2)
        val ctxShift = if (chroma) log2 - 2 else (log2 + 1) shr 2
        for (p in 0 until n) {
            val prefix = lastPrefix(p)
            var cx = 0
            var cy = 0
            for (b in 0 until prefix) {
                cx += est.costOf(Ctx.LAST_X_PREFIX + ctxOffset + (b shr ctxShift), 1)
                cy += est.costOf(Ctx.LAST_Y_PREFIX + ctxOffset + (b shr ctxShift), 1)
            }
            if (prefix < cMax) {
                cx += est.costOf(Ctx.LAST_X_PREFIX + ctxOffset + (prefix shr ctxShift), 0)
                cy += est.costOf(Ctx.LAST_Y_PREFIX + ctxOffset + (prefix shr ctxShift), 0)
            }
            val suffix = if (prefix > 3) ((prefix shr 1) - 1) shl 8 else 0
            lastXCost[p] = cx + suffix
            lastYCost[p] = cy + suffix
        }
    }

    /**
     * Sign data hiding: in each sub-block whose first and last levels lie more than three scan positions
     * apart the decoder takes the sign of the first level from the parity of the sum of levels (7.4.9.11), so
     * where the parity disagrees one level moves by one, the one whose error grows least.
     */
    private fun hideSigns(c: IntArray, levels: IntArray, log2: Int, scanIdx: Int, qBits: Int) {
        val n = 1 shl log2
        val subLog2 = log2 - 2
        val subScan = HevcScan.order(subLog2, scanIdx)
        val scan = HevcScan.order(2, scanIdx)
        val frac = qBits - 10
        val v = value
        for (i in 0 until (1 shl (2 * subLog2))) {
            val sub = subScan[i]
            val base = ((sub shr 8) shl 2) * n + ((sub and 0xFF) shl 2)
            var first = -1
            var last = -1
            var sum = 0
            for (k in 0..15) {
                val pos = scan[k]
                val l = levels[base + (pos shr 8) * n + (pos and 0xFF)]
                if (l != 0) {
                    if (first < 0) first = k
                    last = k
                    sum += if (l < 0) -l else l
                }
            }
            if (first < 0 || last - first <= 3) continue
            val firstPos = scan[first]
            val negative = levels[base + (firstPos shr 8) * n + (firstPos and 0xFF)] < 0
            if ((sum and 1 == 1) == negative) continue
            var bestDelta = Int.MAX_VALUE
            var bestR = -1
            var bestChange = 0
            for (k in first..last) {
                val pos = scan[k]
                val r = base + (pos shr 8) * n + (pos and 0xFF)
                val l = levels[r]
                val abs = if (l < 0) -l else l
                val scaled = v[r] shr frac
                val now = scaled - (abs shl 10)
                val up = now - 1024
                val upDelta = up * up - now * now
                if (abs < 32767 && upDelta < bestDelta && (abs > 0 || (k > first && c[r] != 0))) {
                    bestDelta = upDelta
                    bestR = r
                    bestChange = 1
                }
                if (abs > 0 && !(abs == 1 && (k == first || k == last))) {
                    val down = now + 1024
                    val downDelta = down * down - now * now
                    if (downDelta < bestDelta) {
                        bestDelta = downDelta
                        bestR = r
                        bestChange = -1
                    }
                }
            }
            if (bestR < 0) {
                // Every level is held by its place: raise the first, which keeps the positions as they are.
                bestR = base + (firstPos shr 8) * n + (firstPos and 0xFF)
                bestChange = 1
            }
            val l = levels[bestR]
            val abs = (if (l < 0) -l else l) + bestChange
            levels[bestR] = if (l < 0 || (l == 0 && c[bestR] < 0)) -abs else abs
        }
    }

    companion object {
        /** The reciprocals of levelScale (8.6.3) in 2^20, rounded: QUANT[ k ] * levelScale[ k ] is 2^20 within half a unit. */
        val QUANT: IntArray = IntArray(6) { k -> ((1 shl 20) + PictureDecoder.LEVEL_SCALE[k] / 2) / PictureDecoder.LEVEL_SCALE[k] }

        /** The distortion of dropping a level of 3 or more, which RDOQ never does: large, and safe to add up in an Int. */
        private const val ZERO_CAP = 1 shl 26

        /** The prefix of last_sig_coeff_x_prefix for a position [p]: the inverse of 7-74 and 7-76. */
        fun lastPrefix(p: Int): Int {
            if (p < 4) return p
            var prefix = 4
            while (true) {
                val next = prefix + 1
                val start = (1 shl ((next shr 1) - 1)) * (2 + (next and 1))
                if (start > p) return prefix
                prefix = next
            }
        }

        /** The bypass bins of coeff_abs_level_remaining [rem] with Rice parameter [rice] (9.3.3.11). */
        fun remainingBits(rem: Int, rice: Int): Int {
            val prefix = rem shr rice
            if (prefix < 4) return prefix + 1 + rice
            var v = rem - (4 shl rice)
            var k = rice + 1
            var len = 4
            while (v >= (1 shl k)) {
                v -= 1 shl k
                k++
                len++
            }
            return len + 1 + k
        }
    }
}
