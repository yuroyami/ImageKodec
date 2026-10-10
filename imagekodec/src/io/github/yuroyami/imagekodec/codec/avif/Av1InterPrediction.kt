package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.codec.avif.Av1.INTRA_FRAME
import io.github.yuroyami.imagekodec.codec.avif.Av1.round2
import kotlin.math.abs

/**
 * The inter prediction process (specification section 7.11.3) for the blocks of [d]: motion
 * vector scaling, the subsample filters, local and global warps, the wedge, difference and
 * inter-intra masks, distance weights, and overlapped block motion compensation, writing into
 * CurrFrame. Intra block copy predicts through here too, from the frame being decoded.
 */
internal class Av1InterPrediction(private val d: Av1FrameDecoder) {
    private val preds = Array(2) { IntArray(MAX_SB * MAX_SB) }
    private val obmcPred = IntArray(MAX_SB * MAX_SB)
    /** Room for a 128-sample block read from a reference twice the size: 2 * 127 + 1 + 8 rows. */
    private val intermediate = IntArray((2 * MAX_SB + 8) * MAX_SB)
    private val warpIntermediate = IntArray(15 * 8)
    private val mask = IntArray(MAX_SB * MAX_SB)
    private var maskStride = 0

    private var interRound0 = 0
    private var interRound1 = 0
    private var interPostRound = 0
    private var fwdWeight = 0
    private var bckWeight = 0

    /** LocalWarpParams and LocalValid, set while predicting luma and kept for chroma. */
    private val localWarpParams = IntArray(6)
    private var localValid = false
    private val shear = IntArray(4)

    // The reference being read: FrameStore[ refIdx ], or CurrFrame for intra block copy.
    private lateinit var refPlanes: Array<ShortArray>
    private lateinit var refStrides: IntArray
    private var refUpscaledWidth = 0
    private var refFrameHeight = 0
    private var startX = 0
    private var startY = 0
    private var stepX = 0
    private var stepY = 0

    /** compute_prediction( ) for the block being decoded. */
    fun computePrediction() {
        val sbMask = d.sbMask
        val subBlockMiRow = d.miRow and sbMask
        val subBlockMiCol = d.miCol and sbMask
        for (plane in 0 until 1 + (if (d.hasChroma) 2 else 0)) {
            val subX = if (plane > 0) d.subX else 0
            val subY = if (plane > 0) d.subY else 0
            val planeSz = Av1.subsampledSize(d.miSize, subX, subY)
            val num4x4W = Av1.num4x4Wide[planeSz]
            val num4x4H = Av1.num4x4High[planeSz]
            val log2W = Av1.MI_SIZE_LOG2 + Av1.miWidthLog2[planeSz]
            val log2H = Av1.MI_SIZE_LOG2 + Av1.miHeightLog2[planeSz]
            val baseX = (d.miCol shr subX) * Av1.MI_SIZE
            val baseY = (d.miRow shr subY) * Av1.MI_SIZE
            var candRow = (d.miRow shr subY) shl subY
            var candCol = (d.miCol shr subX) shl subX
            if (d.isInter && d.refFrame[1] == INTRA_FRAME) {
                val mode = when (d.interintraMode) {
                    Av1.II_DC_PRED -> Av1.DC_PRED
                    Av1.II_V_PRED -> Av1.V_PRED
                    Av1.II_H_PRED -> Av1.H_PRED
                    else -> Av1.SMOOTH_PRED
                }
                Av1Intra.predictIntra(
                    d, plane, baseX, baseY,
                    if (plane == 0) d.availL else d.availLChroma,
                    if (plane == 0) d.availU else d.availUChroma,
                    d.blockDecodedAt(plane, (subBlockMiRow shr subY) - 1, (subBlockMiCol shr subX) + num4x4W),
                    d.blockDecodedAt(plane, (subBlockMiRow shr subY) + num4x4H, (subBlockMiCol shr subX) - 1),
                    mode, log2W, log2H,
                )
            }
            if (!d.isInter) continue
            var predW = Av1.blockWidth[d.miSize] shr subX
            var predH = Av1.blockHeight[d.miSize] shr subY
            var someUseIntra = false
            for (r in 0 until (num4x4H shl subY)) for (c in 0 until (num4x4W shl subX)) {
                if (d.refFrames0[d.mi(candRow + r, candCol + c)] == INTRA_FRAME) someUseIntra = true
            }
            if (someUseIntra) {
                predW = num4x4W * 4
                predH = num4x4H * 4
                candRow = d.miRow
                candCol = d.miCol
            }
            var r = 0
            var y = 0
            while (y < num4x4H * 4) {
                var c = 0
                var x = 0
                while (x < num4x4W * 4) {
                    predictInter(plane, baseX + x, baseY + y, predW, predH, candRow + r, candCol + c)
                    x += predW
                    c++
                }
                y += predH
                r++
            }
        }
    }

