package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.codec.avif.Av1.DC_PRED
import io.github.yuroyami.imagekodec.codec.avif.Av1.TX_4X4
import io.github.yuroyami.imagekodec.codec.avif.Av1.UV_CFL_PRED
import io.github.yuroyami.imagekodec.codec.avif.Av1Cdfs.Companion as C

/**
 * One AV1 frame being decoded: its sample planes, the per-4x4 mode information the
 * specification keeps, and the tile decode of sections 5.11 and 7.11 to 7.13. The names
 * follow the specification's own, in camel case, so each step can be read beside it.
 */
internal class Av1FrameDecoder(val seq: Av1SequenceHeader, val fh: Av1FrameHeader, startCdfs: Av1Cdfs) {

    val bitDepth = seq.bitDepth
    val numPlanes = seq.numPlanes
    val subX = seq.subsamplingX
    val subY = seq.subsamplingY
    val miRows = fh.miRows
    val miCols = fh.miCols
    val sb128 = seq.use128x128Superblock
    val sbSize4 = if (sb128) 32 else 16
    val sbMask = if (sb128) 31 else 15

    /** The mode information grid, aligned to whole superblocks so a block crossing the frame edge has room. */
    val miStride = ((miCols + sbSize4 - 1) / sbSize4) * sbSize4
    val miRowsAligned = ((miRows + sbSize4 - 1) / sbSize4) * sbSize4
    private val miCount = miStride * miRowsAligned

    /** CurrFrame: one plane of samples each, at the superblock-aligned size. */
    val planeWidth = IntArray(3)
    val planeHeight = IntArray(3)
    val frame: Array<IntArray>

    init {
        if (miCount.toLong() * 16 > (1L shl 28)) throw UnsupportedImageException("AV1 frame of ${fh.frameWidth} by ${fh.frameHeight}")
        frame = Array(numPlanes) { p ->
            val sx = if (p > 0) subX else 0
            val sy = if (p > 0) subY else 0
            planeWidth[p] = (miStride * 4) shr sx
            planeHeight[p] = (miRowsAligned * 4) shr sy
            IntArray(planeWidth[p] * planeHeight[p])
        }
    }

    /** The frame after its filters, when they produced new planes; [frame] otherwise. */
    var output: Array<IntArray>? = null
    var outputStride: IntArray? = null

    fun outputPlanes(): Array<IntArray> = output ?: frame
    fun outputStrides(): IntArray = outputStride ?: planeWidth.copyOf(numPlanes)

    // ---- per-4x4 mode information ----------------------------------------------------
    val yModes = IntArray(miCount)
    val uvModes = IntArray(miCount)
    val refFrames0 = IntArray(miCount)
    val refFrames1 = IntArray(miCount) { -1 }
    val isInters = BooleanArray(miCount)
    val skipModes = IntArray(miCount)
    val skips = IntArray(miCount)
    val txSizes = IntArray(miCount)
    val interTxSizes = IntArray(miCount)
    val miSizes = IntArray(miCount)
    val segmentIds = IntArray(miCount)
    val prevSegmentIds = IntArray(miCount)
    val paletteSizes = Array(2) { IntArray(miCount) }
    val paletteColors = Array(2) { IntArray(miCount * 8) }
    val deltaLfs = IntArray(miCount * 4)
    val txTypes = IntArray(miCount)
    val mvs = IntArray(miCount * 4)
    val loopfilterTxSizes = Array(3) { IntArray(miCount) }
    val cdefIdx = IntArray(miCount) { -1 }

    fun mi(row: Int, col: Int): Int = row * miStride + col

    // ---- loop restoration units ------------------------------------------------------
    val lrUnitRows = IntArray(3)
    val lrUnitCols = IntArray(3)
    val lrType = arrayOfNulls<IntArray>(3)
    val lrWiener = arrayOfNulls<IntArray>(3)
    val lrSgrSet = arrayOfNulls<IntArray>(3)
    val lrSgrXqd = arrayOfNulls<IntArray>(3)

    init {
        for (plane in 0 until numPlanes) {
            if (fh.frameRestorationType[plane] == Av1FrameHeader.RESTORE_NONE) continue
            val sx = if (plane == 0) 0 else subX
            val sy = if (plane == 0) 0 else subY
            val unitSize = fh.loopRestorationSize[plane]
            lrUnitRows[plane] = countUnitsInFrame(unitSize, Av1.round2(fh.frameHeight, sy))
            lrUnitCols[plane] = countUnitsInFrame(unitSize, Av1.round2(fh.upscaledWidth, sx))
            val n = lrUnitRows[plane] * lrUnitCols[plane]
            lrType[plane] = IntArray(n)
            lrWiener[plane] = IntArray(n * 6)
            lrSgrSet[plane] = IntArray(n)
            lrSgrXqd[plane] = IntArray(n * 2)
        }
    }

    private fun countUnitsInFrame(unitSize: Int, frameSize: Int): Int = maxOf((frameSize + (unitSize shr 1)) / unitSize, 1)

    // ---- CDFs ----------------------------------------------------------------------------
    /** The CDFs every tile starts from. */
    val frameCdfs: Av1Cdfs = startCdfs
    /** The CDFs of the tile `context_update_tile_id` names, at its end. */
    var savedCdfs: Av1Cdfs? = null

    // ---- tile state ----------------------------------------------------------------------
    lateinit var sd: Av1SymbolDecoder
    lateinit var cdf: Av1Cdfs
    var miRowStart = 0
    var miRowEnd = 0
    var miColStart = 0
    var miColEnd = 0
    var currentQIndex = 0
    val deltaLf = IntArray(4)
    var readDeltas = false
    val refSgrXqd = Array(3) { IntArray(2) }
    val refLrWiener = Array(3) { Array(2) { IntArray(3) } }

    val aboveLevelContext = Array(3) { IntArray(miStride + 32) }
    val aboveDcContext = Array(3) { IntArray(miStride + 32) }
    val leftLevelContext = Array(3) { IntArray(miRowsAligned + 32) }
    val leftDcContext = Array(3) { IntArray(miRowsAligned + 32) }
    val aboveSegPredContext = IntArray(miStride + 32)
    val leftSegPredContext = IntArray(miRowsAligned + 32)

    /** BlockDecoded[ plane ][ y ][ x ] for y and x from -1 to 32, stored one higher. */
    val blockDecoded = Array(3) { BooleanArray(34 * 34) }
    fun blockDecodedAt(plane: Int, y: Int, x: Int): Boolean = blockDecoded[plane][(y + 1) * 34 + (x + 1)]

    // ---- block state ---------------------------------------------------------------------
    var miRow = 0
    var miCol = 0
    var miSize = 0
    var hasChroma = false
    var availU = false
    var availL = false
    var availUChroma = false
    var availLChroma = false
    var skip = 0
    var skipMode = 0
    var segmentId = 0
    var lossless = false
    var isInter = false
    var useIntrabc = false
    var yMode = 0
    var uvMode = 0
    var angleDeltaY = 0
    var angleDeltaUV = 0
    var cflAlphaU = 0
    var cflAlphaV = 0
    var useFilterIntra = false
    var filterIntraMode = 0
    var paletteSizeY = 0
    var paletteSizeUV = 0
    val paletteColorsY = IntArray(8)
    val paletteColorsU = IntArray(8)
    val paletteColorsV = IntArray(8)
    val colorMapY = IntArray(64 * 64)
    val colorMapUV = IntArray(64 * 64)
    var txSize = 0
    val refFrame = intArrayOf(0, -1)
    val mv = IntArray(4)
    var maxLumaW = 0
    var maxLumaH = 0
    var planeTxType = 0

    /** Quant[ ] of the transform block being read, laid out with Min( 32, w ) columns. */
    val quant = IntArray(1024)

    private val transform = Av1Transform()
    private val paletteCache = IntArray(16)
    private val colorOrder = IntArray(8)
    private var colorContextHash = 0

    // ---- tiles -------------------------------------------------------------------------

    /** Decodes tile [tileNum] from [size] bytes of [data] at [start]. */
    fun decodeTile(tileNum: Int, data: ByteArray, start: Int, size: Int) {
        val tileRow = tileNum / fh.tileCols
        val tileCol = tileNum % fh.tileCols
        miRowStart = fh.miRowStarts[tileRow]
        miRowEnd = fh.miRowStarts[tileRow + 1]
        miColStart = fh.miColStarts[tileCol]
        miColEnd = fh.miColStarts[tileCol + 1]
        currentQIndex = fh.baseQIdx
        // init_symbol( tileSize ): the tile's CDFs start from the frame's.
        cdf = frameCdfs.copy()
        cdf.resetIntraFrameYMode()
        sd = Av1SymbolDecoder(data, start, size, fh.disableCdfUpdate)
        // decode_tile( )
        for (plane in 0 until 3) {
            aboveLevelContext[plane].fill(0)
            aboveDcContext[plane].fill(0)
        }
        aboveSegPredContext.fill(0)
        deltaLf.fill(0)
        for (plane in 0 until numPlanes) for (pass in 0 until 2) {
            refSgrXqd[plane][pass] = Av1Tables.sgrprojXqdMid[pass]
            for (i in 0 until 3) refLrWiener[plane][pass][i] = Av1Tables.wienerTapsMid[i]
        }
        val sbSize = if (sb128) Av1.BLOCK_128X128 else Av1.BLOCK_64X64
        var r = miRowStart
        while (r < miRowEnd) {
            for (plane in 0 until 3) {
                leftLevelContext[plane].fill(0)
                leftDcContext[plane].fill(0)
            }
            leftSegPredContext.fill(0)
            var c = miColStart
            while (c < miColEnd) {
                readDeltas = fh.deltaQPresent
                clearCdef(r, c)
                clearBlockDecodedFlags(r, c)
                readLr(r, c, sbSize)
                decodePartition(r, c, sbSize)
                c += sbSize4
            }
            r += sbSize4
        }
        // exit_symbol( ): keep this tile's CDFs when it is the one that updates the frame context.
        if (!fh.disableFrameEndUpdateCdf && tileNum == fh.contextUpdateTileId) savedCdfs = cdf
    }

