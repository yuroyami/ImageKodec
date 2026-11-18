package io.github.yuroyami.imagekodec.codec.avif

/**
 * The second pass of the AV1 encoder (#176): the loop filter levels, the CDEF strengths and the loop restoration
 * units, chosen on the first pass's reconstruction with the decoder's own filters ([Av1LoopFilter], [Av1Cdef],
 * [Av1LoopRestoration]), each candidate measured by its squared error against the source. The frame is filtered
 * in place and put back after each candidate.
 *
 * Deblocking tries luma levels around a guess from the quantizer and keeps the best, then chroma the same way.
 * CDEF filters the deblocked frame once for each of a set of strength pairs, keeps each 64 by 64 block's squared
 * error under each, and picks the 1, 2, 4 or 8 pairs, and each block's choice among them, that cost least with
 * `cdef_idx` priced at its bits. Loop restoration then weighs the self-guided and Wiener filters on the CDEF output
 * ([restoration]).
 */
internal class Av1FilterSearch(
    private val d: Av1FrameDecoder,
    private val src: Av1Source,
    private val record: Av1Record,
    private val effort: Int,
    /** Whether the Wiener filter may be chosen; tests turn it off to measure what it brings. */
    private val wiener: Boolean = true,
    /** Whether the self-guided filter may be chosen; tests turn it off to make the Wiener filter the only one. */
    private val selfGuided: Boolean = true,
    /** Whether every restoration unit takes a filter whatever it costs, so tests see each filter in every unit. */
    private val forceRestoration: Boolean = false,
) {
    /** A restoration unit's choice: its type, and its self-guided set and weights, or for Wiener its unit's index. */
    private class Unit(val type: Int, val set: Int, val w0: Int, val w1: Int)

    /** Where entry (i, j) of the symmetric 3 by 3 matrix of the Wiener normal equations is kept. */
    private val SYM = intArrayOf(0, 1, 2, 1, 3, 4, 2, 4, 5)
    private val NONE_UNIT = Unit(Av1FrameHeader.RESTORE_NONE, 0, 0, 0)

    private val fh = d.fh
    private val lambda256: Long = run {
        val q = Av1Transform.acQ(d.bitDepth, fh.baseQIdx).toLong()
        maxOf(1L, q * q * 38 / 64)
    }

    /** The restoration units chosen by [search], for the writing pass. */
    var lr: Av1LrChoice? = null
        private set

    fun search(base: Av1FrameParams, stream: Av1StreamParams): Av1FrameParams {
        // Under intra block copy the frame has no loop filters.
        if (fh.codedLossless || fh.allowIntrabc) return base
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

    /** The self-guided sets tried: none at effort 1, two at 2 (four gained nothing measurable), all sixteen at 3. */
    private fun sgrSets(): IntArray = if (!selfGuided) IntArray(0) else when (effort) {
        1 -> IntArray(0)
        2 -> intArrayOf(0, 4)
        else -> IntArray(16) { it }
    }

    /** How many least-squares passes fit the Wiener taps, vertical and horizontal in turn from the identity. */
    private fun wienerPasses(): Int = if (!wiener) 0 else when (effort) {
        1 -> 0
        2 -> 2
        else -> 4
    }

    /**
     * The restoration of each unit of each plane: none, the self-guided filter or the Wiener filter, and the frame's
     * restoration type for the plane, chosen by rate and distortion. Returns lr_type as coded for each plane (0 none,
     * 1 switchable, 2 Wiener, 3 self-guided).
     *
     * Each unit gets its best self-guided set and projection, fitted by least squares ([fit]) and measured by the
     * error the projection leaves, and its Wiener taps, fitted by least squares one pass at a time with the other
     * held ([fitWiener]) and measured by filtering the unit with the decoder's own filter. Then each frame type is
     * priced in coding order, where each unit takes its cheapest choice and the taps or weights it codes become the
     * reference the next one is coded against, as `decode_signed_subexp_with_ref_bool` reads them.
     */
    private fun restoration(saved: Array<HashMap<Int, ShortArray>>, unitSize: Int): IntArray {
        val coded = IntArray(3)
        val sets = sgrSets()
        val passes = wienerPasses()
        if (sets.isEmpty() && passes == 0) return coded
        val units = arrayOfNulls<Array<Unit>>(3)
        val cdf = d.cdf
        val useWiener = IntArray(2) { symbolCost(it, cdf[Av1Cdfs.USE_WIENER]) }
        val useSgr = IntArray(2) { symbolCost(it, cdf[Av1Cdfs.USE_SGRPROJ]) }
        val switchable = IntArray(3) { symbolCost(it, cdf[Av1Cdfs.RESTORATION_TYPE]) }
        for (p in 0 until d.numPlanes) {
            val sx = if (p > 0) d.subX else 0
            val sy = if (p > 0) d.subY else 0
            val rows = maxOf((Av1.round2(fh.frameHeight, sy) + (unitSize shr 1)) / unitSize, 1)
            val cols = maxOf((Av1.round2(fh.upscaledWidth, sx) + (unitSize shr 1)) / unitSize, 1)
            val n = rows * cols
            val none = LongArray(n)
            // The self-guided filter: each unit's best set and weights, and the error they leave.
            val sgr = Array(n) { NONE_UNIT }
            val sgrErr = LongArray(n) { Long.MAX_VALUE }
            val stats = LongArray(n * 7)
            for (set in sets) {
                stats.fill(0)
                Av1LoopRestoration.sgrStatistics(d, p, set, unitSize, rows, cols, d.frame[p], d.planeWidth[p], saved[p], src.planes[p], stats)
                val r0 = Av1Tables.sgrParams[set * 4]
                val r1 = Av1Tables.sgrParams[set * 4 + 2]
                for (u in 0 until n) {
                    val at = u * 7
                    none[u] = stats[at + 6]
                    val (w0, w1) = fit(stats, at, r0, r1)
                    // The squared error the projection leaves: sum of (e - w0 a - w1 b)^2, scaled to samples.
                    val aa = stats[at]
                    val ab = stats[at + 1]
                    val bb = stats[at + 2]
                    val ae = stats[at + 3]
                    val be = stats[at + 4]
                    val ee = stats[at + 5]
                    val err = ee - 2 * w0 * ae - 2 * w1 * be + w0.toLong() * w0 * aa + 2L * w0 * w1 * ab + w1.toLong() * w1 * bb
                    val dist = maxOf(0L, err shr (22 - 2 * (d.bitDepth - 8)))
                    if (dist < sgrErr[u]) {
                        sgrErr[u] = dist
                        sgr[u] = Unit(Av1FrameHeader.RESTORE_SGRPROJ, set, w0, w1)
                    }
                }
            }
            // The projection's error leaves out the filter's rounding and clipping, so each unit's best set is
            // measured again through the decoder's own filter.
            if (sets.isNotEmpty()) {
                val chosenSets = IntArray(n) { sgr[it].set.takeIf { _ -> sgr[it].type == Av1FrameHeader.RESTORE_SGRPROJ } ?: -1 }
                val weights = IntArray(n * 2) { if (it % 2 == 0) sgr[it / 2].w0 else sgr[it / 2].w1 }
                val exact = LongArray(n)
                Av1LoopRestoration.sgrErrors(d, p, unitSize, rows, cols, d.frame[p], d.planeWidth[p], saved[p], src.planes[p], chosenSets, weights, exact)
                for (u in 0 until n) {
                    if (chosenSets[u] >= 0) sgrErr[u] = exact[u]
                }
            }
            // The Wiener filter: taps by least squares, then each unit filtered with them.
            val taps = IntArray(n * 6)
            val wienerErr = LongArray(n) { Long.MAX_VALUE }
            if (passes > 0) {
                val equations = LongArray(n * 9)
                for (pass in 0 until passes) {
                    val vertical = pass % 2 == 0
                    equations.fill(0)
                    Av1LoopRestoration.wienerStatistics(
                        d, p, vertical, unitSize, rows, cols, d.frame[p], d.planeWidth[p], saved[p], src.planes[p], taps, equations,
                    )
                    for (u in 0 until n) fitWiener(equations, u * 9, p > 0, taps, u * 6 + (if (vertical) 0 else 3))
                }
                val errors = LongArray(n * 2)
                Av1LoopRestoration.wienerErrors(d, p, unitSize, rows, cols, d.frame[p], d.planeWidth[p], saved[p], src.planes[p], taps, errors)
                for (u in 0 until n) {
                    wienerErr[u] = errors[u * 2]
                    none[u] = errors[u * 2 + 1]
                }
            }
            // Each frame type priced in coding order; the cheapest is kept with its units.
            var bestJ = if (forceRestoration) Long.MAX_VALUE else 0L
            var bestType = Av1FrameHeader.RESTORE_NONE
            var bestUnits = Array(n) { NONE_UNIT }
            if (!forceRestoration) for (u in 0 until n) bestJ += none[u] shl 8
            val candidates = ArrayList<Int>()
            if (sets.isNotEmpty()) candidates += Av1FrameHeader.RESTORE_SGRPROJ
            if (passes > 0) candidates += Av1FrameHeader.RESTORE_WIENER
            if (sets.isNotEmpty() && passes > 0) {
                // Forced with both filters, only the switchable type, which the tests need to see.
                if (forceRestoration) candidates.clear()
                candidates += Av1FrameHeader.RESTORE_SWITCHABLE
            }
            for (type in candidates) {
                val refW = Array(2) { Av1Tables.wienerTapsMid.copyOf() }
                val refX = Av1Tables.sgrprojXqdMid.copyOf()
                val chosen = Array(n) { NONE_UNIT }
                var total = 0L
                for (u in 0 until n) {
                    val noneBits = when (type) {
                        Av1FrameHeader.RESTORE_WIENER -> useWiener[0]
                        Av1FrameHeader.RESTORE_SGRPROJ -> useSgr[0]
                        else -> switchable[Av1FrameHeader.RESTORE_NONE]
                    }
                    var best = if (forceRestoration) Long.MAX_VALUE else cost(none[u], noneBits)
                    var pick = NONE_UNIT
                    if (type != Av1FrameHeader.RESTORE_SGRPROJ && wienerErr[u] != Long.MAX_VALUE) {
                        val flag = if (type == Av1FrameHeader.RESTORE_WIENER) useWiener[1] else switchable[Av1FrameHeader.RESTORE_WIENER]
                        val jw = cost(wienerErr[u], flag + wienerBits(taps, u * 6, refW, p > 0))
                        if (jw < best) {
                            best = jw
                            pick = Unit(Av1FrameHeader.RESTORE_WIENER, u, 0, 0)
                        }
                    }
                    if (type != Av1FrameHeader.RESTORE_WIENER && sgr[u].type == Av1FrameHeader.RESTORE_SGRPROJ) {
                        val flag = if (type == Av1FrameHeader.RESTORE_SGRPROJ) useSgr[1] else switchable[Av1FrameHeader.RESTORE_SGRPROJ]
                        val js = cost(sgrErr[u], flag + sgrBits(sgr[u], refX))
                        if (js < best) {
                            best = js
                            pick = sgr[u]
                        }
                    }
                    when (pick.type) {
                        Av1FrameHeader.RESTORE_WIENER -> for (pass in 0 until 2) for (k in 0 until 3) refW[pass][k] = taps[u * 6 + pass * 3 + k]
                        Av1FrameHeader.RESTORE_SGRPROJ -> {
                            refX[0] = pick.w0
                            refX[1] = pick.w1
                        }
                    }
                    chosen[u] = pick
                    total = if (best == Long.MAX_VALUE) Long.MAX_VALUE - 1 else minOf(Long.MAX_VALUE - 1, total + best)
                }
                if (total < bestJ) {
                    bestJ = total
                    bestType = type
                    bestUnits = chosen
                }
            }
            if (bestType != Av1FrameHeader.RESTORE_NONE) {
                units[p] = bestUnits
                planeTaps[p] = taps
                coded[p] = when (bestType) {
                    Av1FrameHeader.RESTORE_SWITCHABLE -> 1
                    Av1FrameHeader.RESTORE_WIENER -> 2
                    else -> 3
                }
            }
        }
        lr = object : Av1LrChoice {
            override fun unit(coding: Av1Coding, plane: Int, unitRow: Int, unitCol: Int, wiener: IntArray, wienerAt: Int, xqd: IntArray, xqdAt: Int): Int {
                val all = units[plane] ?: return Av1FrameHeader.RESTORE_NONE
                val cols = maxOf((Av1.round2(fh.upscaledWidth, if (plane > 0) d.subX else 0) + (unitSize shr 1)) / unitSize, 1)
                val u = all[unitRow * cols + unitCol]
                when (u.type) {
                    Av1FrameHeader.RESTORE_SGRPROJ -> {
                        coding.lrSgrSet = u.set
                        xqd[xqdAt] = u.w0
                        xqd[xqdAt + 1] = u.w1
                    }
                    // A Wiener unit keeps its index in set; its taps are the plane's.
                    Av1FrameHeader.RESTORE_WIENER -> planeTaps[plane]!!.copyInto(wiener, wienerAt, u.set * 6, u.set * 6 + 6)
                }
                return u.type
            }
        }
        unitTypes = Array(3) { p -> units[p]?.let { a -> IntArray(a.size) { a[it].type } } }
        return coded
    }

    private fun cost(dist: Long, bits256: Int): Long = (dist shl 8) + (lambda256 * bits256 shr 8)

    private fun symbolCost(v: Int, c: IntArray): Int = Av1SymbolCounter.COST[maxOf(1, c[v] - (if (v > 0) c[v - 1] else 0))]

    private val planeTaps = arrayOfNulls<IntArray>(3)

    /** The restoration type of each unit of each plane that [search] chose, null for a plane with none: for tests. */
    var unitTypes: Array<IntArray?> = arrayOfNulls(3)
        private set

    /** The bits, in 1/256, of the Wiener taps at [at] of [taps] coded against [ref], as `read_lr_unit` codes them. */
    private fun wienerBits(taps: IntArray, at: Int, ref: Array<IntArray>, chroma: Boolean): Int {
        var bits = 0
        for (pass in 0 until 2) for (k in (if (chroma) 1 else 0) until 3) {
            bits += subexpBits(Av1Tables.wienerTapsMin[k], Av1Tables.wienerTapsMax[k] + 1, Av1Tables.wienerTapsK[k], ref[pass][k], taps[at + pass * 3 + k])
        }
        return bits shl 8
    }

    /** The bits, in 1/256, of the self-guided set and weights of [u] coded against [ref]. */
    private fun sgrBits(u: Unit, ref: IntArray): Int {
        var bits = 4
        for (i in 0 until 2) {
            if (Av1Tables.sgrParams[u.set * 4 + i * 2] == 0) continue
            bits += subexpBits(Av1Tables.sgrprojXqdMin[i], Av1Tables.sgrprojXqdMax[i] + 1, 4, ref[i], if (i == 0) u.w0 else u.w1)
        }
        return bits shl 8
    }

    /**
     * The bits of [v] in [low] until [high] coded about the reference [r] as `decode_signed_subexp_with_ref_bool`
     * reads it: the recentred value's subexponential code, its flag bits and literals, and its last `NS` in full.
     */
    private fun subexpBits(low: Int, high: Int, k: Int, r: Int, v: Int): Int {
        val mx = high - low
        val x = v - low
        val rr = r - low
        val s = if ((rr shl 1) <= mx) recenter(rr, x) else recenter(mx - 1 - rr, mx - 1 - x)
        var i = 0
        var mk = 0
        var bits = 0
        while (true) {
            val b2 = if (i != 0) k + i - 1 else k
            val a = 1 shl b2
            if (mx <= mk + 3 * a) {
                val n = mx - mk
                val w = 32 - n.countLeadingZeroBits()
                return bits + (if (s - mk < (1 shl w) - n) w - 1 else w)
            }
            bits++
            if (s >= mk + a) {
                i++
                mk += a
            } else {
                return bits + b2
            }
        }
    }

    private fun recenter(r: Int, v: Int): Int = when {
        v > 2 * r -> v
        v >= r -> (v - r) shl 1
        else -> ((r - v) shl 1) - 1
    }

    /**
     * The Wiener taps of one pass that fit the normal equations at [at] of [eq] best, into [taps] at [out]: three
     * taps, or for chroma, whose outer tap is 0, two, by least squares held to the ranges the syntax codes. A tap the
     * unconstrained solution puts out of its range is fixed at the end it passed, and the others solved again
     * with it fixed, which is the constrained optimum when one bound is active and close to it otherwise. A
     * singular system leaves the taps as they were.
     */
    private fun fitWiener(eq: LongArray, at: Int, chroma: Boolean, taps: IntArray, out: Int) {
        val m = Array(3) { i -> LongArray(3) { j -> eq[at + SYM[i * 3 + j]] } }
        // A ridge on the diagonal: the three second differences are close to one another on smooth content, so
        // the system is near singular there, and the plain solution swings between the ends of the taps' ranges.
        val ridge = (m[0][0] + m[1][1] + m[2][2]) shr 8
        for (k in 0 until 3) m[k][k] += ridge
        val b = LongArray(3) { eq[at + 6 + it] }
        val a = IntArray(3)
        val free = BooleanArray(3) { !(chroma && it == 0) }
        for (round in 0 until 3) {
            val idx = (0 until 3).filter { free[it] }
            if (idx.isEmpty()) break
            // The right side less what the fixed taps contribute.
            val rhs = LongArray(idx.size) { r ->
                var v = b[idx[r]]
                for (k in 0 until 3) if (!free[k]) v -= m[idx[r]][k] * a[k]
                v
            }
            val sol = solve(Array(idx.size) { r -> LongArray(idx.size) { c -> m[idx[r]][idx[c]] } }, rhs) ?: return
            var clamped = false
            for (r in idx.indices) {
                val k = idx[r]
                val lo = Av1Tables.wienerTapsMin[k]
                val hi = Av1Tables.wienerTapsMax[k]
                a[k] = sol[r].coerceIn(lo, hi)
                if (sol[r] != a[k]) {
                    free[k] = false
                    clamped = true
                }
            }
            if (!clamped) break
        }
        for (k in 0 until 3) taps[out + k] = a[k]
    }

    /**
     * The solution of the symmetric system [m] x = [rhs] of one to three unknowns, rounded, by Cramer's rule, or null
     * when it is singular. The matrix and the right side are scaled to 19 bits each, apart so that neither loses the
     * other's precision, so the products of three entries stay in a Long.
     */
    private fun solve(m: Array<LongArray>, rhs: LongArray): IntArray? {
        val n = rhs.size
        var top = 0L
        for (row in m) for (v in row) top = maxOf(top, kotlin.math.abs(v))
        var topB = 0L
        for (v in rhs) topB = maxOf(topB, kotlin.math.abs(v))
        val sa = shiftTo(top, 19)
        val sb = shiftTo(topB, 19)
        val a = Array(n) { r -> LongArray(n) { c -> m[r][c] shr sa } }
        val y = LongArray(n) { rhs[it] shr sb }
        fun det(x: Array<LongArray>): Long = when (n) {
            1 -> x[0][0]
            2 -> x[0][0] * x[1][1] - x[0][1] * x[1][0]
            else -> x[0][0] * (x[1][1] * x[2][2] - x[1][2] * x[2][1]) - x[0][1] * (x[1][0] * x[2][2] - x[1][2] * x[2][0]) +
                x[0][2] * (x[1][0] * x[2][1] - x[1][1] * x[2][0])
        }
        val d = det(a)
        if (d <= 0L) return null
        // Each unknown is det_k / det times 2^(sb - sa), where det_k has column k replaced by the right side.
        val e = sb - sa
        return IntArray(n) { k ->
            val num = det(Array(n) { r -> LongArray(n) { c -> if (c == k) y[r] else a[r][c] } })
            if (e >= 0) {
                val dd = d shr e
                if (dd == 0L) 0 else divRound(num, dd).coerceIn(-1000L, 1000L).toInt()
            } else {
                divRound(num shr -e, d).coerceIn(-1000L, 1000L).toInt()
            }
        }
    }

    /** The shift that brings [top] below 2^[bits]. */
    private fun shiftTo(top: Long, bits: Int): Int {
        var t = top
        var s = 0
        while (t >= (1L shl bits)) {
            t = t shr 1
            s++
        }
        return s
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
     * neighbours a step away, moving to the best and halving the step, down to a step of 4 at effort 2 and of 1 at 3.
     * Effort 1 keeps the guess.
     */
    private fun deblock(): IntArray {
        val saved = copyFrame()
        val levels = IntArray(4)
        val guess = (fh.baseQIdx * 3 / 32).coerceIn(0, 63)
        val minStep = if (effort >= 3) 1 else 4
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

    /**
     * The strength pairs CDEF tries, primary and secondary, each a header code (primary * 4 + secondary code): the
     * first 2 at effort 1, 4 at effort 2 (8 gained nothing measurable on the photo scenes) and all at 3.
     */
    private fun presets(): List<Pair<Int, Int>> {
        val all = listOf(
            0 to 0, 1 to 0, 2 to 1, 3 to 1, 4 to 2, 6 to 2, 8 to 3, 10 to 3, 12 to 3, 15 to 3,
            2 to 0, 4 to 0, 6 to 1, 1 to 1, 0 to 1, 0 to 2,
        )
        val n = when (effort) {
            1 -> 2
            2 -> 4
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
            // A superblock's width of one row in an Int (at most 64 squares of 13 bits), then into its Long.
            val span = 64 shr sx
            for (yy in 0 until h) {
                val br = (yy shl sy) shr 6
                var x0 = 0
                while (x0 < w) {
                    val x1 = minOf(w, x0 + span)
                    var row = 0
                    for (xx in x0 until x1) {
                        val e = a[yy * stride + xx] - f[yy * stride + xx]
                        row += e * e
                    }
                    out[br * sbCols + ((x0 shl sx) shr 6)] += row
                    x0 = x1
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