    /** predict_inter( plane, x, y, w, h, candRow, candCol ). */
    private fun predictInter(plane: Int, x: Int, y: Int, w: Int, h: Int, candRow: Int, candCol: Int) {
        val fh = d.fh
        val m = d.mi(candRow, candCol)
        val isCompound = d.refFrames1[m] > INTRA_FRAME
        roundingVariables(isCompound)
        if (plane == 0 && d.motionMode == Av1.LOCALWARP) {
            warpEstimation()
            if (localValid) localValid = setupShear(localWarpParams)
        }
        val globalMode = d.yMode == Av1.GLOBALMV || d.yMode == Av1.GLOBAL_GLOBALMV
        for (refList in 0 until (if (isCompound) 2 else 1)) {
            val refFrame = if (refList == 0) d.refFrames0[m] else d.refFrames1[m]
            var globalValid = false
            if (globalMode && fh.gmType[refFrame] > Av1.TRANSLATION) globalValid = setupShear(fh.gmParams[refFrame])
            val useWarp = when {
                w < 8 || h < 8 -> 0
                fh.forceIntegerMv != 0 -> 0
                d.motionMode == Av1.LOCALWARP && localValid -> 1
                globalMode && fh.gmType[refFrame] > Av1.TRANSLATION && !isScaled(refFrame) && globalValid -> 2
                else -> 0
            }
            val mvRow = d.mvs[m * 4 + refList * 2]
            val mvCol = d.mvs[m * 4 + refList * 2 + 1]
            if (!d.useIntrabc) {
                selectReference(fh.refFrameIdx[refFrame - Av1.LAST_FRAME])
            } else {
                refPlanes = d.frame
                refStrides = d.planeWidth
                refUpscaledWidth = fh.upscaledWidth
                refFrameHeight = fh.frameHeight
            }
            scaleMotionVector(plane, x, y, mvRow, mvCol)
            if (d.useIntrabc) {
                refUpscaledWidth = d.miCols * Av1.MI_SIZE
                refFrameHeight = d.miRows * Av1.MI_SIZE
            }
            val pred = preds[refList]
            d.toolUse?.let { use ->
                if (useWarp == 2) use.add("global warp")
                if (stepX != 1 shl SCALE_SUBPEL_BITS || stepY != 1 shl SCALE_SUBPEL_BITS) use.add("scaled reference")
            }
            if (useWarp != 0) {
                for (i8 in 0..((h - 1) shr 3)) for (j8 in 0..((w - 1) shr 3)) {
                    blockWarp(if (useWarp == 1) localWarpParams else fh.gmParams[refFrame], plane, x, y, i8, j8, w, h, pred)
                }
            } else {
                blockInterPrediction(plane, startX, startY, stepX, stepY, w, h, m, pred, w)
            }
        }
        val compoundType = d.compoundType
        if (compoundType == Av1.COMPOUND_WEDGE && plane == 0) {
            wedgeMask(w, h)
        } else if (compoundType == Av1.COMPOUND_INTRA) {
            intraModeVariantMask(w, h)
        } else if (compoundType == Av1.COMPOUND_DIFFWTD && plane == 0) {
            differenceWeightMask(w, h)
        }
        if (compoundType == Av1.COMPOUND_DISTANCE) distanceWeights(m)
        val out = d.frame[plane]
        val stride = d.planeWidth[plane]
        val maxValue = (1 shl d.bitDepth) - 1
        val isInterIntra = d.isInter && d.refFrame[1] == INTRA_FRAME
        val p0 = preds[0]
        val p1 = preds[1]
        if (!isCompound && !isInterIntra) {
            for (i in 0 until h) {
                val row = (y + i) * stride + x
                for (j in 0 until w) out[row + j] = p0[i * w + j].coerceIn(0, maxValue).toShort()
            }
        } else if (compoundType == Av1.COMPOUND_AVERAGE) {
            val shift = 1 + interPostRound
            for (i in 0 until h) {
                val row = (y + i) * stride + x
                for (j in 0 until w) out[row + j] = round2(p0[i * w + j] + p1[i * w + j], shift).coerceIn(0, maxValue).toShort()
            }
        } else if (compoundType == Av1.COMPOUND_DISTANCE) {
            val shift = 4 + interPostRound
            for (i in 0 until h) {
                val row = (y + i) * stride + x
                for (j in 0 until w) {
                    out[row + j] = round2(fwdWeight * p0[i * w + j] + bckWeight * p1[i * w + j], shift).coerceIn(0, maxValue).toShort()
                }
            }
        } else {
            maskBlend(plane, x, y, w, h)
        }
        if (d.motionMode == Av1.OBMC) overlappedMotionCompensation(plane, w, h)
    }

    /** The rounding variables derivation process. */
    private fun roundingVariables(isCompound: Boolean) {
        interRound0 = 3
        interRound1 = if (isCompound) 7 else 11
        if (d.bitDepth == 12) interRound0 += 2
        if (d.bitDepth == 12 && !isCompound) interRound1 -= 2
        interPostRound = 2 * FILTER_BITS - (interRound0 + interRound1)
    }