    private fun clearCdef(r: Int, c: Int) {
        cdefIdx[mi(r, c)] = -1
        if (sb128) {
            val s = 16
            if (c + s < miStride) cdefIdx[mi(r, c + s)] = -1
            if (r + s < miRowsAligned) {
                cdefIdx[mi(r + s, c)] = -1
                if (c + s < miStride) cdefIdx[mi(r + s, c + s)] = -1
            }
        }
    }

    private fun clearBlockDecodedFlags(r: Int, c: Int) {
        for (plane in 0 until numPlanes) {
            val sx = if (plane > 0) subX else 0
            val sy = if (plane > 0) subY else 0
            val sbWidth4 = (miColEnd - c) shr sx
            val sbHeight4 = (miRowEnd - r) shr sy
            val bd = blockDecoded[plane]
            for (y in -1..(sbSize4 shr sy)) for (x in -1..(sbSize4 shr sx)) {
                bd[(y + 1) * 34 + (x + 1)] = when {
                    y < 0 && x < sbWidth4 -> true
                    x < 0 && y < sbHeight4 -> true
                    else -> false
                }
            }
            bd[((sbSize4 shr sy) + 1) * 34 + 0] = false
        }
    }

    fun isInside(candR: Int, candC: Int): Boolean =
        candC >= miColStart && candC < miColEnd && candR >= miRowStart && candR < miRowEnd

    // ---- partitions ------------------------------------------------------------------

    private fun decodePartition(r: Int, c: Int, bSize: Int) {
        if (r >= miRows || c >= miCols) return
        availU = isInside(r - 1, c)
        availL = isInside(r, c - 1)
        val num4x4 = Av1.num4x4Wide[bSize]
        val halfBlock4x4 = num4x4 shr 1
        val quarterBlock4x4 = halfBlock4x4 shr 1
        val hasRows = (r + halfBlock4x4) < miRows
        val hasCols = (c + halfBlock4x4) < miCols
        val partition = when {
            bSize < Av1.BLOCK_8X8 -> Av1.PARTITION_NONE
            hasRows && hasCols -> readPartition(r, c, bSize)
            hasCols -> if (readSplitOrHorz(r, c, bSize) == 1) Av1.PARTITION_SPLIT else Av1.PARTITION_HORZ
            hasRows -> if (readSplitOrVert(r, c, bSize) == 1) Av1.PARTITION_SPLIT else Av1.PARTITION_VERT
            else -> Av1.PARTITION_SPLIT
        }
        val subSize = Av1.partitionSubsize(partition, bSize)
        val splitSize = Av1.partitionSubsize(Av1.PARTITION_SPLIT, bSize)
        when (partition) {
            Av1.PARTITION_NONE -> decodeBlock(r, c, subSize)
            Av1.PARTITION_HORZ -> {
                decodeBlock(r, c, subSize)
                if (hasRows) decodeBlock(r + halfBlock4x4, c, subSize)
            }
            Av1.PARTITION_VERT -> {
                decodeBlock(r, c, subSize)
                if (hasCols) decodeBlock(r, c + halfBlock4x4, subSize)
            }
            Av1.PARTITION_SPLIT -> {
                decodePartition(r, c, subSize)
                decodePartition(r, c + halfBlock4x4, subSize)
                decodePartition(r + halfBlock4x4, c, subSize)
                decodePartition(r + halfBlock4x4, c + halfBlock4x4, subSize)
            }
            Av1.PARTITION_HORZ_A -> {
                decodeBlock(r, c, splitSize)
                decodeBlock(r, c + halfBlock4x4, splitSize)
                decodeBlock(r + halfBlock4x4, c, subSize)
            }
            Av1.PARTITION_HORZ_B -> {
                decodeBlock(r, c, subSize)
                decodeBlock(r + halfBlock4x4, c, splitSize)
                decodeBlock(r + halfBlock4x4, c + halfBlock4x4, splitSize)
            }
            Av1.PARTITION_VERT_A -> {
                decodeBlock(r, c, splitSize)
                decodeBlock(r + halfBlock4x4, c, splitSize)
                decodeBlock(r, c + halfBlock4x4, subSize)
            }
            Av1.PARTITION_VERT_B -> {
                decodeBlock(r, c, subSize)
                decodeBlock(r, c + halfBlock4x4, splitSize)
                decodeBlock(r + halfBlock4x4, c + halfBlock4x4, splitSize)
            }
            Av1.PARTITION_HORZ_4 -> {
                decodeBlock(r, c, subSize)
                decodeBlock(r + quarterBlock4x4, c, subSize)
                decodeBlock(r + quarterBlock4x4 * 2, c, subSize)
                if (r + quarterBlock4x4 * 3 < miRows) decodeBlock(r + quarterBlock4x4 * 3, c, subSize)
            }
            else -> {
                decodeBlock(r, c, subSize)
                decodeBlock(r, c + quarterBlock4x4, subSize)
                decodeBlock(r, c + quarterBlock4x4 * 2, subSize)
                if (c + quarterBlock4x4 * 3 < miCols) decodeBlock(r, c + quarterBlock4x4 * 3, subSize)
            }
        }
    }

    /** The partition CDF for [bSize] at ([r], [c]): its array and offset. */
    private fun partitionCdf(r: Int, c: Int, bSize: Int): Pair<IntArray, Int> {
        val bsl = Av1.miWidthLog2[bSize]
        val above = if (availU && Av1.miWidthLog2[miSizes[mi(r - 1, c)]] < bsl) 1 else 0
        val left = if (availL && Av1.miHeightLog2[miSizes[mi(r, c - 1)]] < bsl) 1 else 0
        val ctx = left * 2 + above
        return when (bsl) {
            1 -> cdf[C.PARTITION_W8] to ctx * 5
            2 -> cdf[C.PARTITION_W16] to ctx * 11
            3 -> cdf[C.PARTITION_W32] to ctx * 11
            4 -> cdf[C.PARTITION_W64] to ctx * 11
            else -> cdf[C.PARTITION_W128] to ctx * 9
        }
    }

    private fun readPartition(r: Int, c: Int, bSize: Int): Int {
        val (a, o) = partitionCdf(r, c, bSize)
        val bsl = Av1.miWidthLog2[bSize]
        val n = when (bsl) {
            1 -> 4
            5 -> 8
            else -> 10
        }
        return sd.symbol(a, o, n)
    }

    private fun cdfAt(a: IntArray, o: Int, i: Int): Int = if (i < 0) 0 else a[o + i]

    private fun readSplitOrHorz(r: Int, c: Int, bSize: Int): Int {
        val (a, o) = partitionCdf(r, c, bSize)
        fun p(i: Int) = cdfAt(a, o, i) - cdfAt(a, o, i - 1)
        var psum = p(Av1.PARTITION_VERT) + p(Av1.PARTITION_SPLIT) + p(Av1.PARTITION_HORZ_A) +
            p(Av1.PARTITION_VERT_A) + p(Av1.PARTITION_VERT_B)
        if (bSize != Av1.BLOCK_128X128) psum += p(Av1.PARTITION_VERT_4)
        return boolSymbol(psum)
    }

    private fun readSplitOrVert(r: Int, c: Int, bSize: Int): Int {
        val (a, o) = partitionCdf(r, c, bSize)
        fun p(i: Int) = cdfAt(a, o, i) - cdfAt(a, o, i - 1)
        var psum = p(Av1.PARTITION_HORZ) + p(Av1.PARTITION_SPLIT) + p(Av1.PARTITION_HORZ_A) +
            p(Av1.PARTITION_HORZ_B) + p(Av1.PARTITION_VERT_A)
        if (bSize != Av1.BLOCK_128X128) psum += p(Av1.PARTITION_HORZ_4)
        return boolSymbol(psum)
    }

    private val tmpCdf = IntArray(3)

    /**
     * A two-symbol read on the CDF { 32768 - psum, 32768, 0 } the specification builds for
     * split_or_horz and split_or_vert; it adapts a throwaway copy, as the built array is
     * never kept.
     */
    private fun boolSymbol(psum: Int): Int {
        tmpCdf[0] = (1 shl 15) - psum
        tmpCdf[1] = 1 shl 15
        tmpCdf[2] = 0
        return sd.symbol(tmpCdf, 0, 2)
    }

    // ---- blocks ----------------------------------------------------------------------

