package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.codec.avif.Av1Cdfs.Companion as C

/**
 * The first pass of the AV1 encoder (#176): the partition tree, intra modes, transform sizes and types, and levels
 * of each superblock, chosen by rate and distortion.
 *
 * A candidate is priced by coding it through the frame decoder ([Av1FrameDecoder.decodeBlock] in coding mode) into
 * a counter on the tile's CDFs, frozen, and its distortion is the squared error of the reconstruction the decoder's
 * own prediction and inverse transform leave in the frame. The cost is J = D + lambda R, with lambda proportional
 * to the square of the quantizer step. Before each candidate the state a block's syntax changes outside its own
 * samples is put back: the coefficient contexts above and left of it, the decoded flags of its superblock and its
 * superblock's `cdef_idx`. What a block writes inside its own area, the next candidate overwrites.
 *
 * Modes are first ranked by the Hadamard cost of their prediction (SATD) and the price of their symbols, and only
 * the best few are coded in full: luma with DC chroma first, then the chroma modes, chroma from luma included, on
 * the chosen luma. Effort widens each step: 1 codes one luma and one chroma mode at the largest transform, square
 * partitions down to 8 by 8, and the DCT or the mode's own transform type with a dead-zone quantizer; 2 codes two of
 * each, ranks angle deltas and filter intra, tries one transform split for the best mode, the DCT and ADST families
 * when the superblock is committed, and searches the levels ([optimize]); 3 codes five luma and four chroma modes,
 * two splits, 4 by 4 blocks, horizontal and vertical partitions and every transform type of the set. Where the frame
 * allows screen content tools, a block whose luma holds few values also tries a palette.
 */