    /** Points the prediction at FrameStore[ [refIdx] ]. */
    private fun selectReference(refIdx: Int) {
        val refs = d.refs
        val picture = refs.pictures[refIdx]
        if (!refs.valid[refIdx] || picture == null) throw ImageDecodeException("AV1: a block predicts from empty reference slot $refIdx")
        if (picture.bitDepth != d.bitDepth || picture.subX != d.subX || picture.subY != d.subY || picture.planes.size != d.numPlanes) {
            throw ImageDecodeException("AV1: reference slot $refIdx holds a frame of another format")
        }
        refPlanes = picture.planes
        refStrides = picture.strides
        refUpscaledWidth = refs.upscaledWidth[refIdx]
        refFrameHeight = refs.frameHeight[refIdx]
    }

    /** is_scaled( refFrame ). */
    private fun isScaled(refFrame: Int): Boolean = d.isScaled(refFrame)

    /** The motion vector scaling process, for the reference [selectReference] chose, into startX, startY, stepX and stepY. */
    private fun scaleMotionVector(plane: Int, x: Int, y: Int, mvRow: Int, mvCol: Int) {
        val fh = d.fh
        val frameWidth = fh.frameWidth
        val frameHeight = fh.frameHeight
        if (2 * frameWidth < refUpscaledWidth || 2 * frameHeight < refFrameHeight ||
            frameWidth > 16 * refUpscaledWidth || frameHeight > 16 * refFrameHeight
        ) {
            throw ImageDecodeException("AV1: a reference of $refUpscaledWidth by $refFrameHeight for a frame of $frameWidth by $frameHeight")
        }
        val xScale = ((refUpscaledWidth shl REF_SCALE_SHIFT) + (frameWidth / 2)) / frameWidth
        val yScale = ((refFrameHeight shl REF_SCALE_SHIFT) + (frameHeight / 2)) / frameHeight
        val subX = if (plane > 0) d.subX else 0
        val subY = if (plane > 0) d.subY else 0
        val halfSample = 1 shl (SUBPEL_BITS - 1)
        val origX = ((x shl SUBPEL_BITS) + ((2 * mvCol) shr subX) + halfSample).toLong()
        val origY = ((y shl SUBPEL_BITS) + ((2 * mvRow) shr subY) + halfSample).toLong()
        val baseX = origX * xScale - (halfSample shl REF_SCALE_SHIFT)
        val baseY = origY * yScale - (halfSample shl REF_SCALE_SHIFT)
        val off = (1 shl (SCALE_SUBPEL_BITS - SUBPEL_BITS)) / 2
        startX = (Av1MvStack.round2Signed(baseX, REF_SCALE_SHIFT + SUBPEL_BITS - SCALE_SUBPEL_BITS) + off).toInt()
        startY = (Av1MvStack.round2Signed(baseY, REF_SCALE_SHIFT + SUBPEL_BITS - SCALE_SUBPEL_BITS) + off).toInt()
        stepX = Av1.round2Signed(xScale, REF_SCALE_SHIFT - SCALE_SUBPEL_BITS)
        stepY = Av1.round2Signed(yScale, REF_SCALE_SHIFT - SCALE_SUBPEL_BITS)
    }