    private fun decodeBlock(r: Int, c: Int, subSize: Int) {
        miRow = r
        miCol = c
        miSize = subSize
        val bw4 = Av1.num4x4Wide[subSize]
        val bh4 = Av1.num4x4High[subSize]
        hasChroma = when {
            bh4 == 1 && subY == 1 && (miRow and 1) == 0 -> false
            bw4 == 1 && subX == 1 && (miCol and 1) == 0 -> false
            else -> numPlanes > 1
        }
        availU = isInside(r - 1, c)
        availL = isInside(r, c - 1)
        availUChroma = availU
        availLChroma = availL
        if (hasChroma) {
            if (subY == 1 && bh4 == 1) availUChroma = isInside(r - 2, c)
            if (subX == 1 && bw4 == 1) availLChroma = isInside(r, c - 2)
        } else {
            availUChroma = false
            availLChroma = false
        }
        if (fh.frameIsIntra) intraFrameModeInfo() else throw UnsupportedImageException("AV1 inter frames")
        paletteTokens()
        readBlockTxSize()
        if (skip != 0) resetBlockContext(bw4, bh4)
        for (y in 0 until bh4) for (x in 0 until bw4) {
            val m = mi(r + y, c + x)
            yModes[m] = yMode
            if (refFrame[0] == Av1.INTRA_FRAME && hasChroma) uvModes[m] = uvMode
            refFrames0[m] = refFrame[0]
            refFrames1[m] = refFrame[1]
            if (isInter) {
                mvs[m * 4] = mv[0]
                mvs[m * 4 + 1] = mv[1]
            }
        }
        computePrediction()
        residual()
        for (y in 0 until bh4) for (x in 0 until bw4) {
            val m = mi(r + y, c + x)
            isInters[m] = isInter
            skipModes[m] = skipMode
            skips[m] = skip
            txSizes[m] = txSize
            miSizes[m] = miSize
            segmentIds[m] = segmentId
            paletteSizes[0][m] = paletteSizeY
            paletteSizes[1][m] = paletteSizeUV
            for (i in 0 until paletteSizeY) paletteColors[0][m * 8 + i] = paletteColorsY[i]
            for (i in 0 until paletteSizeUV) paletteColors[1][m * 8 + i] = paletteColorsU[i]
            for (i in 0 until 4) deltaLfs[m * 4 + i] = deltaLf[i]
        }
    }

    private fun resetBlockContext(bw4: Int, bh4: Int) {
        for (plane in 0 until 1 + (if (hasChroma) 2 else 0)) {
            val sx = if (plane > 0) subX else 0
            val sy = if (plane > 0) subY else 0
            for (i in (miCol shr sx) until ((miCol + bw4) shr sx)) {
                aboveLevelContext[plane][i] = 0
                aboveDcContext[plane][i] = 0
            }
            for (i in (miRow shr sy) until ((miRow + bh4) shr sy)) {
                leftLevelContext[plane][i] = 0
                leftDcContext[plane][i] = 0
            }
        }
    }

    // ---- intra frame mode info -------------------------------------------------------

    private fun intraFrameModeInfo() {
        skip = 0
        if (fh.segIdPreSkip) intraSegmentId()
        skipMode = 0
        readSkip()
        if (!fh.segIdPreSkip) intraSegmentId()
        readCdef()
        readDeltaQIndex()
        readDeltaLf()
        readDeltas = false
        refFrame[0] = Av1.INTRA_FRAME
        refFrame[1] = Av1.NONE
        useIntrabc = if (fh.allowIntrabc) sd.symbol(cdf[C.INTRABC], 0, 2) == 1 else false
        useFilterIntra = false
        if (useIntrabc) {
            isInter = true
            yMode = DC_PRED
            uvMode = DC_PRED
            paletteSizeY = 0
            paletteSizeUV = 0
            intraBlockCopyModeInfo()
        } else {
            isInter = false
            val aboveMode = Av1Tables.intraModeContext[if (availU) yModes[mi(miRow - 1, miCol)] else DC_PRED]
            val leftMode = Av1Tables.intraModeContext[if (availL) yModes[mi(miRow, miCol - 1)] else DC_PRED]
            yMode = sd.symbol(cdf[C.INTRA_FRAME_Y_MODE], (aboveMode * 5 + leftMode) * 14, 13)
            intraAngleInfoY()
            if (hasChroma) {
                readUvMode()
                if (uvMode == UV_CFL_PRED) readCflAlphas()
                intraAngleInfoUv()
            }
            paletteSizeY = 0
            paletteSizeUV = 0
            if (miSize >= Av1.BLOCK_8X8 && Av1.blockWidth[miSize] <= 64 && Av1.blockHeight[miSize] <= 64 &&
                fh.allowScreenContentTools != 0
            ) {
                paletteModeInfo()
            }
            filterIntraModeInfo()
        }
    }

    /** Intra block copy: its motion vector, set by the intra block copy step of [Av1IntraBlockCopy]. */
    private fun intraBlockCopyModeInfo() {
        Av1IntraBlockCopy.assignMv(this)
    }

    private fun readUvMode() {
        val cflAllowed = if (lossless && Av1.subsampledSize(miSize, subX, subY) == Av1.BLOCK_4X4) true
        else !lossless && maxOf(Av1.blockWidth[miSize], Av1.blockHeight[miSize]) <= 32
        uvMode = if (cflAllowed) sd.symbol(cdf[C.UV_MODE_CFL_ALLOWED], yMode * 15, 14)
        else sd.symbol(cdf[C.UV_MODE_CFL_NOT_ALLOWED], yMode * 14, 13)
    }

    private fun intraSegmentId() {
        segmentId = if (fh.segmentationEnabled) readSegmentId() else 0
        lossless = fh.losslessArray[segmentId]
    }

    private fun readSegmentId(): Int {
        val prevUL = if (availU && availL) segmentIds[mi(miRow - 1, miCol - 1)] else -1
        val prevU = if (availU) segmentIds[mi(miRow - 1, miCol)] else -1
        val prevL = if (availL) segmentIds[mi(miRow, miCol - 1)] else -1
        val pred = when {
            prevU == -1 -> if (prevL == -1) 0 else prevL
            prevL == -1 -> prevU
            else -> if (prevUL == prevU) prevU else prevL
        }
        if (skip != 0) return pred
        val ctx = when {
            prevUL < 0 -> 0
            prevUL == prevU && prevUL == prevL -> 2
            prevUL == prevU || prevUL == prevL || prevU == prevL -> 1
            else -> 0
        }
        val s = sd.symbol(cdf[C.SEGMENT_ID], ctx * 9, 8)
        return negDeinterleave(s, pred, fh.lastActiveSegId + 1)
    }

    private fun negDeinterleave(diff: Int, ref: Int, max: Int): Int {
        if (ref == 0) return diff
        if (ref >= max - 1) return max - diff - 1
        if (2 * ref < max) {
            if (diff <= 2 * ref) {
                return if (diff and 1 != 0) ref + ((diff + 1) shr 1) else ref - (diff shr 1)
            }
            return diff
        } else {
            if (diff <= 2 * (max - ref - 1)) {
                return if (diff and 1 != 0) ref + ((diff + 1) shr 1) else ref - (diff shr 1)
            }
            return max - (diff + 1)
        }
    }

    private fun segFeatureActive(feature: Int): Boolean = fh.segFeatureActiveIdx(segmentId, feature)

    private fun readSkip() {
        if (fh.segIdPreSkip && segFeatureActive(Av1.SEG_LVL_SKIP)) {
            skip = 1
        } else {
            var ctx = 0
            if (availU) ctx += skips[mi(miRow - 1, miCol)]
            if (availL) ctx += skips[mi(miRow, miCol - 1)]
            skip = sd.symbol(cdf[C.SKIP], ctx * 3, 2)
        }
    }

    private fun readCdef() {
        if (skip != 0 || fh.codedLossless || !seq.enableCdef || fh.allowIntrabc) return
        val cdefSize4 = 16
        val r = miRow and (cdefSize4 - 1).inv()
        val c = miCol and (cdefSize4 - 1).inv()
        if (cdefIdx[mi(r, c)] == -1) {
            val v = sd.literal(fh.cdefBits)
            cdefIdx[mi(r, c)] = v
            val w4 = Av1.num4x4Wide[miSize]
            val h4 = Av1.num4x4High[miSize]
            var i = r
            while (i < r + h4) {
                var j = c
                while (j < c + w4) {
                    cdefIdx[mi(i, j)] = v
                    j += cdefSize4
                }
                i += cdefSize4
            }
        }
    }

    private fun readDeltaQIndex() {
        val sbSize = if (sb128) Av1.BLOCK_128X128 else Av1.BLOCK_64X64
        if (miSize == sbSize && skip != 0) return
        if (readDeltas) {
            var deltaQAbs = sd.symbol(cdf[C.DELTA_Q], 0, Av1.DELTA_Q_SMALL + 1)
            if (deltaQAbs == Av1.DELTA_Q_SMALL) {
                val remBits = sd.literal(3) + 1
                val absBits = sd.literal(remBits)
                deltaQAbs = absBits + (1 shl remBits) + 1
            }
            if (deltaQAbs != 0) {
                val sign = sd.literal(1)
                val reduced = if (sign != 0) -deltaQAbs else deltaQAbs
                currentQIndex = (currentQIndex + (reduced shl fh.deltaQRes)).coerceIn(1, 255)
            }
        }
    }

    private fun readDeltaLf() {
        val sbSize = if (sb128) Av1.BLOCK_128X128 else Av1.BLOCK_64X64
        if (miSize == sbSize && skip != 0) return
        if (readDeltas && fh.deltaLfPresent) {
            val frameLfCount = if (fh.deltaLfMulti) (if (numPlanes > 1) Av1.FRAME_LF_COUNT else Av1.FRAME_LF_COUNT - 2) else 1
            for (i in 0 until frameLfCount) {
                val abs = if (fh.deltaLfMulti) sd.symbol(cdf[C.DELTA_LF_MULTI], i * 5, Av1.DELTA_LF_SMALL + 1)
                else sd.symbol(cdf[C.DELTA_LF], 0, Av1.DELTA_LF_SMALL + 1)
                val deltaLfAbs = if (abs == Av1.DELTA_LF_SMALL) {
                    val n = sd.literal(3) + 1
                    sd.literal(n) + (1 shl n) + 1
                } else abs
                if (deltaLfAbs != 0) {
                    val sign = sd.literal(1)
                    val reduced = if (sign != 0) -deltaLfAbs else deltaLfAbs
                    deltaLf[i] = (deltaLf[i] + (reduced shl fh.deltaLfRes)).coerceIn(-Av1.MAX_LOOP_FILTER, Av1.MAX_LOOP_FILTER)
                }
            }
        }
    }

