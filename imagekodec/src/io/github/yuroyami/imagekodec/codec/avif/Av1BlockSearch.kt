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
 * partitions down to 8 by 8, and the DCT or the mode's own transform type with a dead-zone quantizer; 2 also tries
 * one transform split for the best mode, 4 by 4 blocks, searches the levels ([optimize]), and weighs the DCT, the
 * identity and the mode's own type; 3 codes five luma and four chroma modes, ranks angle deltas and filter intra up
 * to 32 by 32, tries two splits, horizontal and vertical partitions and codes every type of the set. Where the
 * frame allows screen content tools, a block whose colours are few also tries a palette.
 *
 * Efforts 1 and 2 take cheaper estimates first (#176): they rank only the three directional modes whose lines the
 * block's source gradients follow most ([strongestDirections]) and, for chroma, those and the luma's mode; they code
 * only the transform type whose coefficients have the least sum of magnitudes, the one that gathers the residual
 * into fewest; and they do not split a block that its whole codes at the largest transform when its source varies
 * by less than lambda a sample. Measured on the photo scenes as the size at equal SSIMULACRA 2 score over
 * qualities 30 to 90, each cost under a percent and the four together 0.8 percent at effort 1 and nothing at effort
 * 2, for a quarter and a fifth of the time.
 *
 * Effort 2 was set by measuring, on the photo scenes of the tests, what each step of effort 3 brought against its
 * time, as the size at equal SSIMULACRA 2 score (#176). Kept: 4 by 4 blocks (4.6 percent smaller for 9 percent more
 * time), the identity and the mode's own type while searching and every type at the commit (together about 3.5
 * percent for 6 percent), the level search (5 percent) and the transform split (3 percent). Left to effort 3: a second
 * luma and chroma mode, angle deltas, rectangular partitions and filter intra, each under a percent for its time or,
 * for the partitions and five modes, 6 to 8 percent for twice the time. Filter intra up to 16 by 16 and every type
 * of the set at the commit were in effort 2 until the cheaper estimates came, and then gained nothing measurable.
 */