    /**
     * The block inter prediction process from the selected reference, with the filters of the
     * block at [m], into [pred] with rows [predStride] apart.
     */
    private fun blockInterPrediction(plane: Int, x: Int, y: Int, xStep: Int, yStep: Int, w: Int, h: Int, m: Int, pred: IntArray, predStride: Int) {
        val ref = refPlanes[plane]
        val refStride = refStrides[plane]
        val subX = if (plane > 0) d.subX else 0
        val subY = if (plane > 0) d.subY else 0
        val lastX = ((refUpscaledWidth + subX) shr subX) - 1
        val lastY = ((refFrameHeight + subY) shr subY) - 1
        val intermediateHeight = (((h - 1) * yStep + (1 shl SCALE_SUBPEL_BITS) - 1) shr SCALE_SUBPEL_BITS) + 8
        val filters = Av1Tables.subpelFilters
        var filterX = d.interpFilters[m * 2 + 1]
        if (w <= 4) filterX = fourTap(filterX)
        var filterY = d.interpFilters[m * 2]
        if (h <= 4) filterY = fourTap(filterY)
        val inter = intermediate
        val round0 = interRound0
        val y0 = y shr SCALE_SUBPEL_BITS
        // Whole rows and columns of the reference stay inside it: no clamping per sample.
        val firstX = (x shr SCALE_SUBPEL_BITS) - 3
        val lastNeededX = ((x + xStep * (w - 1)) shr SCALE_SUBPEL_BITS) + 4
        val inside = firstX >= 0 && lastNeededX <= lastX
        for (r in 0 until intermediateHeight) {
            val rowStart = (y0 + r - 3).coerceIn(0, lastY) * refStride
            val o = r * w
            if (xStep == 1 shl SCALE_SUBPEL_BITS) {
                val f = filterX * 128 + ((x shr 6) and SUBPEL_MASK) * 8
                val px0 = (x shr SCALE_SUBPEL_BITS) - 3
                if (inside) {
                    val base = rowStart + px0
                    for (c in 0 until w) {
                        val b = base + c
                        val s = filters[f] * ref[b] + filters[f + 1] * ref[b + 1] + filters[f + 2] * ref[b + 2] +
                            filters[f + 3] * ref[b + 3] + filters[f + 4] * ref[b + 4] + filters[f + 5] * ref[b + 5] +
                            filters[f + 6] * ref[b + 6] + filters[f + 7] * ref[b + 7]
                        inter[o + c] = round2(s, round0)
                    }
                } else {
                    for (c in 0 until w) {
                        var s = 0
                        for (t in 0 until 8) s += filters[f + t] * ref[rowStart + (px0 + c + t).coerceIn(0, lastX)]
                        inter[o + c] = round2(s, round0)
                    }
                }
            } else {
                for (c in 0 until w) {
                    val p = x + xStep * c
                    val f = filterX * 128 + ((p shr 6) and SUBPEL_MASK) * 8
                    val px = (p shr SCALE_SUBPEL_BITS) - 3
                    var s = 0
                    for (t in 0 until 8) s += filters[f + t] * ref[rowStart + (px + t).coerceIn(0, lastX)]
                    inter[o + c] = round2(s, round0)
                }
            }
        }
        val round1 = interRound1
        for (r in 0 until h) {
            val p = (y and 1023) + yStep * r
            val f = filterY * 128 + ((p shr 6) and SUBPEL_MASK) * 8
            val base = (p shr SCALE_SUBPEL_BITS) * w
            val o = r * predStride
            for (c in 0 until w) {
                val b = base + c
                val s = filters[f] * inter[b] + filters[f + 1] * inter[b + w] + filters[f + 2] * inter[b + 2 * w] +
                    filters[f + 3] * inter[b + 3 * w] + filters[f + 4] * inter[b + 4 * w] + filters[f + 5] * inter[b + 5 * w] +
                    filters[f + 6] * inter[b + 6 * w] + filters[f + 7] * inter[b + 7 * w]
                pred[o + c] = round2(s, round1)
            }
        }
    }

    private fun fourTap(filter: Int): Int = when (filter) {
        Av1.EIGHTTAP, Av1.EIGHTTAP_SHARP -> 4
        Av1.EIGHTTAP_SMOOTH -> 5
        else -> filter
    }

    /** The block warp process for the 8x8 part ([i8], [j8]) of a [w] by [h] prediction into [pred]. */
    private fun blockWarp(warpParams: IntArray, plane: Int, x: Int, y: Int, i8: Int, j8: Int, w: Int, h: Int, pred: IntArray) {
        val ref = refPlanes[plane]
        val refStride = refStrides[plane]
        val subX = if (plane > 0) d.subX else 0
        val subY = if (plane > 0) d.subY else 0
        val lastX = ((refUpscaledWidth + subX) shr subX) - 1
        val lastY = ((refFrameHeight + subY) shr subY) - 1
        val srcX = (x + j8 * 8 + 4) shl subX
        val srcY = (y + i8 * 8 + 4) shl subY
        val dstX = warpParams[2].toLong() * srcX + warpParams[3].toLong() * srcY + warpParams[0]
        val dstY = warpParams[4].toLong() * srcX + warpParams[5].toLong() * srcY + warpParams[1]
        setupShear(warpParams)
        val alpha = shear[0]
        val beta = shear[1]
        val gamma = shear[2]
        val delta = shear[3]
        val filters = Av1Tables.warpedFilters
        val x4 = dstX shr subX
        val y4 = dstY shr subY
        val ix4 = (x4 shr WARPEDMODEL_PREC_BITS).toInt()
        val sx4 = (x4 and ((1L shl WARPEDMODEL_PREC_BITS) - 1)).toInt()
        val iy4 = (y4 shr WARPEDMODEL_PREC_BITS).toInt()
        val sy4 = (y4 and ((1L shl WARPEDMODEL_PREC_BITS) - 1)).toInt()
        val inter = warpIntermediate
        for (i1 in -7 until 8) {
            val row = (iy4 + i1).coerceIn(0, lastY) * refStride
            for (i2 in -4 until 4) {
                val sx = sx4 + alpha * i2 + beta * i1
                val offs = (round2(sx, WARPEDDIFF_PREC_BITS) + WARPEDPIXEL_PREC_SHIFTS) * 8
                var s = 0
                for (i3 in 0 until 8) s += filters[offs + i3] * ref[row + (ix4 + i2 - 3 + i3).coerceIn(0, lastX)]
                inter[(i1 + 7) * 8 + i2 + 4] = round2(s, interRound0)
            }
        }
        for (i1 in -4 until minOf(4, h - i8 * 8 - 4)) {
            for (i2 in -4 until minOf(4, w - j8 * 8 - 4)) {
                val sy = sy4 + gamma * i2 + delta * i1
                val offs = (round2(sy, WARPEDDIFF_PREC_BITS) + WARPEDPIXEL_PREC_SHIFTS) * 8
                var s = 0
                for (i3 in 0 until 8) s += filters[offs + i3] * inter[(i1 + i3 + 4) * 8 + i2 + 4]
                pred[(i8 * 8 + i1 + 4) * w + j8 * 8 + i2 + 4] = round2(s, interRound1)
            }
        }
    }