    private fun intraAngleInfoY() {
        angleDeltaY = 0
        if (miSize >= Av1.BLOCK_8X8 && Av1.isDirectionalMode(yMode)) {
            angleDeltaY = sd.symbol(cdf[C.ANGLE_DELTA], (yMode - Av1.V_PRED) * 8, 7) - Av1.MAX_ANGLE_DELTA
        }
    }

    private fun intraAngleInfoUv() {
        angleDeltaUV = 0
        if (miSize >= Av1.BLOCK_8X8 && Av1.isDirectionalMode(uvMode)) {
            angleDeltaUV = sd.symbol(cdf[C.ANGLE_DELTA], (uvMode - Av1.V_PRED) * 8, 7) - Av1.MAX_ANGLE_DELTA
        }
    }

    private fun readCflAlphas() {
        val signs = sd.symbol(cdf[C.CFL_SIGN], 0, 8)
        val signU = (signs + 1) / 3
        val signV = (signs + 1) % 3
        if (signU != Av1.CFL_SIGN_ZERO) {
            val ctx = (signU - 1) * 3 + signV
            cflAlphaU = 1 + sd.symbol(cdf[C.CFL_ALPHA], ctx * 17, 16)
            if (signU == Av1.CFL_SIGN_NEG) cflAlphaU = -cflAlphaU
        } else {
            cflAlphaU = 0
        }
        if (signV != Av1.CFL_SIGN_ZERO) {
            val ctx = (signV - 1) * 3 + signU
            cflAlphaV = 1 + sd.symbol(cdf[C.CFL_ALPHA], ctx * 17, 16)
            if (signV == Av1.CFL_SIGN_NEG) cflAlphaV = -cflAlphaV
        } else {
            cflAlphaV = 0
        }
    }

    private fun filterIntraModeInfo() {
        useFilterIntra = false
        if (seq.enableFilterIntra && yMode == DC_PRED && paletteSizeY == 0 &&
            maxOf(Av1.blockWidth[miSize], Av1.blockHeight[miSize]) <= 32
        ) {
            useFilterIntra = sd.symbol(cdf[C.FILTER_INTRA], miSize * 3, 2) == 1
            if (useFilterIntra) filterIntraMode = sd.symbol(cdf[C.FILTER_INTRA_MODE], 0, 5)
        }
    }

    // ---- palette -----------------------------------------------------------------------

    private fun clip1(v: Int): Int = v.coerceIn(0, (1 shl bitDepth) - 1)

    private fun paletteModeInfo() {
        val bsizeCtx = Av1.miWidthLog2[miSize] + Av1.miHeightLog2[miSize] - 2
        if (yMode == DC_PRED) {
            var ctx = 0
            if (availU && paletteSizes[0][mi(miRow - 1, miCol)] > 0) ctx++
            if (availL && paletteSizes[0][mi(miRow, miCol - 1)] > 0) ctx++
            val hasPaletteY = sd.symbol(cdf[C.PALETTE_Y_MODE], (bsizeCtx * 3 + ctx) * 3, 2)
            if (hasPaletteY == 1) {
                paletteSizeY = sd.symbol(cdf[C.PALETTE_Y_SIZE], bsizeCtx * 8, 7) + 2
                readPaletteColors(0, paletteSizeY, paletteColorsY, deltaPlusOne = true)
            }
        }
        if (hasChroma && uvMode == DC_PRED) {
            val ctx = if (paletteSizeY > 0) 1 else 0
            val hasPaletteUv = sd.symbol(cdf[C.PALETTE_UV_MODE], ctx * 3, 2)
            if (hasPaletteUv == 1) {
                paletteSizeUV = sd.symbol(cdf[C.PALETTE_UV_SIZE], bsizeCtx * 8, 7) + 2
                readPaletteColors(1, paletteSizeUV, paletteColorsU, deltaPlusOne = false)
                if (sd.literal(1) == 1) {
                    val minBits = bitDepth - 4
                    val maxVal = 1 shl bitDepth
                    val paletteBits = minBits + sd.literal(2)
                    paletteColorsV[0] = sd.literal(bitDepth)
                    for (idx in 1 until paletteSizeUV) {
                        var delta = sd.literal(paletteBits)
                        if (delta != 0 && sd.literal(1) == 1) delta = -delta
                        var v = paletteColorsV[idx - 1] + delta
                        if (v < 0) v += maxVal
                        if (v >= maxVal) v -= maxVal
                        paletteColorsV[idx] = clip1(v)
                    }
                } else {
                    for (idx in 0 until paletteSizeUV) paletteColorsV[idx] = sd.literal(bitDepth)
                }
            }
        }
    }

    /**
     * The colors of a luma or U palette: those reused from the cache, then the first one
     * coded, then deltas. A luma delta counts from one, a U delta from zero, and their
     * remaining ranges differ by one in the same way.
     */
    private fun readPaletteColors(plane: Int, size: Int, colors: IntArray, deltaPlusOne: Boolean) {
        val cacheN = paletteCache(plane)
        var idx = 0
        var i = 0
        while (i < cacheN && idx < size) {
            if (sd.literal(1) == 1) colors[idx++] = paletteCache[i]
            i++
        }
        if (idx < size) colors[idx++] = sd.literal(bitDepth)
        var paletteBits = 0
        if (idx < size) {
            val minBits = bitDepth - 3
            paletteBits = minBits + sd.literal(2)
        }
        while (idx < size) {
            var delta = sd.literal(paletteBits)
            if (deltaPlusOne) delta++
            colors[idx] = clip1(colors[idx - 1] + delta)
            val range = (1 shl bitDepth) - colors[idx] - (if (deltaPlusOne) 1 else 0)
            paletteBits = minOf(paletteBits, Av1BitReader.ceilLog2(range))
            idx++
        }
        colors.sort(0, size)
    }

    private fun paletteCache(plane: Int): Int {
        val aboveN = if ((miRow * Av1.MI_SIZE) % 64 != 0) paletteSizes[plane][mi(miRow - 1, miCol)] else 0
        val leftN = if (availL) paletteSizes[plane][mi(miRow, miCol - 1)] else 0
        var aboveIdx = 0
        var leftIdx = 0
        var n = 0
        val aboveBase = if (aboveN > 0) mi(miRow - 1, miCol) * 8 else 0
        val leftBase = if (leftN > 0) mi(miRow, miCol - 1) * 8 else 0
        val colors = paletteColors[plane]
        while (aboveIdx < aboveN && leftIdx < leftN) {
            val aboveC = colors[aboveBase + aboveIdx]
            val leftC = colors[leftBase + leftIdx]
            if (leftC < aboveC) {
                if (n == 0 || leftC != paletteCache[n - 1]) paletteCache[n++] = leftC
                leftIdx++
            } else {
                if (n == 0 || aboveC != paletteCache[n - 1]) paletteCache[n++] = aboveC
                aboveIdx++
                if (leftC == aboveC) leftIdx++
            }
        }
        while (aboveIdx < aboveN) {
            val v = colors[aboveBase + aboveIdx++]
            if (n == 0 || v != paletteCache[n - 1]) paletteCache[n++] = v
        }
        while (leftIdx < leftN) {
            val v = colors[leftBase + leftIdx++]
            if (n == 0 || v != paletteCache[n - 1]) paletteCache[n++] = v
        }
        return n
    }

    private fun paletteTokens() {
        var blockHeight = Av1.blockHeight[miSize]
        var blockWidth = Av1.blockWidth[miSize]
        var onscreenHeight = minOf(blockHeight, (miRows - miRow) * Av1.MI_SIZE)
        var onscreenWidth = minOf(blockWidth, (miCols - miCol) * Av1.MI_SIZE)
        if (paletteSizeY != 0) {
            readColorMap(colorMapY, paletteSizeY, blockWidth, blockHeight, onscreenWidth, onscreenHeight, C.PALETTE_Y_COLOR)
        }
        if (paletteSizeUV != 0) {
            blockHeight = blockHeight shr subY
            blockWidth = blockWidth shr subX
            onscreenHeight = onscreenHeight shr subY
            onscreenWidth = onscreenWidth shr subX
            if (blockWidth < 4) {
                blockWidth += 2
                onscreenWidth += 2
            }
            if (blockHeight < 4) {
                blockHeight += 2
                onscreenHeight += 2
            }
            readColorMap(colorMapUV, paletteSizeUV, blockWidth, blockHeight, onscreenWidth, onscreenHeight, C.PALETTE_UV_COLOR)
        }
    }

