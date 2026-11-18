package io.github.yuroyami.imagekodec.codec.avif

/**
 * The second pass of the AV1 encoder (#176): the loop filter levels and the CDEF strengths, chosen on the first
 * pass's reconstruction with the decoder's own filters ([Av1LoopFilter], [Av1Cdef]), each candidate measured by
 * its squared error against the source. The frame is filtered in place and put back after each candidate.
 *
 * Deblocking tries luma levels around a guess from the quantizer and keeps the best, then chroma the same way.
 * CDEF filters the deblocked frame once for each of a set of strength pairs, keeps each 64 by 64 block's squared
 * error under each, and picks the 1, 2, 4 or 8 pairs, and each block's choice among them, that cost least with
 * `cdef_idx` priced at its bits.
 */
internal class Av1FilterSearch(
    private val d: Av1FrameDecoder,
    private val src: Av1Source,
    private val record: Av1Record,
    private val effort: Int,
) {
    private val fh = d.fh
    private val lambda256: Long = run {
        val q = Av1Transform.acQ(d.bitDepth, fh.baseQIdx).toLong()
        maxOf(1L, q * q * 38 / 64)
    }

    /** The restoration units chosen by [search], for the writing pass. */
    var lr: Av1LrChoice? = null
        private set

    fun search(base: Av1FrameParams, stream: Av1StreamParams): Av1FrameParams {
        if (fh.codedLossless) return base
        val levels = deblock()
        // The deblocked frame, which CDEF filters; loop restoration reads its rows around each stripe.
        applyDeblock(levels)
        val unitShift = if (src.width * src.height > 256 * 256) 1 else 0
        val unitSize = 64 shl unitShift
        var saved: Array<HashMap<Int, ShortArray>>? = null
        if (stream.enableRestoration) {
            for (p in 0 until d.numPlanes) {
                fh.frameRestorationType[p] = Av1FrameHeader.RESTORE_SGRPROJ
                fh.loopRestorationSize[p] = unitSize
            }
            saved = Av1LoopRestoration.savedRows(d)
            fh.frameRestorationType.fill(Av1FrameHeader.RESTORE_NONE)
        }
        val cdef = if (stream.enableCdef) cdef() else null
        var lrType = IntArray(3)
        if (saved != null) {
            if (cdef != null) applyCdef(cdef)
            lrType = restoration(saved, unitSize)
        }
        return Av1FrameParams(
            base.baseQIdx, base.allowScreenContentTools, base.tileColsLog2, base.tileRowsLog2,
            loopFilterLevel = levels,
            cdefDamping = cdef?.damping ?: 3, cdefBits = cdef?.bits ?: 0,
            cdefY = cdef?.y ?: IntArray(8), cdefUv = cdef?.uv ?: IntArray(8),
            lrType = lrType, lrUnitShift = unitShift,
            reducedTxSet = base.reducedTxSet,
        )
    }

    /** CDEF as [c] and the blocks' chosen indices in [record] give it, on the frame in place. */
    private fun applyCdef(c: Cdef) {
        fh.cdefDamping = c.damping
        fh.cdefBits = c.bits
        for (i in 0 until (1 shl c.bits)) setStrength(i, c.y[i], c.uv[i])
        val sbRows = (d.miRows + 15) shr 4
        val sbCols = (d.miCols + 15) shr 4
        for (b in 0 until sbRows * sbCols) {
            val r = (b / sbCols) * 16
            val col = (b % sbCols) * 16
            val m = d.mi(r, col)
            if (d.cdefIdx[m] != -1) d.cdefIdx[m] = record.cdef[(r shl 16) or col] ?: 0
        }
        Av1Cdef.apply(d)
    }

    // ---- loop restoration ----------------------------------------------------------------

    /** The self-guided sets tried: none at effort 1, a spread of four at 2, all sixteen at 3. */
    private fun sgrSets(): IntArray = when (effort) {
        1 -> IntArray(0)
        2 -> intArrayOf(0, 4, 10, 14)
        else -> IntArray(16) { it }
    }

    private class Unit(val type: Int, val set: Int, val w0: Int, val w1: Int)

    /**
     * The self-guided filter of each restoration unit, or none, chosen by the squared error the least-squares
     * projection leaves, against the bits of its parameters; returns lr_type as coded for each plane: 3 for the
     * self-guided filter where some unit uses it, 0 where none does.
     */
    private fun restoration(saved: Array<HashMap<Int, ShortArray>>, unitSize: Int): IntArray {
        val coded = IntArray(3)
        val sets = sgrSets()
        if (sets.isEmpty()) return coded
        val units = arrayOfNulls<Array<Unit>>(3)
        for (p in 0 until d.numPlanes) {
            val sx = if (p > 0) d.subX else 0
            val sy = if (p > 0) d.subY else 0
            val rows = maxOf((Av1.round2(fh.frameHeight, sy) + (unitSize shr 1)) / unitSize, 1)
            val cols = maxOf((Av1.round2(fh.upscaledWidth, sx) + (unitSize shr 1)) / unitSize, 1)
            val n = rows * cols
            val best = Array(n) { Unit(Av1FrameHeader.RESTORE_NONE, 0, 0, 0) }
            val bestJ = LongArray(n) { Long.MAX_VALUE }
            val stats = LongArray(n * 7)
            for (set in sets) {
                stats.fill(0)
                Av1LoopRestoration.sgrStatistics(d, p, set, unitSize, rows, cols, d.frame[p], d.planeWidth[p], saved[p], src.planes[p], stats)
                val r0 = Av1Tables.sgrParams[set * 4]
                val r1 = Av1Tables.sgrParams[set * 4 + 2]
                for (u in 0 until n) {
                    val at = u * 7
                    if (set == sets[0]) bestJ[u] = (stats[at + 6] shl 8) + (lambda256 * 256 shr 8)
                    val (w0, w1) = fit(stats, at, r0, r1)
                    // The squared error the projection leaves: sum of (e - w0 a - w1 b)^2 over 2^14.
                    val aa = stats[at]
                    val ab = stats[at + 1]
                    val bb = stats[at + 2]
                    val ae = stats[at + 3]
                    val be = stats[at + 4]
                    val ee = stats[at + 5]
                    val err = ee - 2 * w0 * ae - 2 * w1 * be + w0.toLong() * w0 * aa + 2L * w0 * w1 * ab + w1.toLong() * w1 * bb
                    val dist = maxOf(0L, err shr 14)
                    // use_sgrproj, the set's four bits and two subexponential codes of about seven bits.
                    val j = (dist shl 8) + (lambda256 * (19 * 256) shr 8)
                    if (j < bestJ[u]) {
                        bestJ[u] = j
                        best[u] = Unit(Av1FrameHeader.RESTORE_SGRPROJ, set, w0, w1)
                    }
                }
            }
            units[p] = best
            if (best.any { it.type != Av1FrameHeader.RESTORE_NONE }) coded[p] = 3
        }
        lr = object : Av1LrChoice {
            override fun unit(coding: Av1Coding, plane: Int, unitRow: Int, unitCol: Int, wiener: IntArray, wienerAt: Int, xqd: IntArray, xqdAt: Int): Int {
                val all = units[plane] ?: return Av1FrameHeader.RESTORE_NONE
                val cols = maxOf((Av1.round2(fh.upscaledWidth, if (plane > 0) d.subX else 0) + (unitSize shr 1)) / unitSize, 1)
                val u = all[unitRow * cols + unitCol]
                if (u.type == Av1FrameHeader.RESTORE_SGRPROJ) {
                    coding.lrSgrSet = u.set
                    xqd[xqdAt] = u.w0
                    xqd[xqdAt + 1] = u.w1
                }
                return u.type
            }
        }
        return coded
    }

    /**
     * The projection weights w0 and w1, in 1/128, that fit the unit's statistics at [at] best, by Cramer's rule on
     * the normal equations scaled to 30 bits, rounded and held to the ranges the syntax codes. A pass whose radius is
     * 0 fixes its weight as the syntax does.
     */
    private fun fit(stats: LongArray, at: Int, r0: Int, r1: Int): Pair<Int, Int> {
        val min0 = Av1Tables.sgrprojXqdMin[0]
        val max0 = Av1Tables.sgrprojXqdMax[0]
        val min1 = Av1Tables.sgrprojXqdMin[1]
        val max1 = Av1Tables.sgrprojXqdMax[1]
        var aa = stats[at]
        var ab = stats[at + 1]
        var bb = stats[at + 2]
        var ae = stats[at + 3]
        var be = stats[at + 4]
        var top = maxOf(maxOf(kotlin.math.abs(aa), kotlin.math.abs(ab)), maxOf(kotlin.math.abs(bb), maxOf(kotlin.math.abs(ae), kotlin.math.abs(be))))
        var shift = 0
        while (top >= (1L shl 30)) {
            top = top shr 1
            shift++
        }
        aa = aa shr shift
        ab = ab shr shift
        bb = bb shr shift
        ae = ae shr shift
        be = be shr shift
        if (r0 == 0) {
            // w0 is 0: one weight on b alone.
            val w1 = if (bb == 0L) 0 else divRound(be, bb).toInt()
            return 0 to w1.coerceIn(min1, max1)
        }
        if (r1 == 0) {
            // w1 is 128 - w0 and f1 is u, so b is 0 and the model is 128 u + w0 a.
            val w0 = (if (aa == 0L) 0 else divRound(ae, aa).toInt()).coerceIn(min0, max0)
            return w0 to (128 - w0).coerceIn(min1, max1)
        }
        val det = aa * bb - ab * ab
        if (det <= 0L) return 0 to 0
        val w0 = divRound(ae * bb - be * ab, det).toInt()
        val w1 = divRound(aa * be - ab * ae, det).toInt()
        return w0.coerceIn(min0, max0) to w1.coerceIn(min1, max1)
    }

    private fun divRound(a: Long, b: Long): Long = if ((a >= 0) == (b > 0)) (a + b / 2) / b else (a - b / 2) / b

    private fun copyFrame(): Array<ShortArray> = Array(d.numPlanes) { d.frame[it].copyOf() }

    private fun restore(saved: Array<ShortArray>) {
        for (p in 0 until d.numPlanes) saved[p].copyInto(d.frame[p])
    }

    private fun planeError(p: Int): Long {
        val stride = d.planeWidth[p]
        val w = src.shownWidth(p, d)
        val h = src.shownHeight(p, d)
        val a = src.planes[p]
        val b = d.frame[p]
        var sum = 0L
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                val e = a[y * stride + x] - b[y * stride + x]
                row += e * e
            }
            sum += row
        }
        return sum
    }

    // ---- deblocking --------------------------------------------------------------------

    private fun applyDeblock(levels: IntArray) {
        levels.copyInto(fh.loopFilterLevel)
        if (levels[0] != 0 || levels[1] != 0) Av1LoopFilter.apply(d)
    }

    /**
     * The loop filter levels by the squared error they leave: luma both ways, then each chroma plane, each by a
     * pattern search from a guess that grows with the quantizer, as the filter's thresholds do: the level and its
     * neighbours a step away, moving to the best and halving the step. Effort 1 keeps the guess.
     */
    private fun deblock(): IntArray {
        val saved = copyFrame()
        val levels = IntArray(4)
        val guess = (fh.baseQIdx * 3 / 32).coerceIn(0, 63)
        val minStep = if (effort >= 3) 1 else 2
        // Luma.
        fun lumaError(l: Int): Long {
            if (l == 0) return planeError(0)
            levels[0] = l
            levels[1] = l
            levels[2] = 0
            levels[3] = 0
            applyDeblock(levels)
            val e = planeError(0)
            restore(saved)
            return e
        }
        val luma = if (effort <= 1) guess else patternSearch(guess, minStep, ::lumaError)
        levels.fill(0)
        levels[0] = luma
        levels[1] = luma
        if (d.numPlanes > 1 && luma > 0) {
            for (p in 1..2) {
                fun chromaError(l: Int): Long {
                    levels[p + 1] = l
                    applyDeblock(levels)
                    val e = planeError(p)
                    restore(saved)
                    return e
                }
                levels[p + 1] = if (effort <= 1) luma else patternSearch(luma, minStep, ::chromaError)
            }
        }
        fh.loopFilterLevel.fill(0)
        return levels
    }

    /** The level from 0 to 63 that [error] puts least, by a pattern search from [start] with steps of 8 down to [minStep]. */
    private fun patternSearch(start: Int, minStep: Int, error: (Int) -> Long): Int {
        val seen = HashMap<Int, Long>()
        fun e(l: Int): Long = seen.getOrPut(l) { error(l) }
        var best = start
        var step = 8
        while (step >= minStep) {
            var moved = true
            while (moved) {
                moved = false
                for (l in intArrayOf(best - step, best + step)) {
                    if (l !in 0..63) continue
                    if (e(l) < e(best)) {
                        best = l
                        moved = true
                    }
                }
            }
            step = step shr 1
        }
        return best
    }

    // ---- CDEF ----------------------------------------------------------------------------

    private class Cdef(val damping: Int, val bits: Int, val y: IntArray, val uv: IntArray)

    /** The strength pairs CDEF tries, primary and secondary, each a header code (primary * 4 + secondary code). */
    private fun presets(): List<Pair<Int, Int>> {
        val all = listOf(
            0 to 0, 1 to 0, 2 to 1, 3 to 1, 4 to 2, 6 to 2, 8 to 3, 10 to 3, 12 to 3, 15 to 3,
            2 to 0, 4 to 0, 6 to 1, 1 to 1, 0 to 1, 0 to 2,
        )
        val n = when (effort) {
            1 -> 4
            2 -> 8
            else -> all.size
        }
        return all.take(n)
    }

    private fun cdef(): Cdef? {
        val sbRows = (d.miRows + 15) shr 4
        val sbCols = (d.miCols + 15) shr 4
        val coded = BooleanArray(sbRows * sbCols) { i -> d.cdefIdx[d.mi((i / sbCols) * 16, (i % sbCols) * 16)] != -1 }
        if (coded.none { it }) return null
        val damping = 3 + minOf(3, fh.baseQIdx shr 6)
        val saved = copyFrame()
        val presets = presets()
        val luma = presets.map { (p, s) -> (p shl 2) or s }
        // Per preset and block, the squared error of luma and of chroma, measured separately so the two pick apart.
        val errY = Array(luma.size) { LongArray(sbRows * sbCols) }
        val errUv = Array(luma.size) { LongArray(sbRows * sbCols) }
        fh.cdefDamping = damping
        fh.cdefBits = 0
        for (k in luma.indices) {
            setStrength(0, luma[k], luma[k])
            Av1Cdef.apply(d)
            blockErrors(errY[k], errUv[k], sbRows, sbCols)
            restore(saved)
        }
        // Greedy sets of 1, 2, 4 and 8 presets for luma and for chroma alike, each block taking its best.
        var best: Cdef? = null
        var bestJ = Long.MAX_VALUE
        val codedCount = coded.count { it }
        for (bits in 0..3) {
            val size = 1 shl bits
            if (size > luma.size) break
            val pickY = greedy(errY, coded, size)
            val pickUv = if (d.numPlanes > 1) greedy(errUv, coded, size) else IntArray(size)
            var total = 0L
            for (b in coded.indices) {
                if (!coded[b]) continue
                var bestBlock = Long.MAX_VALUE
                for (i in 0 until size) bestBlock = minOf(bestBlock, errY[pickY[i]][b] + errUv[pickUv[i]][b])
                total += bestBlock
            }
            val j = (total shl 8) + (lambda256 * (codedCount.toLong() * bits * 256) shr 8)
            if (j < bestJ) {
                bestJ = j
                best = Cdef(damping, bits, IntArray(8) { if (it < size) luma[pickY[it]] else 0 }, IntArray(8) { if (it < size) luma[pickUv[it]] else 0 })
            }
        }
        val chosen = best!!
        // Each block's index under the chosen set.
        val yIdx = chosen.y
        val uvIdx = chosen.uv
        val size = 1 shl chosen.bits
        for (b in coded.indices) {
            if (!coded[b]) continue
            var bi = 0
            var be = Long.MAX_VALUE
            for (i in 0 until size) {
                val e = errY[luma.indexOf(yIdx[i])][b] + errUv[luma.indexOf(uvIdx[i])][b]
                if (e < be) {
                    be = e
                    bi = i
                }
            }
            val r = (b / sbCols) * 16
            val c = (b % sbCols) * 16
            record.cdef[(r shl 16) or c] = bi
        }
        fh.cdefBits = 0
        return chosen
    }

    private fun setStrength(i: Int, y: Int, uv: Int) {
        fh.cdefYPriStrength[i] = y shr 2
        fh.cdefYSecStrength[i] = (y and 3).let { if (it == 3) 4 else it }
        fh.cdefUvPriStrength[i] = uv shr 2
        fh.cdefUvSecStrength[i] = (uv and 3).let { if (it == 3) 4 else it }
    }

    /** The squared error of each 64 by 64 block, luma into [y] and chroma into [uv]. */
    private fun blockErrors(y: LongArray, uv: LongArray, sbRows: Int, sbCols: Int) {
        y.fill(0)
        uv.fill(0)
        for (p in 0 until d.numPlanes) {
            val sx = if (p > 0) d.subX else 0
            val sy = if (p > 0) d.subY else 0
            val stride = d.planeWidth[p]
            val w = src.shownWidth(p, d)
            val h = src.shownHeight(p, d)
            val a = src.planes[p]
            val f = d.frame[p]
            val out = if (p == 0) y else uv
            for (yy in 0 until h) {
                val br = (yy shl sy) shr 6
                for (xx in 0 until w) {
                    val e = a[yy * stride + xx] - f[yy * stride + xx]
                    out[br * sbCols + ((xx shl sx) shr 6)] += (e * e).toLong()
                }
            }
        }
    }

    /** Picks [size] of the presets by [err], each added for the most it lowers the coded blocks' total. */
    private fun greedy(err: Array<LongArray>, coded: BooleanArray, size: Int): IntArray {
        val picked = IntArray(size)
        val current = LongArray(coded.size) { Long.MAX_VALUE }
        for (s in 0 until size) {
            var bestK = 0
            var bestTotal = Long.MAX_VALUE
            for (k in err.indices) {
                var total = 0L
                for (b in coded.indices) if (coded[b]) total += minOf(current[b], err[k][b])
                if (total < bestTotal) {
                    bestTotal = total
                    bestK = k
                }
            }
            picked[s] = bestK
            for (b in coded.indices) current[b] = minOf(current[b], err[bestK][b])
        }
        return picked
    }
}