    /** The setup shear process for [warpParams]: alpha, beta, gamma and delta into [shear], and warpValid. */
    private fun setupShear(warpParams: IntArray): Boolean {
        val alpha0 = (warpParams[2] - (1 shl WARPEDMODEL_PREC_BITS)).coerceIn(-32768, 32767)
        val beta0 = warpParams[3].coerceIn(-32768, 32767)
        resolveDivisor(warpParams[2].toLong())
        val v = warpParams[4].toLong() shl WARPEDMODEL_PREC_BITS
        val gamma0 = Av1MvStack.round2Signed(v * divFactor, divShift).coerceIn(-32768, 32767).toInt()
        val w = warpParams[3].toLong() * warpParams[4]
        val delta0 = (warpParams[5] - Av1MvStack.round2Signed(w * divFactor, divShift) - (1 shl WARPEDMODEL_PREC_BITS))
            .coerceIn(-32768, 32767).toInt()
        shear[0] = Av1.round2Signed(alpha0, WARP_PARAM_REDUCE_BITS) shl WARP_PARAM_REDUCE_BITS
        shear[1] = Av1.round2Signed(beta0, WARP_PARAM_REDUCE_BITS) shl WARP_PARAM_REDUCE_BITS
        shear[2] = Av1.round2Signed(gamma0, WARP_PARAM_REDUCE_BITS) shl WARP_PARAM_REDUCE_BITS
        shear[3] = Av1.round2Signed(delta0, WARP_PARAM_REDUCE_BITS) shl WARP_PARAM_REDUCE_BITS
        if (4 * abs(shear[0]) + 7 * abs(shear[1]) >= (1 shl WARPEDMODEL_PREC_BITS)) return false
        if (4 * abs(shear[2]) + 4 * abs(shear[3]) >= (1 shl WARPEDMODEL_PREC_BITS)) return false
        return true
    }

    private var divShift = 0
    private var divFactor = 0L

    /** The resolve divisor process for [d0]. */
    private fun resolveDivisor(d0: Long) {
        val a = abs(d0)
        val n = 63 - a.countLeadingZeroBits()
        val e = a - (1L shl n)
        val f = if (n > DIV_LUT_BITS) Av1.round2(e, n - DIV_LUT_BITS) else e shl (DIV_LUT_BITS - n)
        divShift = n + DIV_LUT_PREC_BITS
        val lut = Av1Tables.divLut[f.toInt()].toLong()
        divFactor = if (d0 < 0) -lut else lut
    }

    /** The warp estimation process: LocalWarpParams and LocalValid from the samples find_warp_samples kept. */
    private fun warpEstimation() {
        val stack = d.mvStack
        var a00 = 0L
        var a01 = 0L
        var a11 = 0L
        var bx0 = 0L
        var bx1 = 0L
        var by0 = 0L
        var by1 = 0L
        val w4 = Av1.num4x4Wide[d.miSize]
        val h4 = Av1.num4x4High[d.miSize]
        val midY = d.miRow * 4 + h4 * 2 - 1
        val midX = d.miCol * 4 + w4 * 2 - 1
        val suy = midY * 8
        val sux = midX * 8
        val duy = suy + d.mv[0]
        val dux = sux + d.mv[1]
        for (i in 0 until stack.numSamples) {
            val sy = stack.candList[i * 4] - suy
            val sx = stack.candList[i * 4 + 1] - sux
            val dy = stack.candList[i * 4 + 2] - duy
            val dx = stack.candList[i * 4 + 3] - dux
            if (abs(sx - dx) < LS_MV_MAX && abs(sy - dy) < LS_MV_MAX) {
                a00 += lsProduct(sx, sx) + 8
                a01 += lsProduct(sx, sy) + 4
                a11 += lsProduct(sy, sy) + 8
                bx0 += lsProduct(sx, dx) + 8
                bx1 += lsProduct(sy, dx) + 4
                by0 += lsProduct(sx, dy) + 4
                by1 += lsProduct(sy, dy) + 8
            }
        }
        val det = a00 * a11 - a01 * a01
        localValid = det != 0L
        if (!localValid) return
        resolveDivisor(det)
        divShift -= WARPEDMODEL_PREC_BITS
        if (divShift < 0) {
            divFactor = divFactor shl (-divShift)
            divShift = 0
        }
        localWarpParams[2] = diag(a11 * bx0 - a01 * bx1)
        localWarpParams[3] = nondiag(-a01 * bx0 + a00 * bx1)
        localWarpParams[4] = nondiag(a11 * by0 - a01 * by1)
        localWarpParams[5] = diag(-a01 * by0 + a00 * by1)
        val vx = d.mv[1].toLong() * (1 shl (WARPEDMODEL_PREC_BITS - 3)) -
            (midX.toLong() * (localWarpParams[2] - (1 shl WARPEDMODEL_PREC_BITS)) + midY.toLong() * localWarpParams[3])
        val vy = d.mv[0].toLong() * (1 shl (WARPEDMODEL_PREC_BITS - 3)) -
            (midX.toLong() * localWarpParams[4] + midY.toLong() * (localWarpParams[5] - (1 shl WARPEDMODEL_PREC_BITS)))
        localWarpParams[0] = vx.coerceIn(-WARPEDMODEL_TRANS_CLAMP.toLong(), WARPEDMODEL_TRANS_CLAMP - 1L).toInt()
        localWarpParams[1] = vy.coerceIn(-WARPEDMODEL_TRANS_CLAMP.toLong(), WARPEDMODEL_TRANS_CLAMP - 1L).toInt()
    }