    /** A palette color map, 64 samples a row, read in diagonal wavefront order (5.11.49). */
    private fun readColorMap(map: IntArray, n: Int, blockWidth: Int, blockHeight: Int, onscreenWidth: Int, onscreenHeight: Int, cdfBase: Int) {
        map[0] = sd.ns(n)
        val colorCdf = cdf[cdfBase + n - 2]
        for (i in 1 until onscreenHeight + onscreenWidth - 1) {
            var j = minOf(i, onscreenWidth - 1)
            while (j >= maxOf(0, i - onscreenHeight + 1)) {
                paletteColorContext(map, i - j, j, n)
                val ctx = Av1Tables.paletteColorContext[colorContextHash]
                val idx = sd.symbol(colorCdf, ctx * (n + 1), n)
                map[(i - j) * 64 + j] = colorOrder[idx]
                j--
            }
        }
        for (i in 0 until onscreenHeight) for (j in onscreenWidth until blockWidth) map[i * 64 + j] = map[i * 64 + onscreenWidth - 1]
        for (i in onscreenHeight until blockHeight) for (j in 0 until blockWidth) map[i * 64 + j] = map[(onscreenHeight - 1) * 64 + j]
    }

    private val scores = IntArray(8)

    private fun paletteColorContext(map: IntArray, r: Int, c: Int, n: Int) {
        for (i in 0 until Av1.PALETTE_COLORS) {
            scores[i] = 0
            colorOrder[i] = i
        }
        if (c > 0) scores[map[r * 64 + c - 1]] += 2
        if (r > 0 && c > 0) scores[map[(r - 1) * 64 + c - 1]] += 1
        if (r > 0) scores[map[(r - 1) * 64 + c]] += 2
        for (i in 0 until Av1.PALETTE_NUM_NEIGHBORS) {
            var maxScore = scores[i]
            var maxIdx = i
            for (j in i + 1 until n) {
                if (scores[j] > maxScore) {
                    maxScore = scores[j]
                    maxIdx = j
                }
            }
            if (maxIdx != i) {
                maxScore = scores[maxIdx]
                val maxColorOrder = colorOrder[maxIdx]
                for (k in maxIdx downTo i + 1) {
                    scores[k] = scores[k - 1]
                    colorOrder[k] = colorOrder[k - 1]
                }
                scores[i] = maxScore
                colorOrder[i] = maxColorOrder
            }
        }
        colorContextHash = 0
        for (i in 0 until Av1.PALETTE_NUM_NEIGHBORS) colorContextHash += scores[i] * Av1Tables.paletteColorHashMultipliers[i]
    }

    // ---- transform sizes ---------------------------------------------------------------

    private fun readBlockTxSize() {
        val bw4 = Av1.num4x4Wide[miSize]
        val bh4 = Av1.num4x4High[miSize]
        if (fh.txMode == Av1FrameHeader.TX_MODE_SELECT && miSize > Av1.BLOCK_4X4 && isInter && skip == 0 && !lossless) {
            val maxTxSz = Av1Tables.maxTxSizeRect[miSize]
            val txW4 = Av1.txWidth[maxTxSz] / Av1.MI_SIZE
            val txH4 = Av1.txHeight[maxTxSz] / Av1.MI_SIZE
            var row = miRow
            while (row < miRow + bh4) {
                var col = miCol
                while (col < miCol + bw4) {
                    readVarTxSize(row, col, maxTxSz, 0)
                    col += txW4
                }
                row += txH4
            }
        } else {
            readTxSize(skip == 0 || !isInter)
            for (row in miRow until miRow + bh4) for (col in miCol until miCol + bw4) interTxSizes[mi(row, col)] = txSize
        }
    }

    private fun readTxSize(allowSelect: Boolean) {
        if (lossless) {
            txSize = TX_4X4
            return
        }
        val maxRectTxSize = Av1Tables.maxTxSizeRect[miSize]
        val maxTxDepth = Av1Tables.maxTxDepth[miSize]
        txSize = maxRectTxSize
        if (miSize > Av1.BLOCK_4X4 && allowSelect && fh.txMode == Av1FrameHeader.TX_MODE_SELECT) {
            val maxTxWidth = Av1.txWidth[maxRectTxSize]
            val maxTxHeight = Av1.txHeight[maxRectTxSize]
            val aboveW = when {
                availU && isInters[mi(miRow - 1, miCol)] -> Av1.blockWidth[miSizes[mi(miRow - 1, miCol)]]
                availU -> aboveTxWidth(miRow, miCol)
                else -> 0
            }
            val leftH = when {
                availL && isInters[mi(miRow, miCol - 1)] -> Av1.blockHeight[miSizes[mi(miRow, miCol - 1)]]
                availL -> leftTxHeight(miRow, miCol)
                else -> 0
            }
            val ctx = (if (aboveW >= maxTxWidth) 1 else 0) + (if (leftH >= maxTxHeight) 1 else 0)
            val txDepth = when (maxTxDepth) {
                4 -> sd.symbol(cdf[C.TX_64X64], ctx * 4, 3)
                3 -> sd.symbol(cdf[C.TX_32X32], ctx * 4, 3)
                2 -> sd.symbol(cdf[C.TX_16X16], ctx * 4, 3)
                else -> sd.symbol(cdf[C.TX_8X8], ctx * 3, 2)
            }
            for (i in 0 until txDepth) txSize = Av1Tables.splitTxSize[txSize]
        }
    }

    private fun readVarTxSize(row: Int, col: Int, txSz: Int, depth: Int) {
        if (row >= miRows || col >= miCols) return
        val split = if (txSz == TX_4X4 || depth == Av1.MAX_VARTX_DEPTH) false else {
            val above = if (aboveTxWidth(row, col) < Av1.txWidth[txSz]) 1 else 0
            val left = if (leftTxHeight(row, col) < Av1.txHeight[txSz]) 1 else 0
            val size = minOf(64, maxOf(Av1.blockWidth[miSize], Av1.blockHeight[miSize]))
            val maxTxSz = Av1.findTxSize(size, size)
            val txSzSqrUp = Av1Tables.txSizeSqrUp[txSz]
            val ctx = (if (txSzSqrUp != maxTxSz) 1 else 0) * 3 + (Av1.TX_SIZES - 1 - maxTxSz) * 6 + above + left
            sd.symbol(cdf[C.TXFM_SPLIT], ctx * 3, 2) == 1
        }
        val w4 = Av1.txWidth[txSz] / Av1.MI_SIZE
        val h4 = Av1.txHeight[txSz] / Av1.MI_SIZE
        if (split) {
            val subTxSz = Av1Tables.splitTxSize[txSz]
            val stepW = Av1.txWidth[subTxSz] / Av1.MI_SIZE
            val stepH = Av1.txHeight[subTxSz] / Av1.MI_SIZE
            var i = 0
            while (i < h4) {
                var j = 0
                while (j < w4) {
                    readVarTxSize(row + i, col + j, subTxSz, depth + 1)
                    j += stepW
                }
                i += stepH
            }
        } else {
            for (i in 0 until h4) for (j in 0 until w4) {
                if (row + i < miRowsAligned && col + j < miStride) interTxSizes[mi(row + i, col + j)] = txSz
            }
            txSize = txSz
        }
    }

    private fun aboveTxWidth(row: Int, col: Int): Int {
        if (row == miRow) {
            if (!availU) return 64
            val m = mi(row - 1, col)
            if (skips[m] != 0 && isInters[m]) return Av1.blockWidth[miSizes[m]]
        }
        return Av1.txWidth[interTxSizes[mi(row - 1, col)]]
    }

    private fun leftTxHeight(row: Int, col: Int): Int {
        if (col == miCol) {
            if (!availL) return 64
            val m = mi(row, col - 1)
            if (skips[m] != 0 && isInters[m]) return Av1.blockHeight[miSizes[m]]
        }
        return Av1.txHeight[interTxSizes[mi(row, col - 1)]]
    }

    // ---- prediction and residual ------------------------------------------------------

    private fun computePrediction() {
        if (!isInter) return
        // Intra block copy: the whole block predicted from earlier parts of this frame.
        for (plane in 0 until 1 + (if (hasChroma) 2 else 0)) Av1IntraBlockCopy.predict(this, plane)
    }

    private fun residual() {
        val widthChunks = maxOf(1, Av1.blockWidth[miSize] shr 6)
        val heightChunks = maxOf(1, Av1.blockHeight[miSize] shr 6)
        val miSizeChunk = if (widthChunks > 1 || heightChunks > 1) Av1.BLOCK_64X64 else miSize
        for (chunkY in 0 until heightChunks) for (chunkX in 0 until widthChunks) {
            val miRowChunk = miRow + (chunkY shl 4)
            val miColChunk = miCol + (chunkX shl 4)
            for (plane in 0 until 1 + (if (hasChroma) 2 else 0)) {
                val txSz = if (lossless) TX_4X4 else txSizeFor(plane, txSize)
                val stepX = Av1.txWidth[txSz] shr 2
                val stepY = Av1.txHeight[txSz] shr 2
                val planeSz = Av1.subsampledSize(miSizeChunk, if (plane > 0) subX else 0, if (plane > 0) subY else 0)
                val num4x4W = Av1.num4x4Wide[planeSz]
                val num4x4H = Av1.num4x4High[planeSz]
                val sx = if (plane > 0) subX else 0
                val sy = if (plane > 0) subY else 0
                val baseX = (miColChunk shr sx) * Av1.MI_SIZE
                val baseY = (miRowChunk shr sy) * Av1.MI_SIZE
                if (isInter && !lossless && plane == 0) {
                    transformTree(baseX, baseY, num4x4W * 4, num4x4H * 4)
                } else {
                    val baseXBlock = (miCol shr sx) * Av1.MI_SIZE
                    val baseYBlock = (miRow shr sy) * Av1.MI_SIZE
                    var y = 0
                    while (y < num4x4H) {
                        var x = 0
                        while (x < num4x4W) {
                            transformBlock(plane, baseXBlock, baseYBlock, txSz, x + ((chunkX shl 4) shr sx), y + ((chunkY shl 4) shr sy))
                            x += stepX
                        }
                        y += stepY
                    }
                }
            }
        }
    }

