package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

/** Film grain synthesis parameters (specification section 5.9.30); all zero means no grain. */
internal class Av1FilmGrain {
    var applyGrain = false
    var grainSeed = 0
    var updateGrain = false
    var numYPoints = 0
    val pointYValue = IntArray(16)
    val pointYScaling = IntArray(16)
    var chromaScalingFromLuma = false
    var numCbPoints = 0
    val pointCbValue = IntArray(16)
    val pointCbScaling = IntArray(16)
    var numCrPoints = 0
    val pointCrValue = IntArray(16)
    val pointCrScaling = IntArray(16)
    var grainScalingMinus8 = 0
    var arCoeffLag = 0
    val arCoeffsYPlus128 = IntArray(24)
    val arCoeffsCbPlus128 = IntArray(25)
    val arCoeffsCrPlus128 = IntArray(25)
    var arCoeffShiftMinus6 = 0
    var grainScaleShift = 0
    var cbMult = 0
    var cbLumaMult = 0
    var cbOffset = 0
    var crMult = 0
    var crLumaMult = 0
    var crOffset = 0
    var overlapFlag = false
    var clipToRestrictedRange = false

    fun copyFrom(o: Av1FilmGrain) {
        applyGrain = o.applyGrain; grainSeed = o.grainSeed; updateGrain = o.updateGrain
        numYPoints = o.numYPoints; o.pointYValue.copyInto(pointYValue); o.pointYScaling.copyInto(pointYScaling)
        chromaScalingFromLuma = o.chromaScalingFromLuma
        numCbPoints = o.numCbPoints; o.pointCbValue.copyInto(pointCbValue); o.pointCbScaling.copyInto(pointCbScaling)
        numCrPoints = o.numCrPoints; o.pointCrValue.copyInto(pointCrValue); o.pointCrScaling.copyInto(pointCrScaling)
        grainScalingMinus8 = o.grainScalingMinus8; arCoeffLag = o.arCoeffLag
        o.arCoeffsYPlus128.copyInto(arCoeffsYPlus128); o.arCoeffsCbPlus128.copyInto(arCoeffsCbPlus128)
        o.arCoeffsCrPlus128.copyInto(arCoeffsCrPlus128)
        arCoeffShiftMinus6 = o.arCoeffShiftMinus6; grainScaleShift = o.grainScaleShift
        cbMult = o.cbMult; cbLumaMult = o.cbLumaMult; cbOffset = o.cbOffset
        crMult = o.crMult; crLumaMult = o.crLumaMult; crOffset = o.crOffset
        overlapFlag = o.overlapFlag; clipToRestrictedRange = o.clipToRestrictedRange
    }

    fun reset() = copyFrom(Av1FilmGrain())
}

/**
 * What the AV1 decoder keeps for each of the NUM_REF_FRAMES reference slots between frames
 * (specification section 7.20), as far as the frame header reads it.
 */
internal class Av1RefSlots {
    val valid = BooleanArray(8)
    val frameId = IntArray(8)
    val frameType = IntArray(8)
    val orderHint = IntArray(8)
    val upscaledWidth = IntArray(8)
    val frameWidth = IntArray(8)
    val frameHeight = IntArray(8)
    val renderWidth = IntArray(8)
    val renderHeight = IntArray(8)
    val miCols = IntArray(8)
    val miRows = IntArray(8)
    val cdfs = arrayOfNulls<Av1Cdfs>(8)
    val loopFilterRefDeltas = Array(8) { IntArray(8) }
    val loopFilterModeDeltas = Array(8) { IntArray(2) }
    val featureEnabled = Array(8) { Array(8) { BooleanArray(8) } }
    val featureData = Array(8) { Array(8) { IntArray(8) } }
    val gmParams = Array(8) { Array(8) { IntArray(6) } }
    val grain = Array(8) { Av1FilmGrain() }
    /** The saved order hints of each slot's own references, for motion field estimation. */
    val savedOrderHints = Array(8) { IntArray(8) }
}

/** An AV1 uncompressed frame header (specification section 5.9) and the variables it derives. */
internal class Av1FrameHeader(val seq: Av1SequenceHeader) {
    var showExistingFrame = false
    var frameToShowMapIdx = 0
    var frameType = KEY_FRAME
    var frameIsIntra = true
    var showFrame = true
    var showableFrame = false
    var errorResilientMode = false
    var disableCdfUpdate = false
    var allowScreenContentTools = 0
    var forceIntegerMv = 0
    var currentFrameId = 0
    var frameSizeOverrideFlag = false
    var orderHint = 0
    var primaryRefFrame = PRIMARY_REF_NONE
    var refreshFrameFlags = 0
    var frameWidth = 0
    var frameHeight = 0
    var upscaledWidth = 0
    var renderWidth = 0
    var renderHeight = 0
    var useSuperres = false
    var superresDenom = SUPERRES_NUM
    var miCols = 0
    var miRows = 0
    var allowIntrabc = false
    val refFrameIdx = IntArray(7)
    var allowHighPrecisionMv = false
    var interpolationFilter = 0
    var isMotionModeSwitchable = false
    var useRefFrameMvs = false
    val orderHints = IntArray(8)
    val refFrameSignBias = IntArray(8)
    var disableFrameEndUpdateCdf = true