    private fun lsProduct(a: Int, b: Int): Long = ((a.toLong() * b) shr 2) + (a + b)

    private fun nondiag(v: Long): Int =
        Av1MvStack.round2Signed(v * divFactor, divShift)
            .coerceIn(-WARPEDMODEL_NONDIAGAFFINE_CLAMP + 1L, WARPEDMODEL_NONDIAGAFFINE_CLAMP - 1L).toInt()

    private fun diag(v: Long): Int =
        Av1MvStack.round2Signed(v * divFactor, divShift).coerceIn(
            (1L shl WARPEDMODEL_PREC_BITS) - WARPEDMODEL_NONDIAGAFFINE_CLAMP + 1,
            (1L shl WARPEDMODEL_PREC_BITS) + WARPEDMODEL_NONDIAGAFFINE_CLAMP - 1,
        ).toInt()

    /** The overlapped motion compensation process for a [w] by [h] prediction of [plane]. */
    private fun overlappedMotionCompensation(plane: Int, w: Int, h: Int) {
        val subX = if (plane > 0) d.subX else 0
        val subY = if (plane > 0) d.subY else 0
        if (d.availU && Av1.subsampledSize(d.miSize, subX, subY) >= Av1.BLOCK_8X8) {
            val w4 = Av1.num4x4Wide[d.miSize]
            var x4 = d.miCol
            val y4 = d.miRow
            var nCount = 0
            val nLimit = minOf(4, Av1.miWidthLog2[d.miSize])
            while (nCount < nLimit && x4 < minOf(d.miCols, d.miCol + w4)) {
                val candRow = d.miRow - 1
                val candCol = x4 or 1
                val c = d.mi(candRow, candCol)
                val step4 = Av1.num4x4Wide[d.miSizes[c]].coerceIn(2, 16)
                if (d.refFrames0[c] > INTRA_FRAME) {
                    nCount++
                    val predW = minOf(w, (step4 * Av1.MI_SIZE) shr subX)
                    val predH = minOf(h shr 1, 32 shr subY)
                    predictOverlap(plane, c, x4, y4, predW, predH, 0, obmcMask(predH))
                }
                x4 += step4
            }
        }
        if (d.availL) {
            val h4 = Av1.num4x4High[d.miSize]
            val x4 = d.miCol
            var y4 = d.miRow
            var nCount = 0
            val nLimit = minOf(4, Av1.miHeightLog2[d.miSize])
            while (nCount < nLimit && y4 < minOf(d.miRows, d.miRow + h4)) {
                val candCol = d.miCol - 1
                val candRow = y4 or 1
                val c = d.mi(candRow, candCol)
                val step4 = Av1.num4x4High[d.miSizes[c]].coerceIn(2, 16)
                if (d.refFrames0[c] > INTRA_FRAME) {
                    nCount++
                    val predW = minOf(w shr 1, 32 shr subX)
                    val predH = minOf(h, (step4 * Av1.MI_SIZE) shr subY)
                    predictOverlap(plane, c, x4, y4, predW, predH, 1, obmcMask(predW))
                }
                y4 += step4
            }
        }
    }

    private fun obmcMask(length: Int): IntArray = when (length) {
        2 -> Av1Tables.obmcMask2
        4 -> Av1Tables.obmcMask4
        8 -> Av1Tables.obmcMask8
        16 -> Av1Tables.obmcMask16
        else -> Av1Tables.obmcMask32
    }

    /** predict_overlap( ) and the overlap blending process, from the neighbour at [c]. */
    private fun predictOverlap(plane: Int, c: Int, x4: Int, y4: Int, predW: Int, predH: Int, pass: Int, obmcMask: IntArray) {
        val subX = if (plane > 0) d.subX else 0
        val subY = if (plane > 0) d.subY else 0
        selectReference(d.fh.refFrameIdx[d.refFrames0[c] - Av1.LAST_FRAME])
        val predX = (x4 * 4) shr subX
        val predY = (y4 * 4) shr subY
        scaleMotionVector(plane, predX, predY, d.mvs[c * 4], d.mvs[c * 4 + 1])
        blockInterPrediction(plane, startX, startY, stepX, stepY, predW, predH, c, obmcPred, predW)
        val maxValue = (1 shl d.bitDepth) - 1
        val out = d.frame[plane]
        val stride = d.planeWidth[plane]
        for (i in 0 until predH) {
            val row = (predY + i) * stride + predX
            for (j in 0 until predW) {
                val obmc = obmcPred[i * predW + j].coerceIn(0, maxValue)
                val m = if (pass == 0) obmcMask[i] else obmcMask[j]
                out[row + j] = round2(m * out[row + j] + (64 - m) * obmc, 6).toShort()
            }
        }
    }