    private fun transformTree(startX: Int, startY: Int, w: Int, h: Int) {
        val maxX = miCols * Av1.MI_SIZE
        val maxY = miRows * Av1.MI_SIZE
        if (startX >= maxX || startY >= maxY) return
        val row = startY shr Av1.MI_SIZE_LOG2
        val col = startX shr Av1.MI_SIZE_LOG2
        val lumaTxSz = interTxSizes[mi(row, col)]
        val lumaW = Av1.txWidth[lumaTxSz]
        val lumaH = Av1.txHeight[lumaTxSz]
        if (w <= lumaW && h <= lumaH) {
            transformBlock(0, startX, startY, Av1.findTxSize(w, h), 0, 0)
        } else if (w > h) {
            transformTree(startX, startY, w / 2, h)
            transformTree(startX + w / 2, startY, w / 2, h)
        } else if (w < h) {
            transformTree(startX, startY, w, h / 2)
            transformTree(startX, startY + h / 2, w, h / 2)
        } else {
            transformTree(startX, startY, w / 2, h / 2)
            transformTree(startX + w / 2, startY, w / 2, h / 2)
            transformTree(startX, startY + h / 2, w / 2, h / 2)
            transformTree(startX + w / 2, startY + h / 2, w / 2, h / 2)
        }
    }

    /** `get_tx_size( plane, txSz )`. */
    fun txSizeFor(plane: Int, txSz: Int): Int {
        if (plane == 0) return txSz
        val uvTx = Av1Tables.maxTxSizeRect[Av1.subsampledSize(miSize, subX, subY)]
        if (Av1.txWidth[uvTx] == 64 || Av1.txHeight[uvTx] == 64) {
            if (Av1.txWidth[uvTx] == 16) return Av1.TX_16X32
            if (Av1.txHeight[uvTx] == 16) return Av1.TX_32X16
            return Av1.TX_32X32
        }
        return uvTx
    }

    private fun transformBlock(plane: Int, baseX: Int, baseY: Int, txSz: Int, x: Int, y: Int) {
        val startX = baseX + 4 * x
        val startY = baseY + 4 * y
        val sx = if (plane > 0) subX else 0
        val sy = if (plane > 0) subY else 0
        val row = (startY shl sy) shr Av1.MI_SIZE_LOG2
        val col = (startX shl sx) shr Av1.MI_SIZE_LOG2
        val subBlockMiRow = row and sbMask
        val subBlockMiCol = col and sbMask
        val stepX = Av1.txWidth[txSz] shr Av1.MI_SIZE_LOG2
        val stepY = Av1.txHeight[txSz] shr Av1.MI_SIZE_LOG2
        val maxX = (miCols * Av1.MI_SIZE) shr sx
        val maxY = (miRows * Av1.MI_SIZE) shr sy
        if (startX >= maxX || startY >= maxY) return
        if (!isInter) {
            if ((plane == 0 && paletteSizeY != 0) || (plane != 0 && paletteSizeUV != 0)) {
                Av1Intra.predictPalette(this, plane, startX, startY, x, y, txSz)
            } else {
                val isCfl = plane > 0 && uvMode == UV_CFL_PRED
                val mode = if (plane == 0) yMode else if (isCfl) DC_PRED else uvMode
                val log2W = Av1.txWidthLog2[txSz]
                val log2H = Av1.txHeightLog2[txSz]
                Av1Intra.predictIntra(
                    this, plane, startX, startY,
                    (if (plane == 0) availL else availLChroma) || x > 0,
                    (if (plane == 0) availU else availUChroma) || y > 0,
                    blockDecodedAt(plane, (subBlockMiRow shr sy) - 1, (subBlockMiCol shr sx) + stepX),
                    blockDecodedAt(plane, (subBlockMiRow shr sy) + stepY, (subBlockMiCol shr sx) - 1),
                    mode, log2W, log2H,
                )
                if (isCfl) Av1Intra.predictChromaFromLuma(this, plane, startX, startY, txSz)
            }
            if (plane == 0) {
                maxLumaW = startX + stepX * 4
                maxLumaH = startY + stepY * 4
            }
        }
        if (skip == 0) {
            val eob = coeffs(plane, startX, startY, txSz)
            if (eob > 0) transform.reconstruct(this, plane, startX, startY, txSz)
        }
        for (i in 0 until stepY) for (j in 0 until stepX) {
            val lr = (row shr sy) + i
            val lc = (col shr sx) + j
            if (lr < miRowsAligned && lc < miStride) loopfilterTxSizes[plane][lr * miStride + lc] = txSz
            blockDecoded[plane][((subBlockMiRow shr sy) + i + 1) * 34 + (subBlockMiCol shr sx) + j + 1] = true
        }
    }

    // ---- coefficients --------------------------------------------------------------------

    /** `get_tx_set( txSz )`. */
    fun txSet(txSz: Int): Int {
        val txSzSqr = Av1Tables.txSizeSqr[txSz]
        val txSzSqrUp = Av1Tables.txSizeSqrUp[txSz]
        if (txSzSqrUp > Av1.TX_32X32) return Av1.TX_SET_DCTONLY
        return if (isInter) {
            when {
                fh.reducedTxSet || txSzSqrUp == Av1.TX_32X32 -> Av1.TX_SET_INTER_3
                txSzSqr == Av1.TX_16X16 -> Av1.TX_SET_INTER_2
                else -> Av1.TX_SET_INTER_1
            }
        } else {
            when {
                txSzSqrUp == Av1.TX_32X32 -> Av1.TX_SET_DCTONLY
                fh.reducedTxSet -> Av1.TX_SET_INTRA_2
                txSzSqr == Av1.TX_16X16 -> Av1.TX_SET_INTRA_2
                else -> Av1.TX_SET_INTRA_1
            }
        }
    }

    private fun isTxTypeInSet(txSet: Int, txType: Int): Boolean =
        if (isInter) Av1Tables.txTypeInSetInter[txSet * 16 + txType] == 1 else Av1Tables.txTypeInSetIntra[txSet * 16 + txType] == 1

    /** `compute_tx_type( plane, txSz, blockX, blockY )`. */
    fun computeTxType(plane: Int, txSz: Int, blockX: Int, blockY: Int): Int {
        val txSzSqrUp = Av1Tables.txSizeSqrUp[txSz]
        if (lossless || txSzSqrUp > Av1.TX_32X32) return Av1.DCT_DCT
        val txSet = txSet(txSz)
        if (plane == 0) return txTypes[mi(blockY, blockX)]
        if (isInter) {
            val x4 = maxOf(miCol, blockX shl subX)
            val y4 = maxOf(miRow, blockY shl subY)
            val txType = txTypes[mi(y4, x4)]
            return if (!isTxTypeInSet(txSet, txType)) Av1.DCT_DCT else txType
        }
        val txType = Av1Tables.modeToTxfm[uvMode]
        return if (!isTxTypeInSet(txSet, txType)) Av1.DCT_DCT else txType
    }

    private fun transformType(x4: Int, y4: Int, txSz: Int) {
        val set = txSet(txSz)
        val qIdx = if (fh.segmentationEnabled) fh.qIndex(true, segmentId, currentQIndex) else fh.baseQIdx
        val txType = if (set > 0 && qIdx > 0) {
            if (isInter) {
                when (set) {
                    Av1.TX_SET_INTER_1 -> Av1Tables.txTypeInterInvSet1[sd.symbol(cdf[C.INTER_TX_TYPE_SET1], Av1Tables.txSizeSqr[txSz] * 17, 16)]
                    Av1.TX_SET_INTER_2 -> Av1Tables.txTypeInterInvSet2[sd.symbol(cdf[C.INTER_TX_TYPE_SET2], 0, 12)]
                    else -> Av1Tables.txTypeInterInvSet3[sd.symbol(cdf[C.INTER_TX_TYPE_SET3], Av1Tables.txSizeSqr[txSz] * 3, 2)]
                }
            } else {
                val intraDir = if (useFilterIntra) Av1Tables.filterIntraModeToIntraDir[filterIntraMode] else yMode
                if (set == Av1.TX_SET_INTRA_1) {
                    Av1Tables.txTypeIntraInvSet1[sd.symbol(cdf[C.INTRA_TX_TYPE_SET1], (Av1Tables.txSizeSqr[txSz] * 13 + intraDir) * 8, 7)]
                } else {
                    Av1Tables.txTypeIntraInvSet2[sd.symbol(cdf[C.INTRA_TX_TYPE_SET2], (Av1Tables.txSizeSqr[txSz] * 13 + intraDir) * 6, 5)]
                }
            }
        } else Av1.DCT_DCT
        for (i in 0 until (Av1.txWidth[txSz] shr 2)) for (j in 0 until (Av1.txHeight[txSz] shr 2)) {
            txTypes[mi(y4 + j, x4 + i)] = txType
        }
    }