    // tile_info( )
    var tileCols = 1
    var tileRows = 1
    var tileColsLog2 = 0
    var tileRowsLog2 = 0
    val miColStarts = IntArray(65)
    val miRowStarts = IntArray(65)
    var contextUpdateTileId = 0
    var tileSizeBytes = 4

    // quantization_params( )
    var baseQIdx = 0
    var deltaQYDc = 0
    var deltaQUDc = 0
    var deltaQUAc = 0
    var deltaQVDc = 0
    var deltaQVAc = 0
    var usingQmatrix = false
    var qmY = 0
    var qmU = 0
    var qmV = 0

    // segmentation_params( )
    var segmentationEnabled = false
    var segmentationUpdateMap = false
    var segmentationTemporalUpdate = false
    var segmentationUpdateData = false
    val featureEnabled = Array(8) { BooleanArray(8) }
    val featureData = Array(8) { IntArray(8) }
    var segIdPreSkip = false
    var lastActiveSegId = 0

    var deltaQPresent = false
    var deltaQRes = 0
    var deltaLfPresent = false
    var deltaLfRes = 0
    var deltaLfMulti = false
    var codedLossless = false
    var allLossless = false
    val losslessArray = BooleanArray(8)
    val segQmLevel = Array(3) { IntArray(8) }

    // loop_filter_params( )
    val loopFilterLevel = IntArray(4)
    var loopFilterSharpness = 0
    var loopFilterDeltaEnabled = false
    val loopFilterRefDeltas = IntArray(8)
    val loopFilterModeDeltas = IntArray(2)

    // cdef_params( )
    var cdefDamping = 3
    var cdefBits = 0
    val cdefYPriStrength = IntArray(8)
    val cdefYSecStrength = IntArray(8)
    val cdefUvPriStrength = IntArray(8)
    val cdefUvSecStrength = IntArray(8)

    // lr_params( )
    val frameRestorationType = IntArray(3)
    val loopRestorationSize = IntArray(3)
    var usesLr = false

    var txMode = 0
    var referenceSelect = false
    var skipModePresent = false
    val skipModeFrame = IntArray(2)
    var allowWarpedMotion = false
    var reducedTxSet = false
    val gmType = IntArray(8)
    val gmParams = Array(8) { IntArray(6) }
    val prevGmParams = Array(8) { IntArray(6) }
    val filmGrain = Av1FilmGrain()

    /** True when the header loaded CDFs from a reference frame instead of the defaults. */
    var cdfsFromReference = false

    fun segFeatureActiveIdx(idx: Int, feature: Int): Boolean = segmentationEnabled && featureEnabled[idx][feature]

    /** `get_qindex( ignoreDeltaQ, segmentId )`, with [currentQIndex] for delta quantization. */
    fun qIndex(ignoreDeltaQ: Boolean, segmentId: Int, currentQIndex: Int): Int {
        if (segFeatureActiveIdx(segmentId, SEG_LVL_ALT_Q)) {
            val data = featureData[segmentId][SEG_LVL_ALT_Q]
            var q = baseQIdx + data
            if (!ignoreDeltaQ && deltaQPresent) q = currentQIndex + data
            return q.coerceIn(0, 255)
        }
        if (!ignoreDeltaQ && deltaQPresent) return currentQIndex
        return baseQIdx
    }

    companion object {
        const val KEY_FRAME = 0
        const val INTER_FRAME = 1
        const val INTRA_ONLY_FRAME = 2
        const val SWITCH_FRAME = 3
        const val PRIMARY_REF_NONE = 7
        const val SUPERRES_NUM = 8
        const val SUPERRES_DENOM_MIN = 9
        const val SUPERRES_DENOM_BITS = 3
        const val SEG_LVL_ALT_Q = 0
        const val SEG_LVL_REF_FRAME = 5
        const val MAX_SEGMENTS = 8
        const val SEG_LVL_MAX = 8
        const val ONLY_4X4 = 0
        const val TX_MODE_LARGEST = 1
        const val TX_MODE_SELECT = 2
        const val RESTORE_NONE = 0
        const val RESTORE_WIENER = 1
        const val RESTORE_SGRPROJ = 2
        const val RESTORE_SWITCHABLE = 3
        const val RESTORATION_TILESIZE_MAX = 256
        const val SWITCHABLE = 4
        const val IDENTITY = 0
        const val TRANSLATION = 1
        const val ROTZOOM = 2
        const val AFFINE = 3
        const val WARPEDMODEL_PREC_BITS = 16
        const val LAST_FRAME = 1
        const val GOLDEN_FRAME = 4
        const val BWDREF_FRAME = 5
        const val ALTREF2_FRAME = 6
        const val ALTREF_FRAME = 7
        const val INTRA_FRAME = 0
        const val MAX_TILE_WIDTH = 4096
        const val MAX_TILE_AREA = 4096 * 2304
        const val MAX_TILE_ROWS = 64
        const val MAX_TILE_COLS = 64

        private val DEFAULT_REF_DELTAS = intArrayOf(1, 0, 0, 0, -1, 0, -1, -1)

        /**
         * `uncompressed_header( )` for the OBU's [temporalId] and [spatialId], reading [refs]
         * for what earlier frames left in the reference slots.
         */
        fun parse(r: Av1BitReader, seq: Av1SequenceHeader, refs: Av1RefSlots, temporalId: Int, spatialId: Int): Av1FrameHeader {
            val h = Av1FrameHeader(seq)
            h.read(r, refs, temporalId, spatialId)
            return h
        }

        fun tileLog2(blkSize: Int, target: Int): Int {
            var k = 0
            while ((blkSize shl k) < target) k++
            return k
        }
    }