    /** The wedge mask process. */
    private fun wedgeMask(w: Int, h: Int) {
        val src = Av1WedgeMasks.mask(d.miSize, d.wedgeSign, d.wedgeIndex)
        src.copyInto(mask, 0, 0, w * h)
        maskStride = w
    }

    /** The difference weight mask process, from the two luma predictions. */
    private fun differenceWeightMask(w: Int, h: Int) {
        val p0 = preds[0]
        val p1 = preds[1]
        val shift = (d.bitDepth - 8) + interPostRound
        for (i in 0 until w * h) {
            val diff = round2(abs(p0[i] - p1[i]), shift)
            val m = (38 + diff / 16).coerceIn(0, 64)
            mask[i] = if (d.maskType != 0) 64 - m else m
        }
        maskStride = w
    }

    /** The intra mode variant mask process, for the plane being predicted. */
    private fun intraModeVariantMask(w: Int, h: Int) {
        val sizeScale = MAX_SB / maxOf(h, w)
        val weights = Av1Tables.iiWeights1d
        for (i in 0 until h) for (j in 0 until w) {
            mask[i * w + j] = when (d.interintraMode) {
                Av1.II_V_PRED -> weights[i * sizeScale]
                Av1.II_H_PRED -> weights[j * sizeScale]
                Av1.II_SMOOTH_PRED -> weights[minOf(i, j) * sizeScale]
                else -> 32
            }
        }
        maskStride = w
    }

    /** The mask blend process. */
    private fun maskBlend(plane: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        val subX = if (plane > 0) d.subX else 0
        val subY = if (plane > 0) d.subY else 0
        val out = d.frame[plane]
        val stride = d.planeWidth[plane]
        val maxValue = (1 shl d.bitDepth) - 1
        val p0 = preds[0]
        val p1 = preds[1]
        val ms = maskStride
        val direct = (subX == 0 && subY == 0) || (d.interintra && !d.wedgeInterintra)
        for (y in 0 until h) {
            val row = (y + dstY) * stride + dstX
            for (x in 0 until w) {
                val m = when {
                    direct -> mask[y * ms + x]
                    subX != 0 && subY == 0 -> round2(mask[y * ms + 2 * x] + mask[y * ms + 2 * x + 1], 1)
                    else -> round2(
                        mask[2 * y * ms + 2 * x] + mask[2 * y * ms + 2 * x + 1] +
                            mask[(2 * y + 1) * ms + 2 * x] + mask[(2 * y + 1) * ms + 2 * x + 1],
                        2,
                    )
                }
                if (d.interintra) {
                    val pred0 = round2(p0[y * w + x], interPostRound).coerceIn(0, maxValue)
                    val pred1 = out[row + x].toInt()
                    out[row + x] = round2(m * pred1 + (64 - m) * pred0, 6).toShort()
                } else {
                    out[row + x] = round2(m * p0[y * w + x] + (64 - m) * p1[y * w + x], 6 + interPostRound).coerceIn(0, maxValue).toShort()
                }
            }
        }
    }

    /** The distance weights process for the block at [m]. */
    private fun distanceWeights(m: Int) {
        val fh = d.fh
        val dist = IntArray(2)
        for (refList in 0 until 2) {
            val h = fh.orderHints[if (refList == 0) d.refFrames0[m] else d.refFrames1[m]]
            dist[refList] = abs(fh.relativeDist(h, fh.orderHint)).coerceIn(0, Av1.MAX_FRAME_DISTANCE)
        }
        val d0 = dist[1]
        val d1 = dist[0]
        val order = if (d0 <= d1) 1 else 0
        val lookup = Av1Tables.quantDistLookup
        if (d0 == 0 || d1 == 0) {
            fwdWeight = lookup[3 * 2 + order]
            bckWeight = lookup[3 * 2 + 1 - order]
            return
        }
        val weight = Av1Tables.quantDistWeight
        var i = 0
        while (i < 3) {
            val c0 = weight[i * 2 + order]
            val c1 = weight[i * 2 + 1 - order]
            if (order == 1) {
                if (d0 * c0 > d1 * c1) break
            } else {
                if (d0 * c0 < d1 * c1) break
            }
            i++
        }
        fwdWeight = lookup[i * 2 + order]
        bckWeight = lookup[i * 2 + 1 - order]
    }