    /** The scan order for [txSz] with the current PlaneTxType (`get_scan`). */
    private fun scan(txSz: Int): IntArray {
        val t = Av1Tables
        if (txSz == Av1.TX_16X64) return t.defaultScan16x32
        if (txSz == Av1.TX_64X16) return t.defaultScan32x16
        if (t.txSizeSqrUp[txSz] == Av1.TX_64X64) return t.defaultScan32x32
        val type = planeTxType
        val preferRow = type == Av1.V_DCT || type == Av1.V_ADST || type == Av1.V_FLIPADST
        val preferCol = type == Av1.H_DCT || type == Av1.H_ADST || type == Av1.H_FLIPADST
        if (type != Av1.IDTX && preferRow) {
            return when (txSz) {
                TX_4X4 -> t.mrowScan4x4
                5 -> t.mrowScan4x8
                6 -> t.mrowScan8x4
                Av1.TX_8X8 -> t.mrowScan8x8
                7 -> t.mrowScan8x16
                8 -> t.mrowScan16x8
                Av1.TX_16X16 -> t.mrowScan16x16
                13 -> t.mrowScan4x16
                else -> t.mrowScan16x4
            }
        }
        if (type != Av1.IDTX && preferCol) {
            return when (txSz) {
                TX_4X4 -> t.mcolScan4x4
                5 -> t.mcolScan4x8
                6 -> t.mcolScan8x4
                Av1.TX_8X8 -> t.mcolScan8x8
                7 -> t.mcolScan8x16
                8 -> t.mcolScan16x8
                Av1.TX_16X16 -> t.mcolScan16x16
                13 -> t.mcolScan4x16
                else -> t.mcolScan16x4
            }
        }
        return when (txSz) {
            TX_4X4 -> t.defaultScan4x4
            5 -> t.defaultScan4x8
            6 -> t.defaultScan8x4
            Av1.TX_8X8 -> t.defaultScan8x8
            7 -> t.defaultScan8x16
            8 -> t.defaultScan16x8
            Av1.TX_16X16 -> t.defaultScan16x16
            Av1.TX_16X32 -> t.defaultScan16x32
            Av1.TX_32X16 -> t.defaultScan32x16
            13 -> t.defaultScan4x16
            14 -> t.defaultScan16x4
            15 -> t.defaultScan8x32
            16 -> t.defaultScan32x8
            else -> t.defaultScan32x32
        }
    }

    private fun coeffs(plane: Int, startX: Int, startY: Int, txSz: Int): Int {
        val x4 = startX shr 2
        val y4 = startY shr 2
        val w4 = Av1.txWidth[txSz] shr 2
        val h4 = Av1.txHeight[txSz] shr 2
        val txSzCtx = (Av1Tables.txSizeSqr[txSz] + Av1Tables.txSizeSqrUp[txSz] + 1) shr 1
        val ptype = if (plane > 0) 1 else 0
        val segEob = if (txSz == Av1.TX_16X64 || txSz == Av1.TX_64X16) 512 else minOf(1024, Av1.txWidth[txSz] * Av1.txHeight[txSz])
        for (c in 0 until segEob) quant[c] = 0
        var eob = 0
        var culLevel = 0
        var dcCategory = 0
        val allZero = sd.symbol(cdf[C.TXB_SKIP], (txSzCtx * 13 + allZeroCtx(plane, txSz, x4, y4, w4, h4)) * 3, 2)
        if (allZero == 1) {
            if (plane == 0) for (i in 0 until w4) for (j in 0 until h4) txTypes[mi(y4 + j, x4 + i)] = Av1.DCT_DCT
        } else {
            if (plane == 0) transformType(x4, y4, txSz)
            planeTxType = computeTxType(plane, txSz, x4, y4)
            val scan = scan(txSz)
            val eobMultisize = minOf(Av1.txWidthLog2[txSz], 5) + minOf(Av1.txHeightLog2[txSz], 5) - 4
            val classCtx = if (Av1.txClass(planeTxType) == Av1.TX_CLASS_2D) 0 else 1
            val eobPt = 1 + when (eobMultisize) {
                0 -> sd.symbol(cdf[C.EOB_PT_16], (ptype * 2 + classCtx) * 6, 5)
                1 -> sd.symbol(cdf[C.EOB_PT_32], (ptype * 2 + classCtx) * 7, 6)
                2 -> sd.symbol(cdf[C.EOB_PT_64], (ptype * 2 + classCtx) * 8, 7)
                3 -> sd.symbol(cdf[C.EOB_PT_128], (ptype * 2 + classCtx) * 9, 8)
                4 -> sd.symbol(cdf[C.EOB_PT_256], (ptype * 2 + classCtx) * 10, 9)
                5 -> sd.symbol(cdf[C.EOB_PT_512], ptype * 11, 10)
                else -> sd.symbol(cdf[C.EOB_PT_1024], ptype * 12, 11)
            }
            eob = if (eobPt < 2) eobPt else (1 shl (eobPt - 2)) + 1
            var eobShift = maxOf(-1, eobPt - 3)
            if (eobShift >= 0) {
                val eobExtra = sd.symbol(cdf[C.EOB_EXTRA], ((txSzCtx * 2 + ptype) * 9 + eobPt - 3) * 3, 2)
                if (eobExtra == 1) eob += 1 shl eobShift
                for (i in 1 until maxOf(0, eobPt - 2)) {
                    eobShift = maxOf(0, eobPt - 2) - 1 - i
                    if (sd.literal(1) == 1) eob += 1 shl eobShift
                }
            }
            val brCtxSize = minOf(txSzCtx, Av1.TX_32X32)
            for (c in eob - 1 downTo 0) {
                val pos = scan[c]
                var level: Int
                if (c == eob - 1) {
                    val ctx = coeffBaseCtx(txSz, plane, x4, y4, pos, c, true) - Av1.SIG_COEF_CONTEXTS + Av1.SIG_COEF_CONTEXTS_EOB
                    level = sd.symbol(cdf[C.COEFF_BASE_EOB], ((txSzCtx * 2 + ptype) * 4 + ctx) * 4, 3) + 1
                } else {
                    val ctx = coeffBaseCtx(txSz, plane, x4, y4, pos, c, false)
                    level = sd.symbol(cdf[C.COEFF_BASE], ((txSzCtx * 2 + ptype) * 42 + ctx) * 5, 4)
                }
                if (level > Av1.NUM_BASE_LEVELS) {
                    val ctx = coeffBrCtx(txSz, pos)
                    for (idx in 0 until Av1.COEFF_BASE_RANGE / (Av1.BR_CDF_SIZE - 1)) {
                        val br = sd.symbol(cdf[C.COEFF_BR], ((brCtxSize * 2 + ptype) * 21 + ctx) * 5, 4)
                        level += br
                        if (br < Av1.BR_CDF_SIZE - 1) break
                    }
                }
                quant[pos] = level
            }
            for (c in 0 until eob) {
                val pos = scan[c]
                var sign = 0
                if (quant[pos] != 0) {
                    sign = if (c == 0) sd.symbol(cdf[C.DC_SIGN], (ptype * 3 + dcSignCtx(plane, x4, y4, w4, h4)) * 3, 2)
                    else sd.literal(1)
                }
                if (quant[pos] > Av1.NUM_BASE_LEVELS + Av1.COEFF_BASE_RANGE) {
                    var length = 0
                    do {
                        length++
                        val golombLengthBit = sd.literal(1)
                        if (length > 32) throw ImageDecodeException("AV1: a coefficient's Golomb code runs past 32 bits")
                    } while (golombLengthBit == 0)
                    var x = 1
                    for (i in length - 2 downTo 0) x = (x shl 1) or sd.literal(1)
                    quant[pos] = x + Av1.COEFF_BASE_RANGE + Av1.NUM_BASE_LEVELS
                }
                if (pos == 0 && quant[pos] > 0) dcCategory = if (sign != 0) 1 else 2
                quant[pos] = quant[pos] and 0xFFFFF
                culLevel += quant[pos]
                if (sign != 0) quant[pos] = -quant[pos]
            }
            culLevel = minOf(63, culLevel)
        }
        for (i in 0 until w4) {
            aboveLevelContext[plane][x4 + i] = culLevel
            aboveDcContext[plane][x4 + i] = dcCategory
        }
        for (i in 0 until h4) {
            leftLevelContext[plane][y4 + i] = culLevel
            leftDcContext[plane][y4 + i] = dcCategory
        }
        return eob
    }

    private fun allZeroCtx(plane: Int, txSz: Int, x4: Int, y4: Int, w4: Int, h4: Int): Int {
        var maxX4 = miCols
        var maxY4 = miRows
        if (plane > 0) {
            maxX4 = maxX4 shr subX
            maxY4 = maxY4 shr subY
        }
        val w = Av1.txWidth[txSz]
        val h = Av1.txHeight[txSz]
        val bsize = Av1.subsampledSize(miSize, if (plane > 0) subX else 0, if (plane > 0) subY else 0)
        val bw = Av1.blockWidth[bsize]
        val bh = Av1.blockHeight[bsize]
        if (plane == 0) {
            var top = 0
            var left = 0
            for (k in 0 until w4) if (x4 + k < maxX4) top = maxOf(top, aboveLevelContext[plane][x4 + k])
            for (k in 0 until h4) if (y4 + k < maxY4) left = maxOf(left, leftLevelContext[plane][y4 + k])
            top = minOf(top, 255)
            left = minOf(left, 255)
            return when {
                bw == w && bh == h -> 0
                top == 0 && left == 0 -> 1
                top == 0 || left == 0 -> 2 + (if (maxOf(top, left) > 3) 1 else 0)
                maxOf(top, left) <= 3 -> 4
                minOf(top, left) <= 3 -> 5
                else -> 6
            }
        }
        var above = 0
        var left = 0
        for (i in 0 until w4) if (x4 + i < maxX4) above = above or aboveLevelContext[plane][x4 + i] or aboveDcContext[plane][x4 + i]
        for (i in 0 until h4) if (y4 + i < maxY4) left = left or leftLevelContext[plane][y4 + i] or leftDcContext[plane][y4 + i]
        var ctx = (if (above != 0) 1 else 0) + (if (left != 0) 1 else 0)
        ctx += 7
        if (bw * bh > w * h) ctx += 3
        return ctx
    }