    private fun read(r: Av1BitReader, refs: Av1RefSlots, temporalId: Int, spatialId: Int) {
        val idLen = if (seq.frameIdNumbersPresent) seq.additionalFrameIdLength + seq.deltaFrameIdLength else 0
        val allFrames = (1 shl 8) - 1
        if (seq.reducedStillPictureHeader) {
            showExistingFrame = false
            frameType = KEY_FRAME
            frameIsIntra = true
            showFrame = true
            showableFrame = false
        } else {
            showExistingFrame = r.flag()
            if (showExistingFrame) {
                frameToShowMapIdx = r.f(3)
                if (seq.decoderModelInfoPresent && !seq.equalPictureInterval) r.f(seq.framePresentationTimeLength)
                refreshFrameFlags = 0
                if (seq.frameIdNumbersPresent) r.f(idLen)
                frameType = refs.frameType[frameToShowMapIdx]
                if (frameType == KEY_FRAME) refreshFrameFlags = allFrames
                if (seq.filmGrainParamsPresent) filmGrain.copyFrom(refs.grain[frameToShowMapIdx])
                return
            }
            frameType = r.f(2)
            frameIsIntra = frameType == INTRA_ONLY_FRAME || frameType == KEY_FRAME
            showFrame = r.flag()
            if (showFrame && seq.decoderModelInfoPresent && !seq.equalPictureInterval) r.f(seq.framePresentationTimeLength)
            showableFrame = if (showFrame) frameType != KEY_FRAME else r.flag()
            errorResilientMode = if (frameType == SWITCH_FRAME || (frameType == KEY_FRAME && showFrame)) true else r.flag()
        }
        if (frameType == KEY_FRAME && showFrame) {
            for (i in 0 until 8) {
                refs.valid[i] = false
                refs.orderHint[i] = 0
            }
            for (i in 0 until 7) orderHints[LAST_FRAME + i] = 0
        }
        disableCdfUpdate = r.flag()
        allowScreenContentTools = if (seq.seqForceScreenContentTools == Av1SequenceHeader.SELECT_SCREEN_CONTENT_TOOLS) r.f(1)
        else seq.seqForceScreenContentTools
        forceIntegerMv = if (allowScreenContentTools != 0) {
            if (seq.seqForceIntegerMv == Av1SequenceHeader.SELECT_INTEGER_MV) r.f(1) else seq.seqForceIntegerMv
        } else 0
        if (frameIsIntra) forceIntegerMv = 1
        if (seq.frameIdNumbersPresent) {
            currentFrameId = r.f(idLen)
            // mark_ref_frames( idLen )
            val diffLen = seq.deltaFrameIdLength
            for (i in 0 until 8) {
                if (currentFrameId > (1 shl diffLen)) {
                    if (refs.frameId[i] > currentFrameId || refs.frameId[i] < currentFrameId - (1 shl diffLen)) refs.valid[i] = false
                } else if (refs.frameId[i] > currentFrameId && refs.frameId[i] < (1 shl idLen) + currentFrameId - (1 shl diffLen)) {
                    refs.valid[i] = false
                }
            }
        } else {
            currentFrameId = 0
        }
        frameSizeOverrideFlag = when {
            frameType == SWITCH_FRAME -> true
            seq.reducedStillPictureHeader -> false
            else -> r.flag()
        }
        orderHint = r.f(seq.orderHintBits)
        primaryRefFrame = if (frameIsIntra || errorResilientMode) PRIMARY_REF_NONE else r.f(3)
        if (seq.decoderModelInfoPresent) {
            if (r.flag()) {
                for (op in seq.operatingPointIdc.indices) {
                    if (seq.decoderModelPresentForOp[op]) {
                        val idc = seq.operatingPointIdc[op]
                        val inTemporal = (idc shr temporalId) and 1
                        val inSpatial = (idc shr (spatialId + 8)) and 1
                        if (idc == 0 || (inTemporal == 1 && inSpatial == 1)) r.f(seq.bufferRemovalTimeLength)
                    }
                }
            }
        }
        allowHighPrecisionMv = false
        useRefFrameMvs = false
        allowIntrabc = false
        refreshFrameFlags = if (frameType == SWITCH_FRAME || (frameType == KEY_FRAME && showFrame)) allFrames else r.f(8)
        if (!frameIsIntra || refreshFrameFlags != allFrames) {
            if (errorResilientMode && seq.enableOrderHint) {
                for (i in 0 until 8) {
                    val hint = r.f(seq.orderHintBits)
                    if (hint != refs.orderHint[i]) {
                        // The specification marks the slot invalid; a decoder fills it with a blank frame of this hint.
                        refs.valid[i] = false
                        refs.orderHint[i] = hint
                    }
                }
            }
        }
        if (frameIsIntra) {
            frameSize(r)
            renderSize(r)
            if (allowScreenContentTools != 0 && upscaledWidth == frameWidth) allowIntrabc = r.flag()
        } else {
            var shortSignaling = false
            if (seq.enableOrderHint) {
                shortSignaling = r.flag()
                if (shortSignaling) {
                    r.f(3) // last_frame_idx
                    r.f(3) // gold_frame_idx
                    throw UnsupportedImageException("AV1 frame reference short signalling")
                }
            }
            for (i in 0 until 7) {
                if (!shortSignaling) refFrameIdx[i] = r.f(3)
                if (seq.frameIdNumbersPresent) r.f(seq.deltaFrameIdLength)
            }
            if (frameSizeOverrideFlag && !errorResilientMode) frameSizeWithRefs(r, refs) else {
                frameSize(r)
                renderSize(r)
            }
            allowHighPrecisionMv = if (forceIntegerMv != 0) false else r.flag()
            interpolationFilter = if (r.flag()) SWITCHABLE else r.f(2)
            isMotionModeSwitchable = r.flag()
            useRefFrameMvs = if (errorResilientMode || !seq.enableRefFrameMvs) false else r.flag()
            for (i in 0 until 7) {
                val refFrame = LAST_FRAME + i
                val hint = refs.orderHint[refFrameIdx[i]]
                orderHints[refFrame] = hint
                refFrameSignBias[refFrame] = if (!seq.enableOrderHint) 0 else if (relativeDist(hint, orderHint) > 0) 1 else 0
            }
        }
        disableFrameEndUpdateCdf = if (seq.reducedStillPictureHeader || disableCdfUpdate) true else r.flag()
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            setupPastIndependence()
        } else {
            cdfsFromReference = true
            loadPrevious(refs)
        }
        tileInfo(r)
        quantizationParams(r)
        segmentationParams(r)
        deltaQPresent = false
        deltaQRes = 0
        if (baseQIdx > 0) deltaQPresent = r.flag()
        if (deltaQPresent) deltaQRes = r.f(2)
        deltaLfPresent = false
        deltaLfRes = 0
        deltaLfMulti = false
        if (deltaQPresent) {
            if (!allowIntrabc) deltaLfPresent = r.flag()
            if (deltaLfPresent) {
                deltaLfRes = r.f(2)
                deltaLfMulti = r.flag()
            }
        }
        codedLossless = true
        for (segmentId in 0 until MAX_SEGMENTS) {
            val qindex = qIndex(true, segmentId, 0)
            losslessArray[segmentId] = qindex == 0 && deltaQYDc == 0 && deltaQUAc == 0 && deltaQUDc == 0 &&
                deltaQVAc == 0 && deltaQVDc == 0
            if (!losslessArray[segmentId]) codedLossless = false
            if (usingQmatrix) {
                if (losslessArray[segmentId]) {
                    segQmLevel[0][segmentId] = 15
                    segQmLevel[1][segmentId] = 15
                    segQmLevel[2][segmentId] = 15
                } else {
                    segQmLevel[0][segmentId] = qmY
                    segQmLevel[1][segmentId] = qmU
                    segQmLevel[2][segmentId] = qmV
                }
            }
        }
        allLossless = codedLossless && frameWidth == upscaledWidth
        loopFilterParams(r)
        cdefParams(r)
        lrParams(r)
        txMode = if (codedLossless) ONLY_4X4 else if (r.flag()) TX_MODE_SELECT else TX_MODE_LARGEST
        referenceSelect = if (frameIsIntra) false else r.flag()
        skipModeParams(r, refs)
        allowWarpedMotion = if (frameIsIntra || errorResilientMode || !seq.enableWarpedMotion) false else r.flag()
        reducedTxSet = r.flag()
        globalMotionParams(r)
        filmGrainParams(r, refs)
    }

    fun relativeDist(a: Int, b: Int): Int {
        if (!seq.enableOrderHint) return 0
        var diff = a - b
        val m = 1 shl (seq.orderHintBits - 1)
        diff = (diff and (m - 1)) - (diff and m)
        return diff
    }

    private fun frameSize(r: Av1BitReader) {
        if (frameSizeOverrideFlag) {
            frameWidth = r.f(seq.frameWidthBits) + 1
            frameHeight = r.f(seq.frameHeightBits) + 1
        } else {
            frameWidth = seq.maxFrameWidth
            frameHeight = seq.maxFrameHeight
        }
        superresParams(r)
        computeImageSize()
    }

    private fun renderSize(r: Av1BitReader) {
        if (r.flag()) {
            renderWidth = r.f(16) + 1
            renderHeight = r.f(16) + 1
        } else {
            renderWidth = upscaledWidth
            renderHeight = frameHeight
        }
    }

    private fun frameSizeWithRefs(r: Av1BitReader, refs: Av1RefSlots) {
        var found = false
        for (i in 0 until 7) {
            if (r.flag()) {
                val slot = refFrameIdx[i]
                upscaledWidth = refs.upscaledWidth[slot]
                frameWidth = upscaledWidth
                frameHeight = refs.frameHeight[slot]
                renderWidth = refs.renderWidth[slot]
                renderHeight = refs.renderHeight[slot]
                found = true
                break
            }
        }
        if (!found) {
            frameSize(r)
            renderSize(r)
        } else {
            superresParams(r)
            computeImageSize()
        }
    }

    private fun superresParams(r: Av1BitReader) {
        useSuperres = if (seq.enableSuperres) r.flag() else false
        superresDenom = if (useSuperres) r.f(SUPERRES_DENOM_BITS) + SUPERRES_DENOM_MIN else SUPERRES_NUM
        upscaledWidth = frameWidth
        frameWidth = (upscaledWidth * SUPERRES_NUM + (superresDenom / 2)) / superresDenom
    }

    private fun computeImageSize() {
        if (frameWidth <= 0 || frameHeight <= 0) throw ImageDecodeException("AV1: frame size ${frameWidth}x$frameHeight")
        miCols = 2 * ((frameWidth + 7) shr 3)
        miRows = 2 * ((frameHeight + 7) shr 3)
    }

    private fun setupPastIndependence() {
        for (i in 0 until 8) for (j in 0 until 8) {
            featureData[i][j] = 0
            featureEnabled[i][j] = false
        }
        for (ref in LAST_FRAME..ALTREF_FRAME) for (i in 0 until 6) {
            prevGmParams[ref][i] = if (i % 3 == 2) 1 shl WARPEDMODEL_PREC_BITS else 0
        }
        loopFilterDeltaEnabled = true
        DEFAULT_REF_DELTAS.copyInto(loopFilterRefDeltas)
        loopFilterModeDeltas.fill(0)
    }

    /** `load_previous( )`: the primary reference frame's global motion, loop filter deltas and segmentation. */
    private fun loadPrevious(refs: Av1RefSlots) {
        val prev = refFrameIdx[primaryRefFrame]
        for (ref in 0 until 8) refs.gmParams[prev][ref].copyInto(prevGmParams[ref])
        refs.loopFilterRefDeltas[prev].copyInto(loopFilterRefDeltas)
        refs.loopFilterModeDeltas[prev].copyInto(loopFilterModeDeltas)
        for (i in 0 until 8) {
            refs.featureEnabled[prev][i].copyInto(featureEnabled[i])
            refs.featureData[prev][i].copyInto(featureData[i])
        }
    }

    private fun tileInfo(r: Av1BitReader) {
        val sb128 = seq.use128x128Superblock
        val sbCols = if (sb128) (miCols + 31) shr 5 else (miCols + 15) shr 4
        val sbRows = if (sb128) (miRows + 31) shr 5 else (miRows + 15) shr 4
        val sbShift = if (sb128) 5 else 4
        val sbSize = sbShift + 2
        val maxTileWidthSb = MAX_TILE_WIDTH shr sbSize
        var maxTileAreaSb = MAX_TILE_AREA shr (2 * sbSize)
        val minLog2TileCols = tileLog2(maxTileWidthSb, sbCols)
        val maxLog2TileCols = tileLog2(1, minOf(sbCols, MAX_TILE_COLS))
        val maxLog2TileRows = tileLog2(1, minOf(sbRows, MAX_TILE_ROWS))
        val minLog2Tiles = maxOf(minLog2TileCols, tileLog2(maxTileAreaSb, sbRows * sbCols))
        if (r.flag()) {
            tileColsLog2 = minLog2TileCols
            while (tileColsLog2 < maxLog2TileCols) {
                if (r.flag()) tileColsLog2++ else break
            }
            val tileWidthSb = (sbCols + (1 shl tileColsLog2) - 1) shr tileColsLog2
            var i = 0
            var startSb = 0
            while (startSb < sbCols) {
                miColStarts[i] = startSb shl sbShift
                i++
                startSb += tileWidthSb
            }
            miColStarts[i] = miCols
            tileCols = i
            val minLog2TileRows = maxOf(minLog2Tiles - tileColsLog2, 0)
            tileRowsLog2 = minLog2TileRows
            while (tileRowsLog2 < maxLog2TileRows) {
                if (r.flag()) tileRowsLog2++ else break
            }
            val tileHeightSb = (sbRows + (1 shl tileRowsLog2) - 1) shr tileRowsLog2
            i = 0
            startSb = 0
            while (startSb < sbRows) {
                miRowStarts[i] = startSb shl sbShift
                i++
                startSb += tileHeightSb
            }
            miRowStarts[i] = miRows
            tileRows = i
        } else {
            var widestTileSb = 0
            var startSb = 0
            var i = 0
            while (startSb < sbCols) {
                if (i >= MAX_TILE_COLS) throw ImageDecodeException("AV1: more than $MAX_TILE_COLS tile columns")
                miColStarts[i] = startSb shl sbShift
                val maxWidth = minOf(sbCols - startSb, maxTileWidthSb)
                val sizeSb = r.ns(maxWidth) + 1
                widestTileSb = maxOf(sizeSb, widestTileSb)
                startSb += sizeSb
                i++
            }
            miColStarts[i] = miCols
            tileCols = i
            tileColsLog2 = tileLog2(1, tileCols)
            maxTileAreaSb = if (minLog2Tiles > 0) (sbRows * sbCols) shr (minLog2Tiles + 1) else sbRows * sbCols
            val maxTileHeightSb = maxOf(maxTileAreaSb / widestTileSb, 1)
            startSb = 0
            i = 0
            while (startSb < sbRows) {
                if (i >= MAX_TILE_ROWS) throw ImageDecodeException("AV1: more than $MAX_TILE_ROWS tile rows")
                miRowStarts[i] = startSb shl sbShift
                val maxHeight = minOf(sbRows - startSb, maxTileHeightSb)
                val sizeSb = r.ns(maxHeight) + 1
                startSb += sizeSb
                i++
            }
            miRowStarts[i] = miRows
            tileRows = i
            tileRowsLog2 = tileLog2(1, tileRows)
        }
        if (tileColsLog2 > 0 || tileRowsLog2 > 0) {
            contextUpdateTileId = r.f(tileRowsLog2 + tileColsLog2)
            tileSizeBytes = r.f(2) + 1
        } else {
            contextUpdateTileId = 0
        }
    }

    private fun readDeltaQ(r: Av1BitReader): Int = if (r.flag()) r.su(7) else 0

    private fun quantizationParams(r: Av1BitReader) {
        baseQIdx = r.f(8)
        deltaQYDc = readDeltaQ(r)
        if (seq.numPlanes > 1) {
            val diffUvDelta = if (seq.separateUvDeltaQ) r.flag() else false
            deltaQUDc = readDeltaQ(r)
            deltaQUAc = readDeltaQ(r)
            if (diffUvDelta) {
                deltaQVDc = readDeltaQ(r)
                deltaQVAc = readDeltaQ(r)
            } else {
                deltaQVDc = deltaQUDc
                deltaQVAc = deltaQUAc
            }
        } else {
            deltaQUDc = 0; deltaQUAc = 0; deltaQVDc = 0; deltaQVAc = 0
        }
        usingQmatrix = r.flag()
        if (usingQmatrix) {
            qmY = r.f(4)
            qmU = r.f(4)
            qmV = if (!seq.separateUvDeltaQ) qmU else r.f(4)
        }
    }

    private fun segmentationParams(r: Av1BitReader) {
        segmentationEnabled = r.flag()
        if (segmentationEnabled) {
            if (primaryRefFrame == PRIMARY_REF_NONE) {
                segmentationUpdateMap = true
                segmentationTemporalUpdate = false
                segmentationUpdateData = true
            } else {
                segmentationUpdateMap = r.flag()
                segmentationTemporalUpdate = if (segmentationUpdateMap) r.flag() else false
                segmentationUpdateData = r.flag()
            }
            if (segmentationUpdateData) {
                val bits = Av1Tables.segmentationFeatureBits
                val signed = Av1Tables.segmentationFeatureSigned
                val max = Av1Tables.segmentationFeatureMax
                for (i in 0 until MAX_SEGMENTS) for (j in 0 until SEG_LVL_MAX) {
                    val enabled = r.flag()
                    featureEnabled[i][j] = enabled
                    var clipped = 0
                    if (enabled) {
                        val limit = max[j]
                        clipped = if (signed[j] == 1) r.su(1 + bits[j]).coerceIn(-limit, limit)
                        else r.f(bits[j]).coerceIn(0, limit)
                    }
                    featureData[i][j] = clipped
                }
            }
        } else {
            for (i in 0 until MAX_SEGMENTS) for (j in 0 until SEG_LVL_MAX) {
                featureEnabled[i][j] = false
                featureData[i][j] = 0
            }
        }
        segIdPreSkip = false
        lastActiveSegId = 0
        for (i in 0 until MAX_SEGMENTS) for (j in 0 until SEG_LVL_MAX) {
            if (featureEnabled[i][j]) {
                lastActiveSegId = i
                if (j >= SEG_LVL_REF_FRAME) segIdPreSkip = true
            }
        }
    }

    private fun loopFilterParams(r: Av1BitReader) {
        if (codedLossless || allowIntrabc) {
            loopFilterLevel[0] = 0
            loopFilterLevel[1] = 0
            DEFAULT_REF_DELTAS.copyInto(loopFilterRefDeltas)
            loopFilterModeDeltas.fill(0)
            return
        }
        loopFilterLevel[0] = r.f(6)
        loopFilterLevel[1] = r.f(6)
        if (seq.numPlanes > 1 && (loopFilterLevel[0] != 0 || loopFilterLevel[1] != 0)) {
            loopFilterLevel[2] = r.f(6)
            loopFilterLevel[3] = r.f(6)
        }
        loopFilterSharpness = r.f(3)
        loopFilterDeltaEnabled = r.flag()
        if (loopFilterDeltaEnabled) {
            if (r.flag()) {
                for (i in 0 until 8) if (r.flag()) loopFilterRefDeltas[i] = r.su(7)
                for (i in 0 until 2) if (r.flag()) loopFilterModeDeltas[i] = r.su(7)
            }
        }
    }

    private fun cdefParams(r: Av1BitReader) {
        if (codedLossless || allowIntrabc || !seq.enableCdef) {
            cdefBits = 0
            cdefYPriStrength[0] = 0
            cdefYSecStrength[0] = 0
            cdefUvPriStrength[0] = 0
            cdefUvSecStrength[0] = 0
            cdefDamping = 3
            return
        }
        cdefDamping = r.f(2) + 3
        cdefBits = r.f(2)
        for (i in 0 until (1 shl cdefBits)) {
            cdefYPriStrength[i] = r.f(4)
            cdefYSecStrength[i] = r.f(2)
            if (cdefYSecStrength[i] == 3) cdefYSecStrength[i]++
            if (seq.numPlanes > 1) {
                cdefUvPriStrength[i] = r.f(4)
                cdefUvSecStrength[i] = r.f(2)
                if (cdefUvSecStrength[i] == 3) cdefUvSecStrength[i]++
            }
        }
    }

    private fun lrParams(r: Av1BitReader) {
        if (allLossless || allowIntrabc || !seq.enableRestoration) {
            frameRestorationType.fill(RESTORE_NONE)
            usesLr = false
            return
        }
        usesLr = false
        var usesChromaLr = false
        for (i in 0 until seq.numPlanes) {
            frameRestorationType[i] = Av1Tables.remapLrType[r.f(2)]
            if (frameRestorationType[i] != RESTORE_NONE) {
                usesLr = true
                if (i > 0) usesChromaLr = true
            }
        }
        if (usesLr) {
            var shift: Int
            if (seq.use128x128Superblock) {
                shift = r.f(1) + 1
            } else {
                shift = r.f(1)
                if (shift != 0) shift += r.f(1)
            }
            loopRestorationSize[0] = RESTORATION_TILESIZE_MAX shr (2 - shift)
            val uvShift = if (seq.subsamplingX == 1 && seq.subsamplingY == 1 && usesChromaLr) r.f(1) else 0
            loopRestorationSize[1] = loopRestorationSize[0] shr uvShift
            loopRestorationSize[2] = loopRestorationSize[0] shr uvShift
        }
    }

    private fun skipModeParams(r: Av1BitReader, refs: Av1RefSlots) {
        var skipModeAllowed = false
        if (!frameIsIntra && referenceSelect && seq.enableOrderHint) {
            var forwardIdx = -1
            var backwardIdx = -1
            var forwardHint = 0
            var backwardHint = 0
            for (i in 0 until 7) {
                val refHint = refs.orderHint[refFrameIdx[i]]
                if (relativeDist(refHint, orderHint) < 0) {
                    if (forwardIdx < 0 || relativeDist(refHint, forwardHint) > 0) {
                        forwardIdx = i
                        forwardHint = refHint
                    }
                } else if (relativeDist(refHint, orderHint) > 0) {
                    if (backwardIdx < 0 || relativeDist(refHint, backwardHint) < 0) {
                        backwardIdx = i
                        backwardHint = refHint
                    }
                }
            }
            if (forwardIdx < 0) {
                skipModeAllowed = false
            } else if (backwardIdx >= 0) {
                skipModeAllowed = true
                skipModeFrame[0] = LAST_FRAME + minOf(forwardIdx, backwardIdx)
                skipModeFrame[1] = LAST_FRAME + maxOf(forwardIdx, backwardIdx)
            } else {
                var secondForwardIdx = -1
                var secondForwardHint = 0
                for (i in 0 until 7) {
                    val refHint = refs.orderHint[refFrameIdx[i]]
                    if (relativeDist(refHint, forwardHint) < 0) {
                        if (secondForwardIdx < 0 || relativeDist(refHint, secondForwardHint) > 0) {
                            secondForwardIdx = i
                            secondForwardHint = refHint
                        }
                    }
                }
                if (secondForwardIdx >= 0) {
                    skipModeAllowed = true
                    skipModeFrame[0] = LAST_FRAME + minOf(forwardIdx, secondForwardIdx)
                    skipModeFrame[1] = LAST_FRAME + maxOf(forwardIdx, secondForwardIdx)
                }
            }
        }
        skipModePresent = if (skipModeAllowed) r.flag() else false
    }

    private fun globalMotionParams(r: Av1BitReader) {
        for (ref in LAST_FRAME..ALTREF_FRAME) {
            gmType[ref] = IDENTITY
            for (i in 0 until 6) gmParams[ref][i] = if (i % 3 == 2) 1 shl WARPEDMODEL_PREC_BITS else 0
        }
        if (frameIsIntra) return
        for (ref in LAST_FRAME..ALTREF_FRAME) {
            val type = if (r.flag()) {
                if (r.flag()) ROTZOOM else if (r.flag()) TRANSLATION else AFFINE
            } else IDENTITY
            gmType[ref] = type
            if (type >= ROTZOOM) {
                readGlobalParam(r, type, ref, 2)
                readGlobalParam(r, type, ref, 3)
                if (type == AFFINE) {
                    readGlobalParam(r, type, ref, 4)
                    readGlobalParam(r, type, ref, 5)
                } else {
                    gmParams[ref][4] = -gmParams[ref][3]
                    gmParams[ref][5] = gmParams[ref][2]
                }
            }
            if (type >= TRANSLATION) {
                readGlobalParam(r, type, ref, 0)
                readGlobalParam(r, type, ref, 1)
            }
        }
    }

    private fun readGlobalParam(r: Av1BitReader, type: Int, ref: Int, idx: Int) {
        var absBits = 12 // GM_ABS_ALPHA_BITS
        var precBits = 15 // GM_ALPHA_PREC_BITS
        if (idx < 2) {
            if (type == TRANSLATION) {
                val hp = if (allowHighPrecisionMv) 0 else 1
                absBits = 9 - hp // GM_ABS_TRANS_ONLY_BITS
                precBits = 3 - hp // GM_TRANS_ONLY_PREC_BITS
            } else {
                absBits = 12 // GM_ABS_TRANS_BITS
                precBits = 6 // GM_TRANS_PREC_BITS
            }
        }
        val precDiff = WARPEDMODEL_PREC_BITS - precBits
        val round = if (idx % 3 == 2) 1 shl WARPEDMODEL_PREC_BITS else 0
        val sub = if (idx % 3 == 2) 1 shl precBits else 0
        val mx = 1 shl absBits
        val ref0 = (prevGmParams[ref][idx] shr precDiff) - sub
        gmParams[ref][idx] = (decodeSignedSubexpWithRef(r, -mx, mx + 1, ref0) shl precDiff) + round
    }

    private fun decodeSignedSubexpWithRef(r: Av1BitReader, low: Int, high: Int, ref: Int): Int =
        decodeUnsignedSubexpWithRef(r, high - low, ref - low) + low

    private fun decodeUnsignedSubexpWithRef(r: Av1BitReader, mx: Int, ref: Int): Int {
        val v = decodeSubexp(r, mx)
        return if ((ref shl 1) <= mx) inverseRecenter(ref, v) else mx - 1 - inverseRecenter(mx - 1 - ref, v)
    }

    private fun decodeSubexp(r: Av1BitReader, numSyms: Int): Int {
        var i = 0
        var mk = 0
        val k = 3
        while (true) {
            val b2 = if (i != 0) k + i - 1 else k
            val a = 1 shl b2
            if (numSyms <= mk + 3 * a) {
                return r.ns(numSyms - mk) + mk
            } else if (r.flag()) {
                i++
                mk += a
            } else {
                return r.f(b2) + mk
            }
        }
    }

    private fun inverseRecenter(r: Int, v: Int): Int = when {
        v > 2 * r -> v
        v and 1 != 0 -> r - ((v + 1) shr 1)
        else -> r + (v shr 1)
    }

    private fun filmGrainParams(r: Av1BitReader, refs: Av1RefSlots) {
        val g = filmGrain
        if (!seq.filmGrainParamsPresent || (!showFrame && !showableFrame)) {
            g.reset()
            return
        }
        g.applyGrain = r.flag()
        if (!g.applyGrain) {
            g.reset()
            return
        }
        g.grainSeed = r.f(16)
        g.updateGrain = if (frameType == INTER_FRAME) r.flag() else true
        if (!g.updateGrain) {
            val idx = r.f(3)
            val seed = g.grainSeed
            g.copyFrom(refs.grain[idx])
            g.grainSeed = seed
            return
        }
        g.numYPoints = r.f(4)
        if (g.numYPoints > 14) throw ImageDecodeException("AV1: film grain with ${g.numYPoints} luma points")
        for (i in 0 until g.numYPoints) {
            g.pointYValue[i] = r.f(8)
            g.pointYScaling[i] = r.f(8)
        }
        g.chromaScalingFromLuma = if (seq.monochrome) false else r.flag()
        if (seq.monochrome || g.chromaScalingFromLuma || (seq.subsamplingX == 1 && seq.subsamplingY == 1 && g.numYPoints == 0)) {
            g.numCbPoints = 0
            g.numCrPoints = 0
        } else {
            g.numCbPoints = r.f(4)
            if (g.numCbPoints > 10) throw ImageDecodeException("AV1: film grain with ${g.numCbPoints} Cb points")
            for (i in 0 until g.numCbPoints) {
                g.pointCbValue[i] = r.f(8)
                g.pointCbScaling[i] = r.f(8)
            }
            g.numCrPoints = r.f(4)
            if (g.numCrPoints > 10) throw ImageDecodeException("AV1: film grain with ${g.numCrPoints} Cr points")
            for (i in 0 until g.numCrPoints) {
                g.pointCrValue[i] = r.f(8)
                g.pointCrScaling[i] = r.f(8)
            }
        }
        g.grainScalingMinus8 = r.f(2)
        g.arCoeffLag = r.f(2)
        val numPosLuma = 2 * g.arCoeffLag * (g.arCoeffLag + 1)
        val numPosChroma: Int
        if (g.numYPoints != 0) {
            numPosChroma = numPosLuma + 1
            for (i in 0 until numPosLuma) g.arCoeffsYPlus128[i] = r.f(8)
        } else {
            numPosChroma = numPosLuma
        }
        if (g.chromaScalingFromLuma || g.numCbPoints != 0) for (i in 0 until numPosChroma) g.arCoeffsCbPlus128[i] = r.f(8)
        if (g.chromaScalingFromLuma || g.numCrPoints != 0) for (i in 0 until numPosChroma) g.arCoeffsCrPlus128[i] = r.f(8)
        g.arCoeffShiftMinus6 = r.f(2)
        g.grainScaleShift = r.f(2)
        if (g.numCbPoints != 0) {
            g.cbMult = r.f(8)
            g.cbLumaMult = r.f(8)
            g.cbOffset = r.f(9)
        }
        if (g.numCrPoints != 0) {
            g.crMult = r.f(8)
            g.crLumaMult = r.f(8)
            g.crOffset = r.f(9)
        }
        g.overlapFlag = r.flag()
        g.clipToRestrictedRange = r.flag()
    }
}