    companion object {
        private const val MAX_SB = 128
        private const val FILTER_BITS = 7
        private const val REF_SCALE_SHIFT = 14
        private const val SUBPEL_BITS = 4
        private const val SUBPEL_MASK = 15
        private const val SCALE_SUBPEL_BITS = 10
        private const val WARPEDMODEL_PREC_BITS = 16
        private const val WARPEDPIXEL_PREC_SHIFTS = 1 shl 6
        private const val WARPEDDIFF_PREC_BITS = 10
        private const val WARP_PARAM_REDUCE_BITS = 6
        private const val WARPEDMODEL_TRANS_CLAMP = 1 shl 23
        private const val WARPEDMODEL_NONDIAGAFFINE_CLAMP = 1 shl 13
        private const val DIV_LUT_BITS = 8
        private const val DIV_LUT_PREC_BITS = 14
        private const val LS_MV_MAX = 256
    }
}

/** WedgeMasks[ bsize ][ flipSign ][ wedge ], built once by initialise_wedge_mask_table( ) when a wedge is first used. */
internal object Av1WedgeMasks {
    private const val MASK_MASTER_SIZE = 64
    private const val WEDGE_TYPES = 16
    private const val WEDGE_VERTICAL = 1
    private const val WEDGE_OBLIQUE27 = 2
    private const val WEDGE_OBLIQUE63 = 3
    private const val WEDGE_OBLIQUE117 = 4
    private const val WEDGE_OBLIQUE153 = 5
    private const val WEDGE_HORIZONTAL = 0

    private val masks: Array<Array<ByteArray>?> by lazy { build() }

    /** The mask for block size [bsize], sign [sign] and wedge [index], its rows Block_Width[ bsize ] apart. */
    fun mask(bsize: Int, sign: Int, index: Int): IntArray {
        val all = masks[bsize] ?: throw ImageDecodeException("AV1: a wedge on a block size without wedges")
        val bytes = all[sign * WEDGE_TYPES + index]
        return IntArray(bytes.size) { bytes[it].toInt() }
    }

    private fun build(): Array<Array<ByteArray>?> {
        val n = MASK_MASTER_SIZE
        val master = Array(6) { IntArray(n * n) }
        val t = Av1Tables
        for (j in 0 until n) {
            var shift = n / 4
            var i = 0
            while (i < n) {
                master[WEDGE_OBLIQUE63][i * n + j] = t.wedgeMasterObliqueEven[(j - shift).coerceIn(0, n - 1)]
                shift -= 1
                master[WEDGE_OBLIQUE63][(i + 1) * n + j] = t.wedgeMasterObliqueOdd[(j - shift).coerceIn(0, n - 1)]
                master[WEDGE_VERTICAL][i * n + j] = t.wedgeMasterVertical[j]
                master[WEDGE_VERTICAL][(i + 1) * n + j] = t.wedgeMasterVertical[j]
                i += 2
            }
        }
        for (i in 0 until n) for (j in 0 until n) {
            val msk = master[WEDGE_OBLIQUE63][i * n + j]
            master[WEDGE_OBLIQUE27][j * n + i] = msk
            master[WEDGE_OBLIQUE117][i * n + (n - 1 - j)] = 64 - msk
            master[WEDGE_OBLIQUE153][(n - 1 - j) * n + i] = 64 - msk
            master[WEDGE_HORIZONTAL][j * n + i] = master[WEDGE_VERTICAL][i * n + j]
        }
        return Array(22) { bsize ->
            if (bsize < Av1.BLOCK_8X8 || t.wedgeBits[bsize] == 0) return@Array null
            val w = Av1.blockWidth[bsize]
            val h = Av1.blockHeight[bsize]
            val w4 = Av1.num4x4Wide[bsize]
            val h4 = Av1.num4x4High[bsize]
            val shape = if (h4 > w4) 0 else if (h4 < w4) 1 else 2
            val out = arrayOfNulls<ByteArray>(2 * WEDGE_TYPES)
            for (wedge in 0 until WEDGE_TYPES) {
                val code = (shape * WEDGE_TYPES + wedge) * 3
                val dir = t.wedgeCodebook[code]
                val xoff = n / 2 - ((t.wedgeCodebook[code + 1] * w) shr 3)
                val yoff = n / 2 - ((t.wedgeCodebook[code + 2] * h) shr 3)
                var sum = 0
                for (i in 0 until w) sum += master[dir][yoff * n + xoff + i]
                for (i in 1 until h) sum += master[dir][(yoff + i) * n + xoff]
                val avg = (sum + (w + h - 1) / 2) / (w + h - 1)
                val flipSign = if (avg < 32) 1 else 0
                val a = ByteArray(w * h)
                val b = ByteArray(w * h)
                for (i in 0 until h) for (j in 0 until w) {
                    val v = master[dir][(yoff + i) * n + xoff + j]
                    a[i * w + j] = v.toByte()
                    b[i * w + j] = (64 - v).toByte()
                }
                out[flipSign * WEDGE_TYPES + wedge] = a
                out[(1 - flipSign) * WEDGE_TYPES + wedge] = b
            }
            Array(2 * WEDGE_TYPES) { out[it]!! }
        }
    }
}