internal class Av1BlockSearch(
    private val d: Av1FrameDecoder,
    private val src: Av1Source,
    private val record: Av1Record,
    qIndex: Int,
    private val effort: Int,
) : Av1Coding() {

    private val bitDepth = d.bitDepth
    private val lossless = qIndex == 0
    private val trialSink = Av1SymbolCounter(adapt = false)
    private val commitSink = Av1SymbolCounter(adapt = true)
    private val costSink = Av1SymbolCounter(adapt = false)
    private val fwd = Av1ForwardTransform(bitDepth)
    private val dcQ = Av1Transform.dcQ(bitDepth, qIndex)
    private val acQ = Av1Transform.acQ(bitDepth, qIndex)

    /** lambda in 1/256 of squared error a bit: 0.15 times the squared AC step in the units of the 8-bit step. */
    private val lambda256: Long = maxOf(1L, acQ.toLong() * acQ * 38 / 64)
    private val sqrtLambda256: Long = isqrt(lambda256 * 256)

    private enum class Mode { SEARCH, REPLAY, COMMIT }

    private var mode = Mode.SEARCH
    private var trialPartition = 0
    private lateinit var current: Av1BlockChoice
    private val chosen = HashMap<Long, Av1BlockChoice>()
    private var nonZero = 0

    // ---- Av1Coding ---------------------------------------------------------------------

    override fun partition(r: Int, c: Int, bSize: Int): Int =
        if (mode == Mode.SEARCH) trialPartition else record.partitions[record.key(r, c, bSize)]!!

    override fun block(d: Av1FrameDecoder, r: Int, c: Int, subSize: Int): Av1BlockChoice = when (mode) {
        Mode.SEARCH -> current
        Mode.REPLAY -> chosen[record.key(r, c, subSize)]!!
        Mode.COMMIT -> chosen[record.key(r, c, subSize)]!!.copy().also { record.blocks += it }
    }

    override fun cdefIdx(r: Int, c: Int): Int = 0

    override fun lrUnit(plane: Int, unitRow: Int, unitCol: Int, wiener: IntArray, wienerAt: Int, xqd: IntArray, xqdAt: Int): Int =
        Av1FrameHeader.RESTORE_NONE

    override fun coefficients(d: Av1FrameDecoder, plane: Int, startX: Int, startY: Int, txSz: Int): Int {
        val eob = choose(plane, startX, startY, txSz)
        if (eob > 0) nonZero++
        if (mode == Mode.COMMIT) {
            val type = if (plane == 0) txType else d.computeTxType(plane, txSz, startX shr 2, startY shr 2)
            record.add(type)
            record.add(eob)
            if (eob > 0) {
                val scan = d.scanFor(txSz, type)
                for (k in 0 until eob) record.add(levels[scan[k]])
            }
        }
        return eob
    }

    // ---- tiles and superblocks ---------------------------------------------------------

    fun tile(tile: Int) {
        d.enc = this
        d.beginTile(tile)
        var r = d.miRowStart
        while (r < d.miRowEnd) {
            d.beginSuperblockRow()
            var c = d.miColStart
            while (c < d.miColEnd) {
                superblock(r, c)
                c += 16
            }
            r += 16
        }
        d.endTile(tile)
    }

    private val sbSaved = Saved()

    private fun superblock(r: Int, c: Int) {
        d.startSuperblock(r, c)
        chosen.clear()
        sbSaved.save(r, c, Av1.BLOCK_64X64)
        mode = Mode.SEARCH
        sink = trialSink
        search(r, c, Av1.BLOCK_64X64, 0)
        // The commit: the chosen tree once more, from the superblock's starting state, adapting the CDFs.
        sbSaved.restore()
        d.startSuperblock(r, c)
        mode = Mode.COMMIT
        sink = commitSink
        d.decodePartition(r, c, Av1.BLOCK_64X64)
        mode = Mode.SEARCH
        sink = trialSink
    }

    // ---- partitions --------------------------------------------------------------------

    private val saves = Array(8) { Saved() }

    /** The best partition of the block of [bSize] at ([r], [c]) and its cost, its winner left coded in the frame. */
    private fun search(r: Int, c: Int, bSize: Int, depth: Int): Long {
        if (r >= d.miRows || c >= d.miCols) return 0
        val saved = saves[depth]
        saved.save(r, c, bSize)
        val half = Av1.num4x4Wide[bSize] shr 1
        val hasRows = r + half < d.miRows
        val hasCols = c + half < d.miCols
        val options = IntArray(4)
        var n = 0
        if (bSize < Av1.BLOCK_8X8) {
            options[n++] = Av1.PARTITION_NONE
        } else if (hasRows && hasCols) {
            options[n++] = Av1.PARTITION_NONE
            if (bSize > Av1.BLOCK_8X8 || allow4x4()) options[n++] = Av1.PARTITION_SPLIT
            if (effort >= 3) {
                if (valid(Av1.partitionSubsize(Av1.PARTITION_HORZ, bSize))) options[n++] = Av1.PARTITION_HORZ
                if (valid(Av1.partitionSubsize(Av1.PARTITION_VERT, bSize))) options[n++] = Av1.PARTITION_VERT
            }
        } else if (hasCols) {
            options[n++] = Av1.PARTITION_SPLIT
            if (valid(Av1.partitionSubsize(Av1.PARTITION_HORZ, bSize))) options[n++] = Av1.PARTITION_HORZ
        } else if (hasRows) {
            options[n++] = Av1.PARTITION_SPLIT
            if (valid(Av1.partitionSubsize(Av1.PARTITION_VERT, bSize))) options[n++] = Av1.PARTITION_VERT
        } else {
            options[n++] = Av1.PARTITION_SPLIT
        }
        var bestJ = Long.MAX_VALUE
        var best = -1
        var last = -1
        var noneSkipped = false
        for (k in 0 until n) {
            val p = options[k]
            if (p != Av1.PARTITION_NONE && noneSkipped && effort < 3) continue
            saved.restore()
            var j = 0L
            if (bSize >= Av1.BLOCK_8X8) {
                trialPartition = p
                sink = costSink
                costSink.cost = 0
                d.partitionSyntax(r, c, bSize)
                sink = trialSink
                j += lambda256 * costSink.cost shr 8
            }
            val sub = Av1.partitionSubsize(p, bSize)
            when (p) {
                Av1.PARTITION_NONE -> {
                    j += block(r, c, bSize, depth + 1)
                    // A block that codes no residual at a small error seldom gains from a split.
                    noneSkipped = chosen[record.key(r, c, bSize)]!!.skip == 1
                }
                Av1.PARTITION_SPLIT -> {
                    for (q in 0 until 4) {
                        j += search(r + (q shr 1) * half, c + (q and 1) * half, sub, depth + 1)
                        if (j >= bestJ) break
                    }
                }
                Av1.PARTITION_HORZ -> {
                    j += block(r, c, sub, depth + 1)
                    if (hasRows && j < bestJ) j += block(r + half, c, sub, depth + 1)
                }
                else -> {
                    j += block(r, c, sub, depth + 1)
                    if (hasCols && j < bestJ) j += block(r, c + half, sub, depth + 1)
                }
            }
            last = p
            if (j < bestJ) {
                bestJ = j
                best = p
            }
        }
        record.partitions[record.key(r, c, bSize)] = best
        if (best != last) {
            saved.restore()
            mode = Mode.REPLAY
            d.decodePartition(r, c, bSize)
            mode = Mode.SEARCH
        }
        return bestJ
    }

    private fun allow4x4(): Boolean = effort >= 3 || (lossless && effort >= 2)

    /** Whether a block of [size] has a chroma block at this subsampling. */
    private fun valid(size: Int): Boolean = d.numPlanes == 1 || Av1.subsampledSize(size, d.subX, d.subY) != Av1.BLOCK_INVALID

    // ---- one block ---------------------------------------------------------------------

    private val lumaRank = LongArray(80)
    private val lumaCand = IntArray(80)

    /**
     * The best intra coding of the block of [bSize] at ([r], [c]) and its cost, left coded in the frame and kept in
     * [chosen].
     */
    private fun block(r: Int, c: Int, bSize: Int, depth: Int): Long {
        val saved = saves[depth]
        saved.save(r, c, bSize)
        val ch = Av1BlockChoice()
        val best = Av1BlockChoice()
        var bestJ = Long.MAX_VALUE
        var lastWasBest = false
        val maxTx = Av1Tables.maxTxSizeRect[bSize]
        val maxDepth = if (lossless) 0 else minOf(Av1Tables.maxTxDepth[bSize], if (Av1Tables.maxTxDepth[bSize] > 1) 2 else 1)
        val txDepths = minOf(maxDepth, effort - 1)

        // Luma: rank the modes by SATD, then code the best few with DC chroma.
        saved.restore()
        d.positionBlock(r, c, bSize)
        val hasChroma = d.hasChroma
        val n = rankLuma(r, c, bSize)
        val keep = minOf(n, if (lossless) 3 else if (effort <= 1) 1 else if (effort == 2) 2 else 5)
        // Each kept mode at the largest transform; then the transform splits of the best mode alone.
        var bestCode = lumaCand[0]
        for (step in 0..txDepths) {
            val modes = if (step == 0) keep else 1
            for (k in 0 until modes) {
                val code = if (step == 0) lumaCand[k] else bestCode
                decodeLuma(code, ch)
                ch.uvMode = Av1.DC_PRED
                ch.angleDeltaUV = 0
                ch.cflAlphaU = 0
                ch.cflAlphaV = 0
                ch.skip = 0
                var tx = if (lossless) Av1.TX_4X4 else maxTx
                for (i in 0 until step) tx = Av1Tables.splitTxSize[tx]
                ch.txSize = tx
                val j = trial(r, c, bSize, ch, saved)
                if (j < bestJ) {
                    bestJ = j
                    best.copyFrom(ch)
                    bestCode = code
                    lastWasBest = true
                } else {
                    lastWasBest = false
                }
                if (nonZero == 0) {
                    ch.skip = 1
                    val js = trial(r, c, bSize, ch, saved)
                    if (js < bestJ) {
                        bestJ = js
                        best.copyFrom(ch)
                        bestCode = code
                        lastWasBest = true
                    } else {
                        lastWasBest = false
                    }
                }
            }
            // A block the best mode codes with no residual gains nothing from smaller transforms.
            if (best.skip == 1) break
        }
        // A palette, where the frame allows screen content tools and the block's luma has few values.
        if (d.fh.allowScreenContentTools != 0 && bSize >= Av1.BLOCK_8X8 && Av1.blockWidth[bSize] <= 64 && Av1.blockHeight[bSize] <= 64) {
            val pal = palette(r, c, bSize, ch)
            if (pal) {
                ch.uvMode = Av1.DC_PRED
                ch.angleDeltaUV = 0
                ch.cflAlphaU = 0
                ch.cflAlphaV = 0
                ch.skip = 0
                ch.txSize = if (lossless) Av1.TX_4X4 else maxTx
                val j = trial(r, c, bSize, ch, saved)
                if (j < bestJ) {
                    bestJ = j
                    best.copyFrom(ch)
                    lastWasBest = true
                } else {
                    lastWasBest = false
                }
            }
        }
        // Chroma, on the chosen luma.
        if (hasChroma) {
            if (!lastWasBest) {
                trial(r, c, bSize, best, saved)
                lastWasBest = true
            }
            val base = best.copy()
            val ranked = rankChroma(r, c, bSize, base)
            val keepUv = minOf(ranked, if (effort <= 1) 1 else if (effort == 2) 2 else 4)
            for (k in 0 until keepUv) {
                ch.copyFrom(base)
                val code = uvCand[k]
                ch.uvMode = code and 0xFF
                ch.angleDeltaUV = ((code shr 8) and 0xFF) - 3
                ch.cflAlphaU = ((code shr 16) and 0xFF) - 16
                ch.cflAlphaV = ((code shr 24) and 0xFF) - 16
                if (ch.uvMode == base.uvMode && ch.angleDeltaUV == base.angleDeltaUV && ch.uvMode != Av1.UV_CFL_PRED) continue
                ch.skip = 0
                val j = trial(r, c, bSize, ch, saved)
                if (j < bestJ) {
                    bestJ = j
                    best.copyFrom(ch)
                    lastWasBest = true
                } else {
                    lastWasBest = false
                }
                if (nonZero == 0) {
                    ch.skip = 1
                    val js = trial(r, c, bSize, ch, saved)
                    if (js < bestJ) {
                        bestJ = js
                        best.copyFrom(ch)
                        lastWasBest = true
                    } else {
                        lastWasBest = false
                    }
                }
            }
        }
        if (!lastWasBest) trial(r, c, bSize, best, saved)
        chosen[record.key(r, c, bSize)] = best
        return bestJ
    }

    /** Codes [ch] as the block of [bSize] at ([r], [c]) into the frozen counter: its cost J. */
    private fun trial(r: Int, c: Int, bSize: Int, ch: Av1BlockChoice, saved: Saved): Long {
        saved.restore()
        current = ch
        trialSink.cost = 0
        nonZero = 0
        d.decodeBlock(r, c, bSize)
        val dist = blockError(r, c, bSize)
        return (dist shl 8) + (lambda256 * trialSink.cost shr 8)
    }

    /** The squared error of the block's reconstruction against the source, over the shown samples of each plane it codes. */
    private fun blockError(r: Int, c: Int, bSize: Int): Long {
        val bw = Av1.blockWidth[bSize]
        val bh = Av1.blockHeight[bSize]
        var sum = error(0, c * 4, r * 4, bw, bh)
        if (d.hasChroma) {
            val x0 = (c shr d.subX) * 4
            val y0 = (r shr d.subY) * 4
            val w = maxOf(4, bw shr d.subX)
            val h = maxOf(4, bh shr d.subY)
            sum += error(1, x0, y0, w, h) + error(2, x0, y0, w, h)
        }
        return sum
    }

    private fun error(plane: Int, x0: Int, y0: Int, w: Int, h: Int): Long {
        val stride = d.planeWidth[plane]
        val xe = minOf(x0 + w, src.shownWidth(plane, d))
        val ye = minOf(y0 + h, src.shownHeight(plane, d))
        val a = src.planes[plane]
        val b = d.frame[plane]
        var sum = 0L
        for (y in y0 until ye) {
            var row = 0
            val at = y * stride
            for (x in x0 until xe) {
                val e = a[at + x] - b[at + x]
                row += e * e
            }
            sum += row
        }
        return sum
    }

    // ---- palette -------------------------------------------------------------------------

    private val counts = HashMap<Int, Int>()

    /**
     * A luma palette for the block of [bSize] at ([r], [c]) into [ch], DC_PRED with its colours and map, when the
     * source over the block's coded area holds 2 to 8 values, or its 8 commonest cover 15/16 of it; each sample
     * maps to the nearest colour. False when no palette fits.
     */
    private fun palette(r: Int, c: Int, bSize: Int, ch: Av1BlockChoice): Boolean {
        val bw = Av1.blockWidth[bSize]
        val bh = Av1.blockHeight[bSize]
        val w = minOf(bw, (d.miCols - c) * 4)
        val h = minOf(bh, (d.miRows - r) * 4)
        val stride = d.planeWidth[0]
        val s = src.planes[0]
        counts.clear()
        for (y in 0 until h) for (x in 0 until w) {
            val v = s[(r * 4 + y) * stride + c * 4 + x].toInt()
            counts[v] = (counts[v] ?: 0) + 1
            if (counts.size > 64) return false
        }
        if (counts.size < 2) return false
        val top = counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(8)
        if (counts.size > 8 && top.sumOf { it.value } * 16 < w * h * 15) return false
        val colors = top.map { it.key }.sorted()
        val n = colors.size
        val map = IntArray(64 * 64)
        for (y in 0 until h) for (x in 0 until w) {
            val v = s[(r * 4 + y) * stride + c * 4 + x].toInt()
            var bi = 0
            for (k in 1 until n) if (kotlin.math.abs(colors[k] - v) < kotlin.math.abs(colors[bi] - v)) bi = k
            map[y * 64 + x] = bi
        }
        ch.yMode = Av1.DC_PRED
        ch.angleDeltaY = 0
        ch.useFilterIntra = false
        ch.filterIntraMode = 0
        ch.paletteSizeY = n
        ch.paletteSizeUV = 0
        for (k in 0 until n) ch.paletteColorsY[k] = colors[k]
        ch.colorMapY = map
        return true
    }

    // ---- luma mode ranking -------------------------------------------------------------

    /**
     * Fills [lumaCand] with luma candidates in order of their SATD cost, and returns how many. A candidate packs the
     * mode in its low byte, the angle delta plus 3 in the next, and a filter intra mode plus 1 in the third.
     */
    private fun rankLuma(r: Int, c: Int, bSize: Int): Int {
        var n = 0
        val directional = bSize >= Av1.BLOCK_8X8
        for (m in 0 until 13) {
            lumaCand[n] = m or (3 shl 8)
            lumaRank[n] = lumaSatd(r, c, bSize, lumaCand[n])
            n++
        }
        if (!lossless && effort >= 2 && d.seq.enableFilterIntra && maxOf(Av1.blockWidth[bSize], Av1.blockHeight[bSize]) <= 32) {
            for (f in 0 until 5) {
                lumaCand[n] = Av1.DC_PRED or (3 shl 8) or ((f + 1) shl 16)
                lumaRank[n] = lumaSatd(r, c, bSize, lumaCand[n])
                n++
            }
        }
        sortCandidates(lumaCand, lumaRank, n)
        if (directional && effort >= 2) {
            // Angle deltas of the best two directional modes.
            var tried = 0
            val base = n
            for (k in 0 until base) {
                val m = lumaCand[k] and 0xFF
                if (!Av1.isDirectionalMode(m) || (lumaCand[k] shr 16) != 0) continue
                for (delta in -3..3) {
                    if (delta == 0) continue
                    lumaCand[n] = m or ((delta + 3) shl 8)
                    lumaRank[n] = lumaSatd(r, c, bSize, lumaCand[n])
                    n++
                }
                if (++tried == effort - 1) break
            }
            sortCandidates(lumaCand, lumaRank, n)
        }
        return n
    }

    private fun decodeLuma(code: Int, ch: Av1BlockChoice) {
        ch.yMode = code and 0xFF
        ch.angleDeltaY = if (Av1.isDirectionalMode(ch.yMode) && d.miSize >= Av1.BLOCK_8X8) ((code shr 8) and 0xFF) - 3 else 0
        val f = (code shr 16) and 0xFF
        ch.useFilterIntra = f > 0
        ch.filterIntraMode = if (f > 0) f - 1 else 0
        ch.paletteSizeY = 0
        ch.paletteSizeUV = 0
        ch.colorMapY = null
    }

    private val probe = Av1BlockChoice()

    /** The SATD of luma candidate [code] predicted over the whole block, plus lambda times its mode symbols. */
    private fun lumaSatd(r: Int, c: Int, bSize: Int, code: Int): Long {
        decodeLuma(code, probe)
        d.yMode = probe.yMode
        d.angleDeltaY = probe.angleDeltaY
        d.useFilterIntra = probe.useFilterIntra
        d.filterIntraMode = probe.filterIntraMode
        d.paletteSizeY = 0
        val bw4 = Av1.num4x4Wide[bSize]
        val bh4 = Av1.num4x4High[bSize]
        val x = c * 4
        val y = r * 4
        val sbr = r and 15
        val sbc = c and 15
        Av1Intra.predictIntra(
            d, 0, x, y, d.availL, d.availU,
            d.blockDecodedAt(0, sbr - 1, sbc + bw4), d.blockDecodedAt(0, sbr + bh4, sbc - 1),
            probe.yMode, Av1.miWidthLog2[bSize] + 2, Av1.miHeightLog2[bSize] + 2,
        )
        val satd = satd(0, x, y, bw4 * 4, bh4 * 4)
        costSink.cost = 0
        val cdf = d.cdf
        val above = Av1Tables.intraModeContext[if (d.availU) d.yModes[d.mi(r - 1, c)] else Av1.DC_PRED]
        val left = Av1Tables.intraModeContext[if (d.availL) d.yModes[d.mi(r, c - 1)] else Av1.DC_PRED]
        costSink.symbol(probe.yMode, cdf[C.INTRA_FRAME_Y_MODE], (above * 5 + left) * 14, 13)
        if (bSize >= Av1.BLOCK_8X8 && Av1.isDirectionalMode(probe.yMode)) {
            costSink.symbol(probe.angleDeltaY + 3, cdf[C.ANGLE_DELTA], (probe.yMode - Av1.V_PRED) * 8, 7)
        }
        if (d.seq.enableFilterIntra && probe.yMode == Av1.DC_PRED && maxOf(Av1.blockWidth[bSize], Av1.blockHeight[bSize]) <= 32) {
            costSink.symbol(if (probe.useFilterIntra) 1 else 0, cdf[C.FILTER_INTRA], bSize * 3, 2)
            if (probe.useFilterIntra) costSink.symbol(probe.filterIntraMode, cdf[C.FILTER_INTRA_MODE], 0, 5)
        }
        return (satd shl 8) + (sqrtLambda256 * costSink.cost shr 8)
    }

    // ---- chroma mode ranking -----------------------------------------------------------

    private val uvRank = LongArray(40)
    private val uvCand = IntArray(40)

    /**
     * Fills [uvCand] with chroma candidates on the luma of [base], now in the frame, by SATD; returns how many. A
     * candidate packs the mode, the angle delta plus 3, and the U and V alphas plus 16 in its four bytes.
     */
    private fun rankChroma(r: Int, c: Int, bSize: Int, base: Av1BlockChoice): Int {
        d.positionBlock(r, c, bSize)
        val bw = Av1.blockWidth[bSize]
        val bh = Av1.blockHeight[bSize]
        val cflAllowed = if (lossless && Av1.subsampledSize(bSize, d.subX, d.subY) == Av1.BLOCK_4X4) true
        else !lossless && maxOf(bw, bh) <= 32
        val cw = maxOf(4, bw shr d.subX)
        val chh = maxOf(4, bh shr d.subY)
        val x0 = (c shr d.subX) * 4
        val y0 = (r shr d.subY) * 4
        val log2W = 31 - cw.countLeadingZeroBits()
        val log2H = 31 - chh.countLeadingZeroBits()
        val cdf = d.cdf
        var n = 0
        d.yMode = base.yMode
        for (m in 0 until 13) {
            d.uvMode = m
            d.angleDeltaUV = 0
            var satd = 0L
            for (plane in 1..2) {
                predictChroma(plane, x0, y0, cw, chh, m, log2W, log2H, r, c, bSize)
                satd += satd(plane, x0, y0, cw, chh)
            }
            costSink.cost = 0
            if (cflAllowed) costSink.symbol(m, cdf[C.UV_MODE_CFL_ALLOWED], base.yMode * 15, 14)
            else costSink.symbol(m, cdf[C.UV_MODE_CFL_NOT_ALLOWED], base.yMode * 14, 13)
            uvCand[n] = m or (3 shl 8) or (16 shl 16) or (16 shl 24)
            uvRank[n] = (satd shl 8) + (sqrtLambda256 * costSink.cost shr 8)
            n++
        }
        if (cflAllowed && cw <= 32 && chh <= 32) {
            // Chroma from luma: DC prediction plus alpha times the luma's AC, alpha by least squares.
            val alphas = IntArray(2)
            var satd = 0L
            for (plane in 1..2) {
                predictChroma(plane, x0, y0, cw, chh, Av1.DC_PRED, log2W, log2H, r, c, bSize)
                alphas[plane - 1] = cflAlpha(plane, x0, y0, cw, chh)
            }
            if (alphas[0] != 0 || alphas[1] != 0) {
                d.cflAlphaU = alphas[0]
                d.cflAlphaV = alphas[1]
                for (plane in 1..2) {
                    predictChroma(plane, x0, y0, cw, chh, Av1.DC_PRED, log2W, log2H, r, c, bSize)
                    Av1Intra.predictChromaFromLuma(d, plane, x0, y0, txFor(cw, chh))
                    satd += satd(plane, x0, y0, cw, chh)
                }
                costSink.cost = 0
                costSink.symbol(Av1.UV_CFL_PRED, cdf[C.UV_MODE_CFL_ALLOWED], base.yMode * 15, 14)
                costSink.cost += 6 * 256
                uvCand[n] = Av1.UV_CFL_PRED or (3 shl 8) or ((alphas[0] + 16) shl 16) or ((alphas[1] + 16) shl 24)
                uvRank[n] = (satd shl 8) + (sqrtLambda256 * costSink.cost shr 8)
                n++
            }
        }
        sortCandidates(uvCand, uvRank, n)
        if (effort >= 3 && bSize >= Av1.BLOCK_8X8) {
            val m = uvCand[0] and 0xFF
            if (Av1.isDirectionalMode(m)) {
                for (delta in -3..3) {
                    if (delta == 0) continue
                    d.uvMode = m
                    d.angleDeltaUV = delta
                    var satd = 0L
                    for (plane in 1..2) {
                        predictChroma(plane, x0, y0, cw, chh, m, log2W, log2H, r, c, bSize)
                        satd += satd(plane, x0, y0, cw, chh)
                    }
                    costSink.cost = 0
                    if (cflAllowed) costSink.symbol(m, cdf[C.UV_MODE_CFL_ALLOWED], base.yMode * 15, 14)
                    else costSink.symbol(m, cdf[C.UV_MODE_CFL_NOT_ALLOWED], base.yMode * 14, 13)
                    costSink.symbol(delta + 3, cdf[C.ANGLE_DELTA], (m - Av1.V_PRED) * 8, 7)
                    uvCand[n] = m or ((delta + 3) shl 8) or (16 shl 16) or (16 shl 24)
                    uvRank[n] = (satd shl 8) + (sqrtLambda256 * costSink.cost shr 8)
                    n++
                }
                sortCandidates(uvCand, uvRank, n)
            }
        }
        return n
    }

    private fun txFor(w: Int, h: Int): Int = Av1.findTxSize(w, h)

    private fun predictChroma(plane: Int, x0: Int, y0: Int, w: Int, h: Int, mode: Int, log2W: Int, log2H: Int, r: Int, c: Int, bSize: Int) {
        val sbr = (r and 15) shr d.subY
        val sbc = (c and 15) shr d.subX
        val stepX = w shr 2
        val stepY = h shr 2
        d.useFilterIntra = false
        Av1Intra.predictIntra(
            d, plane, x0, y0, d.availLChroma, d.availUChroma,
            d.blockDecodedAt(plane, sbr - 1, sbc + stepX), d.blockDecodedAt(plane, sbr + stepY, sbc - 1),
            mode, log2W, log2H,
        )
    }

    /** The CFL alpha, in 1/8, that best fits the source chroma of [plane] over its DC prediction now in the frame. */
    private fun cflAlpha(plane: Int, x0: Int, y0: Int, w: Int, h: Int): Int {
        val subX = d.subX
        val subY = d.subY
        val luma = d.frame[0]
        val lumaStride = d.planeWidth[0]
        val stride = d.planeWidth[plane]
        val l = IntArray(w * h)
        var sum = 0L
        for (i in 0 until h) {
            val lumaY = minOf((y0 + i) shl subY, d.maxLumaH - (1 shl subY))
            for (j in 0 until w) {
                val lumaX = minOf((x0 + j) shl subX, d.maxLumaW - (1 shl subX))
                var t = 0
                for (dy in 0..subY) for (dx in 0..subX) t += luma[(lumaY + dy) * lumaStride + lumaX + dx].toInt()
                l[i * w + j] = t shl (3 - subX - subY)
                sum += l[i * w + j]
            }
        }
        val avg = ((sum + (w * h / 2)) / (w * h)).toInt()
        var num = 0L
        var den = 0L
        val s = src.planes[plane]
        val f = d.frame[plane]
        for (i in 0 until h) for (j in 0 until w) {
            val ac = (l[i * w + j] - avg).toLong()
            val e = (s[(y0 + i) * stride + x0 + j] - f[(y0 + i) * stride + x0 + j]).toLong()
            num += e * ac
            den += ac * ac
        }
        if (den == 0L) return 0
        // pred = dc + alpha * ac / 64, so alpha = 64 * sum(e * ac) / sum(ac^2), rounded.
        val a = (2 * 64 * num + (if (num >= 0) den else -den)) / (2 * den)
        return a.toInt().coerceIn(-16, 16)
    }

    // ---- SATD ----------------------------------------------------------------------------

    private val diff = IntArray(16)

    /** The sum of absolute 4x4 Hadamard coefficients of the source minus the frame, halved, over the shown samples. */
    private fun satd(plane: Int, x0: Int, y0: Int, w: Int, h: Int): Long {
        val stride = d.planeWidth[plane]
        val sw = src.shownWidth(plane, d)
        val sh = src.shownHeight(plane, d)
        val a = src.planes[plane]
        val b = d.frame[plane]
        var total = 0L
        var y = y0
        while (y < y0 + h && y < sh) {
            var x = x0
            while (x < x0 + w && x < sw) {
                for (i in 0 until 4) for (j in 0 until 4) {
                    val yy = y + i
                    val xx = x + j
                    diff[i * 4 + j] = if (yy < sh && xx < sw) a[yy * stride + xx] - b[yy * stride + xx] else 0
                }
                total += hadamard4(diff)
                x += 4
            }
            y += 4
        }
        return total
    }

    private fun hadamard4(m: IntArray): Int {
        for (i in 0 until 4) {
            val a0 = m[i * 4] + m[i * 4 + 1]
            val a1 = m[i * 4] - m[i * 4 + 1]
            val a2 = m[i * 4 + 2] + m[i * 4 + 3]
            val a3 = m[i * 4 + 2] - m[i * 4 + 3]
            m[i * 4] = a0 + a2
            m[i * 4 + 1] = a1 + a3
            m[i * 4 + 2] = a0 - a2
            m[i * 4 + 3] = a1 - a3
        }
        var sum = 0
        for (j in 0 until 4) {
            val a0 = m[j] + m[4 + j]
            val a1 = m[j] - m[4 + j]
            val a2 = m[8 + j] + m[12 + j]
            val a3 = m[8 + j] - m[12 + j]
            sum += kotlin.math.abs(a0 + a2) + kotlin.math.abs(a1 + a3) + kotlin.math.abs(a0 - a2) + kotlin.math.abs(a1 - a3)
        }
        return (sum + 1) shr 1
    }

    // ---- levels --------------------------------------------------------------------------

    private val residual = IntArray(64 * 64)
    private val coef = IntArray(1024)
    private val trialLevels = IntArray(1024)

    /**
     * The levels of the transform block of [txSz] at ([startX], [startY]) of [plane], whose prediction is in the
     * frame: for each transform type the set and effort allow, the forward transform quantized with a dead zone,
     * priced by coding it into a counter and measured through the decoder's inverse transform, against coding no
     * residual at all. Fills [levels] and [txType]; returns the end of block.
     */
    private fun choose(plane: Int, startX: Int, startY: Int, txSz: Int): Int {
        val w = Av1.txWidth[txSz]
        val h = Av1.txHeight[txSz]
        val tw = minOf(32, w)
        val th = minOf(32, h)
        val stride = d.planeWidth[plane]
        val s = src.planes[plane]
        val f = d.frame[plane]
        for (i in 0 until h) {
            val at = (startY + i) * stride + startX
            for (j in 0 until w) residual[i * w + j] = s[at + j] - f[at + j]
        }
        val vw = (src.shownWidth(plane, d) - startX).coerceIn(0, w)
        val vh = (src.shownHeight(plane, d) - startY).coerceIn(0, h)
        val x4 = startX shr 2
        val y4 = startY shr 2
        fwd.newBlock()
        if (d.lossless) {
            fwd.forwardWht(residual, levels)
            txType = Av1.DCT_DCT
            return endOfBlock(levels, d.scanFor(txSz, Av1.DCT_DCT), 16)
        }
        // No residual: the prediction's error, and the price of all_zero.
        var zeroError = 0L
        for (i in 0 until vh) for (j in 0 until vw) {
            val e = residual[i * w + j]
            zeroError += e * e
        }
        costSink.cost = 0
        d.writeAllZero(costSink, plane, x4, y4, txSz, 1)
        var bestJ = (zeroError shl 8) + (lambda256 * costSink.cost shr 8)
        var bestEob = 0
        var bestType = Av1.DCT_DCT
        val types = candidateTypes(plane, txSz, x4, y4)
        val dqDenom = Av1Transform.dqDenom(txSz)
        val scale = Av1ForwardTransform.distortionScale(txSz).toLong()
        val n = tw * th
        for (type in types) {
            if (type < 0) break
            fwd.forward(residual, txSz, type, coef)
            for (k in 0 until n) {
                val v = coef[k]
                val q = if (k == 0) dcQ else acQ
                val a = kotlin.math.abs(v) * dqDenom
                // Rounded to nearest for the levels' search to lower; a third of a step's dead zone without it.
                val bias = if (rdoq) q / 2 else if (k == 0) q / 2 else q / 3
                val l = (a + bias) / q
                trialLevels[k] = if (v < 0) -l else l
            }
            val scan = d.scanFor(txSz, type)
            val eob = if (rdoq) optimize(plane, x4, y4, txSz, type, scan, n, dqDenom, scale) else endOfBlock(trialLevels, scan, n)
            if (eob == 0) continue
            d.planeTxType = type
            costSink.cost = 0
            d.writeAllZero(costSink, plane, x4, y4, txSz, 0)
            if (plane == 0) d.writeTxType(costSink, txSz, type)
            d.writeLevels(costSink, plane, x4, y4, txSz, trialLevels, eob)
            val rate = costSink.cost
            if (vw == w && vh == h) {
                // A block shown whole: the error in the transform domain, which keeps the energy of the residual,
                // so the prediction's error less what each coded level takes away, scaled to samples.
                var gain = 0L
                for (k in 0 until n) {
                    val l = trialLevels[k]
                    if (l == 0) continue
                    val q = if (k == 0) dcQ else acQ
                    val a = ((kotlin.math.abs(l).toLong() * q) and 0xFFFFFF) / dqDenom
                    val rec = if (l < 0) -a else a
                    gain += rec * (2L * coef[k] - rec)
                }
                val dist = maxOf(0L, zeroError - (gain * scale shr 16))
                val j = (dist shl 8) + (lambda256 * rate shr 8)
                if (j < bestJ) {
                    bestJ = j
                    bestEob = eob
                    bestType = type
                    trialLevels.copyInto(levels, 0, 0, n)
                }
                continue
            }
            // A block that crosses the picture's edge: the error over its shown samples, through the decoder's own
            // dequantization and inverse transform.
            val quant = d.quant
            for (k in 0 until n) quant[k] = trialLevels[k]
            val res = d.transform.residual(d, plane, txSz)
            val flipUD = Av1Transform.flipsRows(type)
            val flipLR = Av1Transform.flipsColumns(type)
            val max = (1 shl bitDepth) - 1
            var dist = 0L
            for (i in 0 until h) {
                val yy = if (flipUD) h - 1 - i else i
                if (yy >= vh) continue
                for (j in 0 until w) {
                    val xx = if (flipLR) w - 1 - j else j
                    if (xx >= vw) continue
                    val at = (startY + yy) * stride + startX + xx
                    val rec = (f[at] + res[i * w + j]).coerceIn(0, max.toLong()).toInt()
                    val e = s[at] - rec
                    dist += e * e
                }
            }
            val j = (dist shl 8) + (lambda256 * rate shr 8)
            if (j < bestJ) {
                bestJ = j
                bestEob = eob
                bestType = type
                trialLevels.copyInto(levels, 0, 0, n)
            }
        }
        txType = bestType
        return bestEob
    }

    /** Whether the levels go through [optimize]: from effort 2, and in lossy coding. */
    private val rdoq = effort >= 2 && !lossless

    /**
     * A rate-distortion search of the levels in [trialLevels] for the coefficients in [coef], in reverse scan order as
     * they are coded: each level, its value one lower, and two lower, priced by the symbols its context would code
     * and measured by its error in the transform domain. A level's context reads only the levels after it in scan
     * order, all settled by then, so each price is the one the stream will pay; what a level does to the contexts of
     * the levels before it is left out, as a greedy search does. Returns the end of block.
     */
    private fun optimize(plane: Int, x4: Int, y4: Int, txSz: Int, type: Int, scan: IntArray, n: Int, dqDenom: Int, scale: Long): Int {
        val eob = endOfBlock(trialLevels, scan, n)
        if (eob == 0) return 0
        d.planeTxType = type
        val quant = d.quant
        quant.fill(0, 0, n)
        val txSzCtx = (Av1Tables.txSizeSqr[txSz] + Av1Tables.txSizeSqrUp[txSz] + 1) shr 1
        val ptype = if (plane > 0) 1 else 0
        val cdf = d.cdf
        val base = cdf[C.COEFF_BASE]
        val baseEob = cdf[C.COEFF_BASE_EOB]
        val br = cdf[C.COEFF_BR]
        val brSize = minOf(txSzCtx, Av1.TX_32X32)
        val cost = Av1SymbolCounter.COST
        for (c in eob - 1 downTo 0) {
            val pos = scan[c]
            val l = trialLevels[pos]
            val a0 = kotlin.math.abs(l)
            if (a0 == 0) continue
            val v = kotlin.math.abs(coef[pos]).toLong()
            val q = if (pos == 0) dcQ else acQ
            val isEob = c == eob - 1
            val ctx = d.coeffBaseCtx(txSz, plane, x4, y4, pos, c, isEob)
            val brCtx = if (a0 > Av1.NUM_BASE_LEVELS) d.coeffBrCtx(txSz, pos) else 0
            var bestA = a0
            var bestJ = Long.MAX_VALUE
            val lowest = maxOf(if (isEob) 1 else 0, a0 - 2)
            for (a in a0 downTo lowest) {
                val rec = ((a.toLong() * q) and 0xFFFFFF) / dqDenom
                val err = v - rec
                var rate = if (a > 0) 256 else 0
                rate += if (isEob) {
                    val o = ((txSzCtx * 2 + ptype) * 4 + ctx - Av1.SIG_COEF_CONTEXTS + Av1.SIG_COEF_CONTEXTS_EOB) * 4
                    val s = minOf(a, 3) - 1
                    cost[baseEob[o + s] - (if (s > 0) baseEob[o + s - 1] else 0)]
                } else {
                    val o = ((txSzCtx * 2 + ptype) * 42 + ctx) * 5
                    val s = minOf(a, 3)
                    cost[maxOf(1, base[o + s] - (if (s > 0) base[o + s - 1] else 0))]
                }
                if (a > Av1.NUM_BASE_LEVELS) {
                    val o = ((brSize * 2 + ptype) * 21 + brCtx) * 5
                    var left = a - Av1.NUM_BASE_LEVELS - 1
                    for (idx in 0 until 4) {
                        val b = minOf(left, 3)
                        rate += cost[maxOf(1, br[o + b] - (if (b > 0) br[o + b - 1] else 0))]
                        left -= b
                        if (b < 3) break
                    }
                    if (a > 14) rate += (2 * (32 - (a - 14).countLeadingZeroBits()) - 1) * 256
                }
                val j = ((err * err * scale) shr 8) + (lambda256 * rate shr 8)
                if (j < bestJ) {
                    bestJ = j
                    bestA = a
                }
            }
            trialLevels[pos] = if (l < 0) -bestA else bestA
            quant[pos] = minOf(bestA, Av1.NUM_BASE_LEVELS + Av1.COEFF_BASE_RANGE + 1)
        }
        return endOfBlock(trialLevels, scan, n)
    }

    private val typeList = IntArray(17)

    /** The transform types to try for [plane] and [txSz], -1 ended: luma by the set and effort, chroma the one it derives. */
    private fun candidateTypes(plane: Int, txSz: Int, x4: Int, y4: Int): IntArray {
        var n = 0
        if (plane > 0) {
            typeList[n++] = d.computeTxType(plane, txSz, x4, y4)
        } else {
            val set = d.txSet(txSz)
            if (set == Av1.TX_SET_DCTONLY) {
                typeList[n++] = Av1.DCT_DCT
            } else {
                val inSet = if (set == Av1.TX_SET_INTRA_1) Av1Tables.txTypeIntraInvSet1 else Av1Tables.txTypeIntraInvSet2
                val implied = Av1Tables.modeToTxfm[if (d.useFilterIntra) Av1Tables.filterIntraModeToIntraDir[d.filterIntraMode] else d.yMode]
                typeList[n++] = Av1.DCT_DCT
                if (effort <= 1 || (effort == 2 && mode != Mode.COMMIT)) {
                    if (implied != Av1.DCT_DCT && implied in inSet) typeList[n++] = implied
                } else {
                    for (t in inSet) {
                        if (t == Av1.DCT_DCT) continue
                        if (effort == 2 && (t == Av1.V_DCT || t == Av1.H_DCT)) continue
                        typeList[n++] = t
                    }
                }
            }
        }
        typeList[n] = -1
        return typeList
    }

    /** One more than the last scan position of [lv] that is not zero, among the first [n]. */
    private fun endOfBlock(lv: IntArray, scan: IntArray, n: Int): Int {
        for (k in n - 1 downTo 0) if (lv[scan[k]] != 0) return k + 1
        return 0
    }

    // ---- what a candidate changes outside its own area -----------------------------------

    /** The coefficient contexts beside a block, its superblock's decoded flags and `cdef_idx`, kept and put back. */
    private inner class Saved {
        private val above = Array(3) { IntArray(2 * 40) }
        private val left = Array(3) { IntArray(2 * 40) }
        private val aboveAt = IntArray(3)
        private val leftAt = IntArray(3)
        private val aboveN = IntArray(3)
        private val leftN = IntArray(3)
        private val decoded = Array(3) { BooleanArray(34 * 34) }
        private var cdefAt = 0
        private var cdef = 0

        fun save(r: Int, c: Int, bSize: Int) {
            for (p in 0 until d.numPlanes) {
                val sx = if (p > 0) d.subX else 0
                val sy = if (p > 0) d.subY else 0
                val a0 = maxOf(0, (c shr sx) - 1)
                val a1 = minOf(d.aboveLevelContext[p].size, ((c + Av1.num4x4Wide[bSize]) shr sx) + 1)
                aboveAt[p] = a0
                aboveN[p] = a1 - a0
                for (i in 0 until a1 - a0) {
                    above[p][i] = d.aboveLevelContext[p][a0 + i]
                    above[p][40 + i] = d.aboveDcContext[p][a0 + i]
                }
                val l0 = maxOf(0, (r shr sy) - 1)
                val l1 = minOf(d.leftLevelContext[p].size, ((r + Av1.num4x4High[bSize]) shr sy) + 1)
                leftAt[p] = l0
                leftN[p] = l1 - l0
                for (i in 0 until l1 - l0) {
                    left[p][i] = d.leftLevelContext[p][l0 + i]
                    left[p][40 + i] = d.leftDcContext[p][l0 + i]
                }
                d.blockDecoded[p].copyInto(decoded[p])
            }
            cdefAt = d.mi(r and 15.inv(), c and 15.inv())
            cdef = d.cdefIdx[cdefAt]
        }

        fun restore() {
            for (p in 0 until d.numPlanes) {
                for (i in 0 until aboveN[p]) {
                    d.aboveLevelContext[p][aboveAt[p] + i] = above[p][i]
                    d.aboveDcContext[p][aboveAt[p] + i] = above[p][40 + i]
                }
                for (i in 0 until leftN[p]) {
                    d.leftLevelContext[p][leftAt[p] + i] = left[p][i]
                    d.leftDcContext[p][leftAt[p] + i] = left[p][40 + i]
                }
                decoded[p].copyInto(d.blockDecoded[p])
            }
            d.cdefIdx[cdefAt] = cdef
        }
    }

    companion object {
        /** The floor of the square root of [x], by Newton's method in integers. */
        fun isqrt(x: Long): Long {
            if (x <= 0) return 0
            var r = x
            var y = (r + 1) / 2
            while (y < r) {
                r = y
                y = (r + x / r) / 2
            }
            return r
        }

        /** Sorts the first [n] of [cand] by [rank], least first; ties keep their order, so the result is the same everywhere. */
        fun sortCandidates(cand: IntArray, rank: LongArray, n: Int) {
            for (i in 1 until n) {
                val c = cand[i]
                val k = rank[i]
                var j = i - 1
                while (j >= 0 && rank[j] > k) {
                    cand[j + 1] = cand[j]
                    rank[j + 1] = rank[j]
                    j--
                }
                cand[j + 1] = c
                rank[j + 1] = k
            }
        }
    }
}