internal class Av1BlockSearch(
    private val d: Av1FrameDecoder,
    private val src: Av1Source,
    private val record: Av1Record,
    qIndex: Int,
    private val effort: Int,
    /** The source's repeated windows, when the frame allows intra block copy. */
    private val hash: Av1BlockHash? = null,
    /** Whether a block may code a chroma palette. */
    private val chromaPalettes: Boolean = true,
    /** Whether a block of an inter frame that allows it may average two references (#177). */
    private val compound: Boolean = true,
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
        // The last superblock's commit adapted the CDFs that every kept result was priced on.
        forgetMemo()
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
                    // A block that codes no residual at a small error seldom gains from a split, nor at efforts 1 and
                    // 2 a block up to 32 by 32 that keeps the largest transform and whose source varies by less than
                    // lambda a sample (#176).
                    val whole = chosen[record.key(r, c, bSize)]!!
                    noneSkipped = whole.skip == 1 || (effort <= 2 && bSize <= Av1.BLOCK_32X32 && smooth(r, c, bSize, whole))
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

    private fun allow4x4(): Boolean = effort >= 2

    /**
     * Whether the block of [bSize] at ([r], [c]), coded whole as [whole], is not worth splitting: [whole] keeps the
     * largest transform, and the source luma's squared deviation from its mean, over the part inside the picture, is
     * under lambda a sample, so a split's own residual could save little.
     */
    private fun smooth(r: Int, c: Int, bSize: Int, whole: Av1BlockChoice): Boolean {
        if (whole.txSize != Av1Tables.maxTxSizeRect[bSize]) return false
        val x0 = c * 4
        val y0 = r * 4
        val w = minOf(Av1.blockWidth[bSize], src.width - x0)
        val h = minOf(Av1.blockHeight[bSize], src.height - y0)
        if (w <= 0 || h <= 0) return false
        val s = src.planes[0]
        val stride = d.planeWidth[0]
        var sum = 0L
        var squares = 0L
        for (y in y0 until y0 + h) {
            // A row of at most 32 samples of 12 bits, and their squares, fit an Int.
            var rowSum = 0
            var rowSquares = 0
            val at = y * stride
            for (x in x0 until x0 + w) {
                val v = s[at + x].toInt()
                rowSum += v
                rowSquares += v * v
            }
            sum += rowSum
            squares += rowSquares
        }
        val deviation = squares - sum * sum / (w * h)
        // deviation / (w h) < lambda, which is lambda256 / 256.
        return deviation * 256 < lambda256 * w * h
    }

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

        // In an inter frame, the inter candidates first; intra modes then compete with the best of them, except at
        // effort 1 when it codes no residual.
        if (interFrame) {
            bestJ = interSearch(r, c, bSize, saved, ch, best)
            if (effort <= 1 && best.skip == 1) {
                trial(r, c, bSize, best, saved)
                chosen[record.key(r, c, bSize)] = best
                return bestJ
            }
            ch.copyFrom(blank)
        }

        // Luma: rank the modes by SATD, then code the best few with DC chroma.
        saved.restore()
        d.positionBlock(r, c, bSize)
        val hasChroma = d.hasChroma
        val n = rankLuma(r, c, bSize)
        val keep = minOf(n, if (lossless) 3 else if (effort <= 2) 1 else 5)
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
        if (hasChroma && !best.isInter) {
            if (!lastWasBest) {
                trial(r, c, bSize, best, saved)
                lastWasBest = true
            }
            val base = best.copy()
            val ranked = rankChroma(r, c, bSize, base)
            val keepUv = minOf(ranked, if (effort <= 2) 1 else 4)
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
        // A chroma palette on the chosen luma, where the frame allows screen content tools and the block's chroma
        // has few colours.
        if (chromaPalettes && hasChroma && !best.isInter && d.fh.allowScreenContentTools != 0 && bSize >= Av1.BLOCK_8X8 && Av1.blockWidth[bSize] <= 64 && Av1.blockHeight[bSize] <= 64) {
            if (!lastWasBest) {
                trial(r, c, bSize, best, saved)
                lastWasBest = true
            }
            ch.copyFrom(best)
            if (chromaPalette(r, c, bSize, ch)) {
                ch.skip = 0
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
        // A copy of an earlier part of the frame, where the frame allows it and the source repeats there.
        if (hash != null && d.fh.allowIntrabc && Av1.blockWidth[bSize] >= 8 && Av1.blockHeight[bSize] >= 8 && copy(r, c, bSize, ch)) {
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
        if (!lastWasBest) trial(r, c, bSize, best, saved)
        chosen[record.key(r, c, bSize)] = best
        return bestJ
    }

    /**
     * An intra block copy for the block of [bSize] at ([r], [c]) into [ch]: the nearest earlier place whose source
     * luma matches the block's, sample for sample, that a conformant stream may copy from (whole samples, decoded
     * far enough back). Among the last 64 windows of the block's hash. False when there is none.
     */
    private fun copy(r: Int, c: Int, bSize: Int, ch: Av1BlockChoice): Boolean {
        val h = hash ?: return false
        d.positionBlock(r, c, bSize)
        val bx = c * 4
        val by = r * 4
        val bw = Av1.blockWidth[bSize]
        val bh = Av1.blockHeight[bSize]
        var dvRow = 0
        var dvCol = 0
        var found = false
        h.earlier(bx, by, 64) { px, py ->
            val dr = (py - by) * 8
            val dc = (px - bx) * 8
            if (d.isDvValid(dr, dc) && h.same(px, py, bx, by, bw, bh)) {
                dvRow = dr
                dvCol = dc
                found = true
                false
            } else {
                true
            }
        }
        if (!found) return false
        ch.skip = 0
        ch.useIntrabc = true
        ch.mvRow = dvRow
        ch.mvCol = dvCol
        ch.yMode = Av1.DC_PRED
        ch.angleDeltaY = 0
        ch.uvMode = Av1.DC_PRED
        ch.angleDeltaUV = 0
        ch.cflAlphaU = 0
        ch.cflAlphaV = 0
        ch.useFilterIntra = false
        ch.paletteSizeY = 0
        ch.paletteSizeUV = 0
        ch.colorMapY = null
        ch.colorMapUV = null
        ch.txSize = Av1Tables.maxTxSizeRect[bSize]
        return true
    }

    private val pairCounts = HashMap<Int, Int>()

    /**
     * A chroma palette for the block of [bSize] at ([r], [c]) into [ch], with DC chroma, when its source chroma over
     * the coded area holds 2 to 8 pairs of U and V, or its 8 commonest cover 15/16 of it: the pairs ordered by U, as
     * the syntax reads them, and each sample mapped to the nearest pair. False when no palette fits.
     */
    private fun chromaPalette(r: Int, c: Int, bSize: Int, ch: Av1BlockChoice): Boolean {
        val bw = Av1.blockWidth[bSize]
        val bh = Av1.blockHeight[bSize]
        // The chroma block and its shown part as palette_tokens sizes them.
        val w = bw shr d.subX
        val h = bh shr d.subY
        if (w < 4 || h < 4) return false
        val onW = minOf(bw, (d.miCols - c) * 4) shr d.subX
        val onH = minOf(bh, (d.miRows - r) * 4) shr d.subY
        val x0 = (c * 4) shr d.subX
        val y0 = (r * 4) shr d.subY
        val stride = d.planeWidth[1]
        val u = src.planes[1]
        val v = src.planes[2]
        pairCounts.clear()
        for (y in 0 until onH) for (x in 0 until onW) {
            val at = (y0 + y) * stride + x0 + x
            val key = (u[at].toInt() shl 16) or v[at].toInt()
            pairCounts[key] = (pairCounts[key] ?: 0) + 1
            if (pairCounts.size > 64) return false
        }
        if (pairCounts.size < 2) return false
        val top = pairCounts.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(8)
        if (pairCounts.size > 8 && top.sumOf { it.value } * 16 < onW * onH * 15) return false
        // The key orders the pairs by U, then by V.
        val pairs = top.map { it.key }.sorted()
        val n = pairs.size
        val map = IntArray(64 * 64)
        for (y in 0 until onH) for (x in 0 until onW) {
            val at = (y0 + y) * stride + x0 + x
            val su = u[at].toInt()
            val sv = v[at].toInt()
            var bi = 0
            var be = Long.MAX_VALUE
            for (k in 0 until n) {
                val du = (su - (pairs[k] ushr 16)).toLong()
                val dv = (sv - (pairs[k] and 0xFFFF)).toLong()
                val e = du * du + dv * dv
                if (e < be) {
                    be = e
                    bi = k
                }
            }
            map[y * 64 + x] = bi
        }
        ch.uvMode = Av1.DC_PRED
        ch.angleDeltaUV = 0
        ch.cflAlphaU = 0
        ch.cflAlphaV = 0
        ch.paletteSizeUV = n
        for (k in 0 until n) {
            ch.paletteColorsU[k] = pairs[k] ushr 16
            ch.paletteColorsV[k] = pairs[k] and 0xFFFF
        }
        ch.colorMapUV = map
        return true
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

    // ---- inter blocks (#177) ---------------------------------------------------------------

    private val interFrame = !d.fh.frameIsIntra

    /** The references searched: LAST, and from effort 2 LAST2 where its slot holds another frame. */
    private val searchRefs: IntArray = when {
        !interFrame -> IntArray(0)
        effort >= 2 && d.fh.refFrameIdx[1] != d.fh.refFrameIdx[0] -> intArrayOf(Av1.LAST_FRAME, Av1.LAST2_FRAME)
        else -> intArrayOf(Av1.LAST_FRAME)
    }

    /** Whether blocks try LAST and LAST2 averaged: the frame allows it and both are searched. */
    private val tryCompound = interFrame && compound && d.fh.referenceSelect && searchRefs.size == 2

    /** The vector each 4x4 position's last search found, by reference searched, as row << 16 | column & 0xFFFF. */
    private val lastMv = Array(searchRefs.size) { IntArray(d.miStride * d.miRowsAligned) }
    private val lastMvSet = Array(searchRefs.size) { BooleanArray(d.miStride * d.miRowsAligned) }

    /** The vector found for each reference searched in the block being decided, packed as [lastMv] packs them. */
    private val newMv = IntArray(2)

    /** One inter candidate: its mode, references, stack index and vectors, packed in that order. */
    private val candidates = IntArray(16 * 8)
    private var candidateCount = 0
    private val candidateRank = LongArray(16)
    private val candidateOrder = IntArray(16)

    private fun addCandidate(mode: Int, ref0: Int, ref1: Int, refMvIdx: Int, mvRow0: Int, mvCol0: Int, mvRow1: Int, mvCol1: Int) {
        val at = candidateCount * 8
        candidates[at] = mode
        candidates[at + 1] = ref0
        candidates[at + 2] = ref1
        candidates[at + 3] = refMvIdx
        candidates[at + 4] = mvRow0
        candidates[at + 5] = mvCol0
        candidates[at + 6] = mvRow1
        candidates[at + 7] = mvCol1
        candidateCount++
    }

    private fun loadCandidate(k: Int, ch: Av1BlockChoice, bSize: Int) {
        val at = k * 8
        ch.copyFrom(blank)
        ch.isInter = true
        ch.yMode = candidates[at]
        ch.refFrame0 = candidates[at + 1]
        ch.refFrame1 = candidates[at + 2]
        ch.refMvIdx = candidates[at + 3]
        ch.mvRow = candidates[at + 4]
        ch.mvCol = candidates[at + 5]
        ch.mv1Row = candidates[at + 6]
        ch.mv1Col = candidates[at + 7]
        ch.txSize = if (lossless) Av1.TX_4X4 else Av1Tables.maxTxSizeRect[bSize]
    }

    private val blank = Av1BlockChoice()

    /**
     * The best inter coding of the block of [bSize] at ([r], [c]) into [best], and its cost. Each reference searched
     * gets a motion search ([motionSearch]); then NEARESTMV, NEARMV, GLOBALMV and NEWMV with the vector found, and
     * where the frame allows two references, their nearest, near and new pairs averaged, are each coded with no
     * residual (skip) through the frame decoder, which prices them exactly. The best few by that cost are coded again
     * with their residual, at the largest transform and, from effort 2, the best of them at one split.
     */
    private fun interSearch(r: Int, c: Int, bSize: Int, saved: Saved, ch: Av1BlockChoice, best: Av1BlockChoice): Long {
        candidateCount = 0
        for (i in searchRefs.indices) {
            val ref = searchRefs[i]
            newMv[i] = motionSearch(r, c, bSize, ref, i)
            // motionSearch left the stack of this reference alone.
            val stack = d.mvStack
            val n = stack.numMvFound
            addCandidate(Av1.NEARESTMV, ref, Av1.NONE, 0, 0, 0, 0, 0)
            addCandidate(Av1.GLOBALMV, ref, Av1.NONE, 0, 0, 0, 0, 0)
            // NEARMV codes index 1, and 2 and 3 where the stack holds them.
            val nearMax = if (effort >= 2) maxOf(1, minOf(3, n - 1)) else 1
            for (k in 1..nearMax) addCandidate(Av1.NEARMV, ref, Av1.NONE, k, 0, 0, 0, 0)
            val mvRow = newMv[i] shr 16
            val mvCol = (newMv[i] shl 16) shr 16
            // The stack entry the new vector is coded against, the nearest of those NEWMV can name.
            var idx = 0
            var bits = Int.MAX_VALUE
            for (k in 0 until minOf(3, maxOf(1, n))) {
                val b = mvBits(mvRow - stack.stack[k * 4], mvCol - stack.stack[k * 4 + 1]) + k * 256
                if (b < bits) {
                    bits = b
                    idx = k
                }
            }
            val same = (0 until minOf(2, maxOf(1, n))).any { k -> stack.stack[k * 4] == mvRow && stack.stack[k * 4 + 1] == mvCol }
            if (!same) addCandidate(Av1.NEWMV, ref, Av1.NONE, idx, mvRow, mvCol, 0, 0)
        }
        if (tryCompound && minOf(Av1.num4x4Wide[bSize], Av1.num4x4High[bSize]) >= 2) {
            d.positionBlock(r, c, bSize)
            d.refFrame[0] = Av1.LAST_FRAME
            d.refFrame[1] = Av1.LAST2_FRAME
            d.mvStack.find(true)
            addCandidate(Av1.NEAREST_NEARESTMV, Av1.LAST_FRAME, Av1.LAST2_FRAME, 0, 0, 0, 0, 0)
            if (effort >= 3) addCandidate(Av1.NEAR_NEARMV, Av1.LAST_FRAME, Av1.LAST2_FRAME, 1, 0, 0, 0, 0)
            addCandidate(Av1.NEW_NEWMV, Av1.LAST_FRAME, Av1.LAST2_FRAME, 0, newMv[0] shr 16, (newMv[0] shl 16) shr 16, newMv[1] shr 16, (newMv[1] shl 16) shr 16)
        }
        // Each candidate with no residual: its exact price, and the order to code the best with their residual in.
        var bestJ = Long.MAX_VALUE
        for (k in 0 until candidateCount) {
            loadCandidate(k, ch, bSize)
            ch.skip = 1
            val j = trial(r, c, bSize, ch, saved)
            candidateRank[k] = j
            candidateOrder[k] = k
            if (j < bestJ) {
                bestJ = j
                best.copyFrom(ch)
            }
        }
        for (i in 1 until candidateCount) {
            val k = candidateOrder[i]
            var j = i - 1
            while (j >= 0 && candidateRank[candidateOrder[j]] > candidateRank[k]) {
                candidateOrder[j + 1] = candidateOrder[j]
                j--
            }
            candidateOrder[j + 1] = k
        }
        val full = minOf(candidateCount, if (effort <= 1) 2 else if (effort == 2) 3 else 5)
        var bestCoded = -1
        var bestCodedJ = Long.MAX_VALUE
        for (i in 0 until full) {
            loadCandidate(candidateOrder[i], ch, bSize)
            ch.skip = 0
            val j = trial(r, c, bSize, ch, saved)
            if (j < bestCodedJ) {
                bestCodedJ = j
                bestCoded = candidateOrder[i]
            }
            if (j < bestJ) {
                bestJ = j
                best.copyFrom(ch)
            }
        }
        // One transform split for the best candidate coded with its residual.
        if (effort >= 2 && !lossless && bestCoded >= 0) {
            loadCandidate(bestCoded, ch, bSize)
            val split = Av1Tables.splitTxSize[ch.txSize]
            if (split != ch.txSize) {
                ch.skip = 0
                ch.txSize = split
                val j = trial(r, c, bSize, ch, saved)
                if (j < bestJ) {
                    bestJ = j
                    best.copyFrom(ch)
                }
            }
        }
        return bestJ
    }

    /** About what a vector difference of ([dr], [dc]) eighths costs to code, in 1/256 bits. */
    private fun mvBits(dr: Int, dc: Int): Int {
        fun component(v: Int): Int {
            if (v == 0) return 0
            val a = (if (v < 0) -v else v) shr 1
            return (3 + 2 * (31 - (a or 1).countLeadingZeroBits())) * 256
        }
        return 256 + component(dr) + component(dc)
    }

    /**
     * The vector, in eighths, that predicts the block of [bSize] at ([r], [c]) from [ref] best by its cost: the error
     * plus lambda times what the vector costs against the stack's nearest. Whole samples first, by the sum of absolute
     * differences against the reference frame, from the stack's vectors, zero and the vector found here at the last
     * size, then a square search at falling steps; then half and quarter samples around the best, by the Hadamard
     * error of the decoder's own prediction. Packed as row << 16 | column & 0xFFFF. Leaves [ref]'s stack in the
     * frame decoder.
     */
    private fun motionSearch(r: Int, c: Int, bSize: Int, ref: Int, refList: Int): Int {
        d.positionBlock(r, c, bSize)
        d.refFrame[0] = ref
        d.refFrame[1] = Av1.NONE
        val stack = d.mvStack
        stack.find(false)
        val predRow = stack.stack[0]
        val predCol = stack.stack[1]
        val slot = d.fh.refFrameIdx[ref - Av1.LAST_FRAME]
        val pic = d.refs.pictures[slot]!!
        val refPlane = pic.planes[0]
        val refStride = pic.strides[0]
        val lastX = d.refs.upscaledWidth[slot] - 1
        val lastY = d.refs.frameHeight[slot] - 1
        val bx = c * 4
        val by = r * 4
        val w = minOf(Av1.blockWidth[bSize], src.width - bx)
        val h = minOf(Av1.blockHeight[bSize], src.height - by)
        val m0 = d.mi(r, c)
        if (w <= 0 || h <= 0) return pack(predRow, predCol)
        var bestRow = 0
        var bestCol = 0
        var bestJ = Long.MAX_VALUE
        fun tryWhole(ir: Int, ic: Int) {
            if (ir < -MAX_WHOLE || ir > MAX_WHOLE || ic < -MAX_WHOLE || ic > MAX_WHOLE) return
            if (bestJ != Long.MAX_VALUE && ir == bestRow && ic == bestCol) return
            val rate = sqrtLambda256 * mvBits(ir * 8 - predRow, ic * 8 - predCol) shr 8
            if (bestJ != Long.MAX_VALUE && rate >= bestJ) return
            val limit = if (bestJ == Long.MAX_VALUE) Long.MAX_VALUE else (bestJ - rate + 255) shr 8
            val sad = sad(refPlane, refStride, lastX, lastY, bx, by, w, h, ic, ir, limit)
            val j = (sad shl 8) + rate
            if (j < bestJ) {
                bestJ = j
                bestRow = ir
                bestCol = ic
            }
        }
        tryWhole(0, 0)
        for (k in 0 until minOf(stack.numMvFound, 4)) tryWhole(roundEighths(stack.stack[k * 4]), roundEighths(stack.stack[k * 4 + 1]))
        if (lastMvSet[refList][m0]) {
            val v = lastMv[refList][m0]
            tryWhole(roundEighths(v shr 16), roundEighths((v shl 16) shr 16))
        }
        val steps = when (effort) {
            1 -> STEPS_1
            2 -> STEPS_2
            else -> STEPS_3
        }
        for (s in steps) {
            var rounds = 0
            while (rounds < 8) {
                val r0 = bestRow
                val c0 = bestCol
                for (k in 0 until 8) tryWhole(r0 + SQUARE[k * 2] * s, c0 + SQUARE[k * 2 + 1] * s)
                if (bestRow == r0 && bestCol == c0) break
                rounds++
            }
        }
        // Half and quarter samples, through the decoder's prediction.
        var mvRow = bestRow * 8
        var mvCol = bestCol * 8
        fun subJ(mr: Int, mc: Int): Long {
            d.predictInterLuma(ref, mr, mc, Av1.NONE, 0, 0, Av1.EIGHTTAP)
            val satd = satd(0, bx, by, Av1.blockWidth[bSize], Av1.blockHeight[bSize])
            return (satd shl 8) + (sqrtLambda256 * mvBits(mr - predRow, mc - predCol) shr 8)
        }
        var subBest = subJ(mvRow, mvCol)
        for (s in intArrayOf(4, 2)) {
            val r0 = mvRow
            val c0 = mvCol
            val n = if (effort >= 2) 8 else 4
            for (k in 0 until n) {
                val mr = r0 + SQUARE[k * 2] * s
                val mc = c0 + SQUARE[k * 2 + 1] * s
                val j = subJ(mr, mc)
                if (j < subBest) {
                    subBest = j
                    mvRow = mr
                    mvCol = mc
                }
            }
        }
        val packed = pack(mvRow, mvCol)
        val bw4 = Av1.num4x4Wide[bSize]
        val bh4 = Av1.num4x4High[bSize]
        for (y in 0 until bh4) for (x in 0 until bw4) {
            val m = d.mi(r + y, c + x)
            lastMv[refList][m] = packed
            lastMvSet[refList][m] = true
        }
        // The stack of [ref] again, which the predictions above leave as it was but the caller reads.
        return packed
    }

    private fun pack(row: Int, col: Int): Int = (row shl 16) or (col and 0xFFFF)

    /** A vector in eighths to the nearest whole sample. */
    private fun roundEighths(v: Int): Int = if (v >= 0) (v + 4) shr 3 else -((-v + 4) shr 3)

    /**
     * The sum of absolute differences of the source luma of [w] by [h] at ([bx], [by]) against the reference [ref]
     * moved by ([dx], [dy]) whole samples, its reads held inside the reference as prediction holds them; it stops
     * once past [limit].
     */
    private fun sad(ref: ShortArray, refStride: Int, lastX: Int, lastY: Int, bx: Int, by: Int, w: Int, h: Int, dx: Int, dy: Int, limit: Long): Long {
        val s = src.planes[0]
        val stride = d.planeWidth[0]
        val inside = bx + dx >= 0 && by + dy >= 0 && bx + dx + w - 1 <= lastX && by + dy + h - 1 <= lastY
        var sum = 0L
        for (y in 0 until h) {
            val sAt = (by + y) * stride + bx
            var row = 0
            if (inside) {
                val rAt = (by + y + dy) * refStride + bx + dx
                for (x in 0 until w) {
                    val e = s[sAt + x] - ref[rAt + x]
                    row += if (e < 0) -e else e
                }
            } else {
                val ry = (by + y + dy).coerceIn(0, lastY) * refStride
                for (x in 0 until w) {
                    val e = s[sAt + x] - ref[ry + (bx + x + dx).coerceIn(0, lastX)]
                    row += if (e < 0) -e else e
                }
            }
            sum += row
            if (sum >= limit) return sum
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
        directions = if (lossless || effort >= 3) 0xFF else strongestDirections(r, c, bSize)
        for (m in 0 until 13) {
            if (Av1.isDirectionalMode(m) && (directions shr (m - Av1.V_PRED)) and 1 == 0) continue
            lumaCand[n] = m or (3 shl 8)
            lumaRank[n] = lumaSatd(r, c, bSize, lumaCand[n])
            n++
        }
        if (!lossless && effort >= 3 && d.seq.enableFilterIntra && maxOf(Av1.blockWidth[bSize], Av1.blockHeight[bSize]) <= 32) {
            for (f in 0 until 5) {
                lumaCand[n] = Av1.DC_PRED or (3 shl 8) or ((f + 1) shl 16)
                lumaRank[n] = lumaSatd(r, c, bSize, lumaCand[n])
                n++
            }
        }
        sortCandidates(lumaCand, lumaRank, n)
        if (directional && effort >= 3) {
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

    /** The directional modes the last [rankLuma] ranked, as a mask by mode less V_PRED. */
    private var directions = 0xFF
    private val directionEnergy = IntArray(8)

    /**
     * The [DIRECTIONS] directional modes whose lines the source luma of the block of [bSize] at ([r], [c]) follows most,
     * as a mask by mode less V_PRED (#176). A line runs across the gradient, so each sample's central differences
     * (gx, gy) give |gx| + |gy| to the mode whose angle is nearest atan2(gx, gy) modulo 180 degrees: V_PRED for a
     * horizontal gradient, H_PRED for a vertical one, and the six between at steps of 22.5 degrees, D203_PRED's line
     * being that of 23 degrees. A mode whose lines the block does not hold seldom predicts it best, and ranking three
     * of the eight cost nothing measurable on the photo scenes.
     */
    private fun strongestDirections(r: Int, c: Int, bSize: Int): Int {
        val e = directionEnergy
        e.fill(0)
        val s = src.planes[0]
        val stride = d.planeWidth[0]
        val x0 = maxOf(1, c * 4)
        val y0 = maxOf(1, r * 4)
        val x1 = minOf(c * 4 + Av1.blockWidth[bSize], src.width - 1)
        val y1 = minOf(r * 4 + Av1.blockHeight[bSize], src.height - 1)
        for (y in y0 until y1) {
            val at = y * stride
            for (x in x0 until x1) {
                var gx = s[at + x + 1] - s[at + x - 1]
                var gy = s[at + stride + x] - s[at - stride + x]
                // The same line either way round: gy made positive, or gx when gy is 0.
                if (gy < 0 || (gy == 0 && gx < 0)) {
                    gx = -gx
                    gy = -gy
                }
                val t = if (gx < 0) -gx else gx
                if (t == 0 && gy == 0) continue
                // The angle from the gy axis against 11.25, 33.75, 56.25 and 78.75 degrees, whose tangents are about
                // 204, 684, 1533 and 5148 in 1/1024; a negative gx turns it the other way.
                val mode = when {
                    t * 1024 < gy * 204 -> Av1.H_PRED
                    t * 1024 < gy * 684 -> if (gx < 0) Av1.D157_PRED else Av1.D203_PRED
                    t * 1024 < gy * 1533 -> if (gx < 0) Av1.D135_PRED else Av1.D45_PRED
                    t * 1024 < gy * 5148 -> if (gx < 0) Av1.D113_PRED else Av1.D67_PRED
                    else -> Av1.V_PRED
                }
                e[mode - Av1.V_PRED] += t + gy
            }
        }
        // The strongest first, the lower mode on a tie.
        var mask = 0
        for (k in 0 until DIRECTIONS) {
            var best = -1
            for (i in 0 until 8) if ((mask shr i) and 1 == 0 && (best < 0 || e[i] > e[best])) best = i
            mask = mask or (1 shl best)
        }
        return mask
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
        if (interFrame) {
            costSink.symbol(probe.yMode, cdf[C.Y_MODE], Av1Tables.sizeGroup[bSize] * 14, 13)
        } else {
            val above = Av1Tables.intraModeContext[if (d.availU) d.yModes[d.mi(r - 1, c)] else Av1.DC_PRED]
            val left = Av1Tables.intraModeContext[if (d.availL) d.yModes[d.mi(r, c - 1)] else Av1.DC_PRED]
            costSink.symbol(probe.yMode, cdf[C.INTRA_FRAME_Y_MODE], (above * 5 + left) * 14, 13)
        }
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
        // At efforts 1 and 2, the directions the luma ranked, and the luma's own mode.
        val uvDirections = if (effort >= 3 || !Av1.isDirectionalMode(base.yMode)) directions else directions or (1 shl (base.yMode - Av1.V_PRED))
        for (m in 0 until 13) {
            if (Av1.isDirectionalMode(m) && (uvDirections shr (m - Av1.V_PRED)) and 1 == 0) continue
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
                if (x + 4 <= sw && y + 4 <= sh) {
                    total += hadamard4(a, b, y * stride + x, stride)
                } else {
                    for (i in 0 until 4) for (j in 0 until 4) {
                        val yy = y + i
                        val xx = x + j
                        diff[i * 4 + j] = if (yy < sh && xx < sw) a[yy * stride + xx] - b[yy * stride + xx] else 0
                    }
                    total += hadamard4(diff)
                }
                x += 4
            }
            y += 4
        }
        return total
    }

    /** [hadamard4] of the 4x4 differences of [a] less [b] at [at], rows [stride] apart, without the array. */
    private fun hadamard4(a: ShortArray, b: ShortArray, at: Int, stride: Int): Int {
        // Each row's transform: t0 to t3 of row i, from its four differences.
        val r0 = at
        val r1 = at + stride
        val r2 = r1 + stride
        val r3 = r2 + stride
        var x0 = a[r0] - b[r0]; var x1 = a[r0 + 1] - b[r0 + 1]; var x2 = a[r0 + 2] - b[r0 + 2]; var x3 = a[r0 + 3] - b[r0 + 3]
        val s00 = x0 + x1 + x2 + x3; val s01 = x0 - x1 + x2 - x3; val s02 = x0 + x1 - x2 - x3; val s03 = x0 - x1 - x2 + x3
        x0 = a[r1] - b[r1]; x1 = a[r1 + 1] - b[r1 + 1]; x2 = a[r1 + 2] - b[r1 + 2]; x3 = a[r1 + 3] - b[r1 + 3]
        val s10 = x0 + x1 + x2 + x3; val s11 = x0 - x1 + x2 - x3; val s12 = x0 + x1 - x2 - x3; val s13 = x0 - x1 - x2 + x3
        x0 = a[r2] - b[r2]; x1 = a[r2 + 1] - b[r2 + 1]; x2 = a[r2 + 2] - b[r2 + 2]; x3 = a[r2 + 3] - b[r2 + 3]
        val s20 = x0 + x1 + x2 + x3; val s21 = x0 - x1 + x2 - x3; val s22 = x0 + x1 - x2 - x3; val s23 = x0 - x1 - x2 + x3
        x0 = a[r3] - b[r3]; x1 = a[r3 + 1] - b[r3 + 1]; x2 = a[r3 + 2] - b[r3 + 2]; x3 = a[r3 + 3] - b[r3 + 3]
        val s30 = x0 + x1 + x2 + x3; val s31 = x0 - x1 + x2 - x3; val s32 = x0 + x1 - x2 - x3; val s33 = x0 - x1 - x2 + x3
        val sum = column(s00, s10, s20, s30) + column(s01, s11, s21, s31) + column(s02, s12, s22, s32) + column(s03, s13, s23, s33)
        return (sum + 1) shr 1
    }

    /** The sum of the absolute values of the 4-point Hadamard transform of one column. */
    private fun column(m0: Int, m1: Int, m2: Int, m3: Int): Int {
        val a0 = m0 + m1
        val a1 = m0 - m1
        val a2 = m2 + m3
        val a3 = m2 - m3
        return abs(a0 + a2) + abs(a1 + a3) + abs(a0 - a2) + abs(a1 - a3)
    }

    private fun abs(v: Int): Int = if (v < 0) -v else v

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
        val types = candidateTypes(plane, txSz, x4, y4)
        val memoKey = ((startY and 63) shl 18) or ((startX and 63) shl 12) or (txSz shl 7) or (plane shl 5) or d.miSize
        val memoExtra = (if (d.isInter) 1 shl 20 else 0) or ((if (d.useFilterIntra) Av1Tables.filterIntraModeToIntraDir[d.filterIntraMode] else d.yMode) shl 8)
        val w4 = w shr 2
        val h4 = h shr 2
        if (mode != Mode.COMMIT) {
            val ctx = memoCtx
            for (i in 0 until w4) {
                ctx[i] = d.aboveLevelContext[plane][x4 + i]
                ctx[w4 + i] = d.aboveDcContext[plane][x4 + i]
            }
            for (i in 0 until h4) {
                ctx[2 * w4 + i] = d.leftLevelContext[plane][y4 + i]
                ctx[2 * w4 + h4 + i] = d.leftDcContext[plane][y4 + i]
            }
            val eob = recall(memoKey, memoExtra, w * h, 2 * (w4 + h4), types)
            if (eob >= 0) return eob
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
        val dqDenom = Av1Transform.dqDenom(txSz)
        val scale = Av1ForwardTransform.distortionScale(txSz).toLong()
        val n = tw * th
        var typeCount = 0
        while (types[typeCount] >= 0) typeCount++
        // At efforts 1 and 2, of several types only the one whose coefficients have the least sum of magnitudes, which
        // gathers the residual into the fewest, is quantized and priced (#176).
        var only = -1
        if (effort <= 2 && typeCount > 1) {
            var least = Long.MAX_VALUE
            for (i in 0 until typeCount) {
                val tc = typeCoef[i]
                fwd.forward(residual, txSz, types[i], tc)
                var sum = 0L
                var k = 0
                while (k < n) {
                    // A row of at most 32 coefficients in an Int, then into the Long.
                    var row = 0
                    for (j in k until k + tw) row += if (tc[j] < 0) -tc[j] else tc[j]
                    sum += row
                    k += tw
                }
                if (sum < least) {
                    least = sum
                    only = i
                }
            }
        }
        for (i in 0 until typeCount) {
            if (only >= 0 && i != only) continue
            val type = types[i]
            if (only >= 0) typeCoef[i].copyInto(coef, 0, 0, n) else fwd.forward(residual, txSz, type, coef)
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
        if (mode != Mode.COMMIT) remember(memoKey, memoExtra, w * h, 2 * (w4 + h4), types, n, bestEob, bestType)
        return bestEob
    }

    // ---- results of [choose] kept for a repeat -----------------------------------------

    /**
     * What [choose] found for one transform block, with everything it read: the residual, the coefficient contexts
     * beside the block, the types it tried, and the block's size and intra direction, which the symbols' contexts read.
     */
    private class Memo {
        var extra = 0
        var residual = IntArray(16)
        val ctx = IntArray(64)
        val types = IntArray(17)
        var levels = IntArray(16)
        var n = 0
        var eob = 0
        var type = 0
    }

    /**
     * [choose] is a function of what [Memo] keeps and of the CDFs, which stay frozen while a superblock is searched.
     * The search codes one transform block with the same inputs many times over: the chroma of each luma candidate,
     * the luma of each chroma candidate, and the winners again when a partition is replayed. So each result is kept
     * until the superblock is committed, the last [MEMO_WAYS] for each place and size, and a call whose inputs match
     * one exactly takes its result, which is the result it would compute.
     */
    private val memo = HashMap<Int, ArrayList<Memo>>()
    private val memoFree = ArrayList<Memo>()
    private val memoCtx = IntArray(64)

    private fun forgetMemo() {
        for (list in memo.values) memoFree.addAll(list)
        memo.clear()
    }

    /** The end of block a kept result gives, its levels into [levels] and its type into [txType]; -1 for none. */
    private fun recall(key: Int, extra: Int, size: Int, ctxSize: Int, types: IntArray): Int {
        val list = memo[key]
        if (list != null) {
            next@ for (k in list.indices.reversed()) {
                val m = list[k]
                if (m.extra != extra) continue
                var t = 0
                while (true) {
                    if (m.types[t] != types[t]) continue@next
                    if (types[t] < 0) break
                    t++
                }
                for (i in 0 until ctxSize) if (m.ctx[i] != memoCtx[i]) continue@next
                val res = m.residual
                for (i in 0 until size) if (res[i] != residual[i]) continue@next
                m.levels.copyInto(levels, 0, 0, m.n)
                txType = m.type
                return m.eob
            }
        }
        return -1
    }

    private fun remember(key: Int, extra: Int, size: Int, ctxSize: Int, types: IntArray, n: Int, eob: Int, type: Int) {
        val list = memo.getOrPut(key) { ArrayList(MEMO_WAYS) }
        val m = if (list.size == MEMO_WAYS) list.removeAt(0) else if (memoFree.isNotEmpty()) memoFree.removeAt(memoFree.size - 1) else Memo()
        m.extra = extra
        if (m.residual.size < size) m.residual = IntArray(size)
        residual.copyInto(m.residual, 0, 0, size)
        memoCtx.copyInto(m.ctx, 0, 0, ctxSize)
        var t = 0
        while (true) {
            m.types[t] = types[t]
            if (types[t] < 0) break
            t++
        }
        if (m.levels.size < n) m.levels = IntArray(n)
        levels.copyInto(m.levels, 0, 0, n)
        m.n = n
        m.eob = eob
        m.type = type
        list += m
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
    /** The coefficients of each type [choose] weighs, while it picks one. */
    private val typeCoef = Array(16) { IntArray(1024) }

    /** The transform types to try for [plane] and [txSz], -1 ended: luma by the set and effort, chroma the one it derives. */
    private fun candidateTypes(plane: Int, txSz: Int, x4: Int, y4: Int): IntArray {
        var n = 0
        if (plane > 0) {
            typeList[n++] = d.computeTxType(plane, txSz, x4, y4)
        } else if (d.isInter) {
            // An intra block copy's residual: the DCT, and the identity where its set has it, for the hard edges a
            // near copy of screen content leaves.
            typeList[n++] = Av1.DCT_DCT
            val set = d.txSet(txSz)
            val index = when (set) {
                Av1.TX_SET_DCTONLY -> -1
                Av1.TX_SET_INTER_1 -> Av1FrameDecoder.INTER_SET1_INDEX[Av1.IDTX]
                Av1.TX_SET_INTER_2 -> Av1FrameDecoder.INTER_SET2_INDEX[Av1.IDTX]
                else -> Av1FrameDecoder.INTER_SET3_INDEX[Av1.IDTX]
            }
            if (index >= 0 && effort >= 2) typeList[n++] = Av1.IDTX
        } else {
            val set = d.txSet(txSz)
            if (set == Av1.TX_SET_DCTONLY) {
                typeList[n++] = Av1.DCT_DCT
            } else {
                val inSet = if (set == Av1.TX_SET_INTRA_1) Av1Tables.txTypeIntraInvSet1 else Av1Tables.txTypeIntraInvSet2
                val implied = Av1Tables.modeToTxfm[if (d.useFilterIntra) Av1Tables.filterIntraModeToIntraDir[d.filterIntraMode] else d.yMode]
                typeList[n++] = Av1.DCT_DCT
                if (effort <= 1) {
                    if (implied != Av1.DCT_DCT && implied in inSet) typeList[n++] = implied
                } else if (effort == 2) {
                    // The identity for hard edges and the mode's own type beside the DCT.
                    if (Av1.IDTX in inSet) typeList[n++] = Av1.IDTX
                    if (implied != Av1.DCT_DCT && implied in inSet) typeList[n++] = implied
                } else {
                    for (t in inSet) {
                        if (t == Av1.DCT_DCT) continue
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
        /** The furthest whole-sample vector component searched, well inside what a vector may hold (2^14 eighths). */
        private const val MAX_WHOLE = 512
        /** The steps of the square search at each effort. */
        private val STEPS_1 = intArrayOf(4, 2, 1)
        private val STEPS_2 = intArrayOf(16, 8, 4, 2, 1)
        private val STEPS_3 = intArrayOf(32, 16, 8, 4, 2, 1)
        /** The eight neighbours of a point, the four nearest first: row and column steps. */
        private val SQUARE = intArrayOf(-1, 0, 0, -1, 0, 1, 1, 0, -1, -1, -1, 1, 1, -1, 1, 1)
        val PRUNE = LongArray(4)
        /** How many directional modes [strongestDirections] keeps at efforts 1 and 2. */
        private const val DIRECTIONS = 3
        /** How many results of [choose] are kept for each place and size of transform block. */
        private const val MEMO_WAYS = 4
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