    private fun coeffBaseCtx(txSz: Int, plane: Int, blockX: Int, blockY: Int, pos: Int, c: Int, isEob: Boolean): Int {
        val adjTxSz = Av1Tables.adjustedTxSize[txSz]
        val bwl = Av1.txWidthLog2[adjTxSz]
        val width = 1 shl bwl
        val height = Av1.txHeight[adjTxSz]
        if (isEob) {
            if (c == 0) return Av1.SIG_COEF_CONTEXTS - 4
            if (c <= (height shl bwl) / 8) return Av1.SIG_COEF_CONTEXTS - 3
            if (c <= (height shl bwl) / 4) return Av1.SIG_COEF_CONTEXTS - 2
            return Av1.SIG_COEF_CONTEXTS - 1
        }
        val txClass = Av1.txClass(planeTxType)
        val row = pos shr bwl
        val col = pos - (row shl bwl)
        var mag = 0
        val offsets = Av1Tables.sigRefDiffOffset
        for (idx in 0 until Av1.SIG_REF_DIFF_OFFSET_NUM) {
            val refRow = row + offsets[(txClass * 5 + idx) * 2]
            val refCol = col + offsets[(txClass * 5 + idx) * 2 + 1]
            if (refRow >= 0 && refCol >= 0 && refRow < height && refCol < width) {
                mag += minOf(kotlin.math.abs(quant[(refRow shl bwl) + refCol]), 3)
            }
        }
        val ctx = minOf((mag + 1) shr 1, 4)
        if (txClass == Av1.TX_CLASS_2D) {
            if (row == 0 && col == 0) return 0
            return ctx + Av1Tables.coeffBaseCtxOffset[txSz * 25 + minOf(row, 4) * 5 + minOf(col, 4)]
        }
        val idx = if (txClass == Av1.TX_CLASS_VERT) row else col
        return ctx + Av1Tables.coeffBasePosCtxOffset[minOf(idx, 2)]
    }

    private fun coeffBrCtx(txSz: Int, pos: Int): Int {
        val adjTxSz = Av1Tables.adjustedTxSize[txSz]
        val bwl = Av1.txWidthLog2[adjTxSz]
        val txw = Av1.txWidth[adjTxSz]
        val txh = Av1.txHeight[adjTxSz]
        val row = pos shr bwl
        val col = pos - (row shl bwl)
        var mag = 0
        val txClass = Av1.txClass(planeTxType)
        val offsets = Av1Tables.magRefOffsetWithTxClass
        for (idx in 0 until 3) {
            val refRow = row + offsets[(txClass * 3 + idx) * 2]
            val refCol = col + offsets[(txClass * 3 + idx) * 2 + 1]
            if (refRow >= 0 && refCol >= 0 && refRow < txh && refCol < (1 shl bwl)) {
                mag += minOf(quant[refRow * txw + refCol], Av1.COEFF_BASE_RANGE + Av1.NUM_BASE_LEVELS + 1)
            }
        }
        mag = minOf((mag + 1) shr 1, 6)
        return when {
            pos == 0 -> mag
            txClass == 0 -> if (row < 2 && col < 2) mag + 7 else mag + 14
            txClass == 1 -> if (col == 0) mag + 7 else mag + 14
            else -> if (row == 0) mag + 7 else mag + 14
        }
    }

    private fun dcSignCtx(plane: Int, x4: Int, y4: Int, w4: Int, h4: Int): Int {
        var maxX4 = miCols
        var maxY4 = miRows
        if (plane > 0) {
            maxX4 = maxX4 shr subX
            maxY4 = maxY4 shr subY
        }
        var dcSign = 0
        for (k in 0 until w4) {
            if (x4 + k < maxX4) {
                when (aboveDcContext[plane][x4 + k]) {
                    1 -> dcSign--
                    2 -> dcSign++
                }
            }
        }
        for (k in 0 until h4) {
            if (y4 + k < maxY4) {
                when (leftDcContext[plane][y4 + k]) {
                    1 -> dcSign--
                    2 -> dcSign++
                }
            }
        }
        return when {
            dcSign < 0 -> 1
            dcSign > 0 -> 2
            else -> 0
        }
    }

    // ---- loop restoration units ----------------------------------------------------------

    private fun readLr(r: Int, c: Int, bSize: Int) {
        if (fh.allowIntrabc) return
        val w = Av1.num4x4Wide[bSize]
        val h = Av1.num4x4High[bSize]
        for (plane in 0 until numPlanes) {
            if (fh.frameRestorationType[plane] == Av1FrameHeader.RESTORE_NONE) continue
            val sx = if (plane == 0) 0 else subX
            val sy = if (plane == 0) 0 else subY
            val unitSize = fh.loopRestorationSize[plane]
            val unitRows = lrUnitRows[plane]
            val unitCols = lrUnitCols[plane]
            val unitRowStart = (r * (Av1.MI_SIZE shr sy) + unitSize - 1) / unitSize
            val unitRowEnd = minOf(unitRows, ((r + h) * (Av1.MI_SIZE shr sy) + unitSize - 1) / unitSize)
            val numerator: Int
            val denominator: Int
            if (fh.useSuperres) {
                numerator = (Av1.MI_SIZE shr sx) * fh.superresDenom
                denominator = unitSize * Av1FrameHeader.SUPERRES_NUM
            } else {
                numerator = Av1.MI_SIZE shr sx
                denominator = unitSize
            }
            val unitColStart = (c * numerator + denominator - 1) / denominator
            val unitColEnd = minOf(unitCols, ((c + w) * numerator + denominator - 1) / denominator)
            for (unitRow in unitRowStart until unitRowEnd) for (unitCol in unitColStart until unitColEnd) {
                readLrUnit(plane, unitRow, unitCol)
            }
        }
    }

    private fun readLrUnit(plane: Int, unitRow: Int, unitCol: Int) {
        val u = unitRow * lrUnitCols[plane] + unitCol
        val type = when (fh.frameRestorationType[plane]) {
            Av1FrameHeader.RESTORE_WIENER -> if (sd.symbol(cdf[C.USE_WIENER], 0, 2) == 1) Av1FrameHeader.RESTORE_WIENER else Av1FrameHeader.RESTORE_NONE
            Av1FrameHeader.RESTORE_SGRPROJ -> if (sd.symbol(cdf[C.USE_SGRPROJ], 0, 2) == 1) Av1FrameHeader.RESTORE_SGRPROJ else Av1FrameHeader.RESTORE_NONE
            else -> sd.symbol(cdf[C.RESTORATION_TYPE], 0, 3)
        }
        lrType[plane]!![u] = type
        if (type == Av1FrameHeader.RESTORE_WIENER) {
            for (pass in 0 until 2) {
                val firstCoeff: Int
                if (plane != 0) {
                    firstCoeff = 1
                    lrWiener[plane]!![u * 6 + pass * 3] = 0
                } else {
                    firstCoeff = 0
                }
                for (j in firstCoeff until 3) {
                    val min = Av1Tables.wienerTapsMin[j]
                    val max = Av1Tables.wienerTapsMax[j]
                    val k = Av1Tables.wienerTapsK[j]
                    val v = decodeSignedSubexpWithRefBool(min, max + 1, k, refLrWiener[plane][pass][j])
                    lrWiener[plane]!![u * 6 + pass * 3 + j] = v
                    refLrWiener[plane][pass][j] = v
                }
            }
        } else if (type == Av1FrameHeader.RESTORE_SGRPROJ) {
            val set = sd.literal(4)
            lrSgrSet[plane]!![u] = set
            for (i in 0 until 2) {
                val radius = Av1Tables.sgrParams[set * 4 + i * 2]
                val min = Av1Tables.sgrprojXqdMin[i]
                val max = Av1Tables.sgrprojXqdMax[i]
                var v: Int
                if (radius != 0) {
                    v = decodeSignedSubexpWithRefBool(min, max + 1, 4, refSgrXqd[plane][i])
                } else {
                    v = 0
                    if (i == 1) v = ((1 shl 7) - refSgrXqd[plane][0]).coerceIn(min, max)
                }
                lrSgrXqd[plane]!![u * 2 + i] = v
                refSgrXqd[plane][i] = v
            }
        }
    }

    private fun decodeSignedSubexpWithRefBool(low: Int, high: Int, k: Int, r: Int): Int =
        decodeUnsignedSubexpWithRefBool(high - low, k, r - low) + low

    private fun decodeUnsignedSubexpWithRefBool(mx: Int, k: Int, r: Int): Int {
        val v = decodeSubexpBool(mx, k)
        return if ((r shl 1) <= mx) inverseRecenter(r, v) else mx - 1 - inverseRecenter(mx - 1 - r, v)
    }

    private fun decodeSubexpBool(numSyms: Int, k: Int): Int {
        var i = 0
        var mk = 0
        while (true) {
            val b2 = if (i != 0) k + i - 1 else k
            val a = 1 shl b2
            if (numSyms <= mk + 3 * a) return sd.ns(numSyms - mk) + mk
            if (sd.literal(1) == 1) {
                i++
                mk += a
            } else {
                return sd.literal(b2) + mk
            }
        }
    }

    private fun inverseRecenter(r: Int, v: Int): Int = when {
        v > 2 * r -> v
        v and 1 != 0 -> r - ((v + 1) shr 1)
        else -> r + (v shr 1)
    }
}
