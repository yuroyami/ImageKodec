package io.github.yuroyami.imagekodec.codec

/**
 * The YUV 4:2:0 planes a VP8 key frame codes, each padded to whole macroblocks by repeating the
 * last column and row of the picture.
 */
internal class Vp8Planes(val width: Int, val height: Int) {
    val mbW = (width + 15) shr 4
    val mbH = (height + 15) shr 4
    val yStride = mbW * 16
    val uvStride = mbW * 8
    val y = ByteArray(yStride * mbH * 16)
    val u = ByteArray(uvStride * mbH * 8)
    val v = ByteArray(uvStride * mbH * 8)
}

/**
 * A lossy VP8 key frame encoder, written from RFC 6386 (#178). Every macroblock is predicted from
 * its reconstructed neighbours as the decoder will predict it, and each choice of prediction mode
 * (16x16 or sixteen 4x4 modes for luma, one of four for chroma) is the one that costs least in
 * distortion plus a lambda times the bits it takes, the bits counted from the token
 * probabilities the frame will carry. At effort 3 a trellis also picks each coefficient's level
 * that way. The macroblocks fall into up to four segments by how busy their luma is, and a busy
 * segment takes a coarser quantizer, where the eye sees the loss less. The token probabilities are
 * then fitted to the counted tokens, and an update is sent for every probability whose saving pays
 * for its own cost. The loop filter level follows each segment's quantizer.
 *
 * Nothing here comes from libwebp's encoder: the tuning constants were set by measuring the size
 * at an equal SSIMULACRA 2 score against `cwebp -m 4`, which is only run as a tool.
 */
internal class Vp8Encoder private constructor(
    private val planes: Vp8Planes,
    private val quality: Int,
    private val effort: Int,
    private val cancel: (() -> Unit)?,
    private val filterOff: Boolean = false,
) {
    private val mbW = planes.mbW
    private val mbH = planes.mbH
    private val yStride = planes.yStride
    private val uvStride = planes.uvStride
    private val mbCount = mbW * mbH

    // Reconstruction before the loop filter, which is what intra prediction reads.
    private val recY = ByteArray(planes.y.size)
    private val recU = ByteArray(planes.u.size)
    private val recV = ByteArray(planes.v.size)

    /** One segment's quantizer steps and the weights of its decisions. */
    private class Segment(val index: Int) {
        var y1Dc = 0
        var y1Ac = 0
        var y2Dc = 0
        var y2Ac = 0
        var uvDc = 0
        var uvAc = 0

        /**
         * Score weight of a 1/256 bit, against squared error times 4096. Scores are whole numbers
         * far below 2^53, so Double holds them exactly, and on JavaScript is faster than Long.
         */
        var lambda16 = 0.0
        var lambda4 = 0.0
        var lambdaUv = 0.0
        var filterLevel = 0
    }

    private val segments = Array(4) { Segment(it) }
    private var segmentCount = 1
    private val segmentOf = ByteArray(mbCount)
    private val segmentQ = IntArray(4)

    // Decisions, one entry a macroblock.
    private val isI4 = BooleanArray(mbCount)
    private val mode16 = ByteArray(mbCount)
    private val modes4 = ByteArray(mbCount * 16)
    private val uvMode = ByteArray(mbCount)
    private val skip = BooleanArray(mbCount)

    /** The quantized levels of each block in zigzag order: a count, then that many levels. */
    private var levels = ShortArray(1 shl 16)
    private var levelsSize = 0
    private val levelsStart = IntArray(mbCount + 1)

    /** Token probabilities the costs are read from, [type][band][context][node] as RFC 6386 13.4. */
    private val proba = Vp8Tables.COEFFS_PROBA_0.copyOf()

    /** Cost of each token from the zero node on, [type][band][context][token]. */
    private val tokenCost = IntArray(NUM_CTX * 11)
    private var useSkip = false
    private var skipProb = 255
    private var filterSharpness = 0
    private var filterLevelBase = 0

    // --- neighbour state of one pass ------------------------------------------------------------

    private val nzTopY = IntArray(mbW * 4)
    private val nzTopU = IntArray(mbW * 2)
    private val nzTopV = IntArray(mbW * 2)
    private val nzDcTop = IntArray(mbW)
    private val nzLeftY = IntArray(4)
    private val nzLeftU = IntArray(2)
    private val nzLeftV = IntArray(2)
    private var nzDcLeft = 0
    private val modeTop = IntArray(mbW * 4)
    private val modeLeft = IntArray(4)

    // --- work buffers of one macroblock ---------------------------------------------------------

    private val srcY = IntArray(256)
    private val srcU = IntArray(64)
    private val srcV = IntArray(64)
    private val topY = IntArray(21) // [0] top-left, [1..16] above, [17..20] above-right
    private val leftY = IntArray(16)
    private val topU = IntArray(9)
    private val leftU = IntArray(8)
    private val topV = IntArray(9)
    private val leftV = IntArray(8)
    private var hasTop = false
    private var hasLeft = false

    private val pred = IntArray(256)
    private val coeffs = IntArray(16 * 16)
    private val dcs = IntArray(16)
    private val wht = IntArray(16)
    private val tmp = IntArray(16)
    private val scratch = IntArray(16)

    // The best 16x16 candidate and the one being tried.
    private val i16Rec = IntArray(256)
    private val i16Lev = IntArray(17 * 16)
    private val tryRec = IntArray(256)
    private val tryLev = IntArray(17 * 16)

    // The 4x4 candidates.
    private val i4Rec = IntArray(256)
    private val i4Lev = IntArray(16 * 16)
    private val i4Modes = IntArray(16)
    private val edge = IntArray(13)
    private val p4 = IntArray(16)
    private val r4 = IntArray(16)
    private val l4 = IntArray(16)
    private val d4 = IntArray(16)
    private val best4Rec = IntArray(16)
    private val best4Lev = IntArray(16)
    private val candidates = IntArray(10)
    private val preScore = DoubleArray(10)

    // Chroma candidates: levels of 4 U then 4 V blocks, reconstruction of U then V.
    private val uvRec = IntArray(128)
    private val uvLev = IntArray(8 * 16)
    private val tryUvRec = IntArray(128)
    private val tryUvLev = IntArray(8 * 16)
    private val predU = IntArray(64)
    private val predV = IntArray(64)

    private fun run(): ByteArray {
        analyse()
        val passes = if (effort >= 2) 2 else 1
        for (pass in 0 until passes) {
            computeTokenCosts()
            encodePass()
            if (pass < passes - 1) fitProbabilities()
        }
        fitProbabilities()
        decideSkip()
        val tokens = writeTokens()
        chooseFilter()
        val first = writeFirstPartition()
        require(first.size < (1 shl 19)) {
            "a ${planes.width}x${planes.height} picture needs a VP8 first partition of ${first.size} bytes, " +
                "past the format's 524287"
        }
        return frame(first, tokens)
    }

    private fun frame(first: ByteArray, tokens: ByteArray): ByteArray {
        val out = ByteArray(10 + first.size + tokens.size)
        val tag = (first.size shl 5) or (1 shl 4) // key frame, version 0, shown
        out[0] = tag.toByte()
        out[1] = (tag ushr 8).toByte()
        out[2] = (tag ushr 16).toByte()
        out[3] = 0x9D.toByte()
        out[4] = 0x01
        out[5] = 0x2A
        out[6] = planes.width.toByte()
        out[7] = (planes.width ushr 8).toByte()
        out[8] = planes.height.toByte()
        out[9] = (planes.height ushr 8).toByte()
        first.copyInto(out, 10)
        tokens.copyInto(out, 10 + first.size)
        return out
    }

    // --- analysis and quantizers ----------------------------------------------------------------

    /**
     * Sort the macroblocks into segments by the AC energy of their luma, and give each segment its
     * quantizer: the base one for the quality, finer for flat blocks and coarser for busy ones.
     */
    private fun analyse() {
        val base = baseQuantizer(quality)
        val activity = IntArray(mbCount)
        val block = IntArray(16)
        val out = IntArray(16)
        for (mbY in 0 until mbH) for (mbX in 0 until mbW) {
            var sum = 0
            for (b in 0 until 16) {
                val x0 = mbX * 16 + (b and 3) * 4
                val y0 = mbY * 16 + (b shr 2) * 4
                for (j in 0 until 4) for (i in 0 until 4) block[j * 4 + i] = planes.y[(y0 + j) * yStride + x0 + i].toInt() and 0xFF
                fdct(block, out, 0, scratch)
                for (k in 1 until 16) sum += if (out[k] < 0) -out[k] else out[k]
            }
            // Log scale, so that a doubling of energy is one step whatever the level.
            activity[mbY * mbW + mbX] = log2Q8(1 + sum / 16)
        }
        val centres = kMeans(activity, 4)
        val distinct = centres.distinct()
        if (base == 0 || distinct.size < 2 || mbCount < 4) {
            segmentCount = 1
            segmentOf.fill(0)
            segmentQ[0] = base
        } else {
            segmentCount = 4
            var mean = 0L
            for (a in activity) mean += a
            val mid = (mean / mbCount).toInt()
            for (i in 0 until mbCount) segmentOf[i] = nearest(centres, activity[i]).toByte()
            for (s in 0 until 4) {
                // SNS_STRENGTH quantizer steps for each doubling of the energy away from the mean.
                val delta = ((centres[s] - mid) * SNS_STRENGTH) shr 8
                segmentQ[s] = (base + delta).coerceIn(0, 127)
            }
        }
        for (s in 0 until 4) setQuantizer(segments[s], segmentQ[s])
    }

    private fun setQuantizer(seg: Segment, q: Int) {
        val dc = Vp8Tables.DC_TABLE
        val ac = Vp8Tables.AC_TABLE
        // The dequantization factors of RFC 6386 section 14.1.
        seg.y1Dc = dc[q]
        seg.y1Ac = ac[q]
        seg.y2Dc = dc[q] * 2
        seg.y2Ac = maxOf(8, ac[q] * 155 / 100)
        seg.uvDc = dc[minOf(q, 117)]
        seg.uvAc = ac[q]
        val step = seg.y1Ac.toLong()
        seg.lambda16 = (step * step * LAMBDA_I16 shr 4).toDouble()
        seg.lambda4 = (step * step * LAMBDA_I4 shr 4).toDouble()
        seg.lambdaUv = (step * step * LAMBDA_UV shr 4).toDouble()
        seg.filterLevel = (FILTER_BASE + seg.y1Ac * FILTER_SLOPE / 64).coerceIn(0, 63).let { if (q == 0) 0 else it }
    }

    // --- one pass over the macroblocks ----------------------------------------------------------

    private fun encodePass() {
        nzTopY.fill(0); nzTopU.fill(0); nzTopV.fill(0); nzDcTop.fill(0)
        modeTop.fill(B_DC)
        levelsSize = 0
        for (mbY in 0 until mbH) {
            nzLeftY.fill(0); nzLeftU.fill(0); nzLeftV.fill(0); nzDcLeft = 0
            modeLeft.fill(B_DC)
            for (mbX in 0 until mbW) encodeMacroblock(mbX, mbY)
            cancel?.invoke()
        }
        levelsStart[mbCount] = levelsSize
    }

    private fun loadContext(mbX: Int, mbY: Int) {
        hasTop = mbY > 0
        hasLeft = mbX > 0
        val x0 = mbX * 16
        val y0 = mbY * 16
        for (j in 0 until 16) {
            val row = (y0 + j) * yStride + x0
            for (i in 0 until 16) srcY[j * 16 + i] = planes.y[row + i].toInt() and 0xFF
        }
        val cx = mbX * 8
        val cy = mbY * 8
        for (j in 0 until 8) {
            val row = (cy + j) * uvStride + cx
            for (i in 0 until 8) {
                srcU[j * 8 + i] = planes.u[row + i].toInt() and 0xFF
                srcV[j * 8 + i] = planes.v[row + i].toInt() and 0xFF
            }
        }
        // The edges RFC 6386 section 12.2 and 12.3 give a macroblock: 127 above the picture, 129
        // left of it, and above-right past the last column the last pixel above, repeated.
        if (hasTop) {
            val above = (y0 - 1) * yStride + x0
            for (i in 0 until 16) topY[1 + i] = recY[above + i].toInt() and 0xFF
            for (i in 0 until 4) {
                topY[17 + i] = if (mbX < mbW - 1) recY[above + 16 + i].toInt() and 0xFF else topY[16]
            }
            topY[0] = if (hasLeft) recY[above - 1].toInt() and 0xFF else 129
            val aboveUv = (cy - 1) * uvStride + cx
            for (i in 0 until 8) {
                topU[1 + i] = recU[aboveUv + i].toInt() and 0xFF
                topV[1 + i] = recV[aboveUv + i].toInt() and 0xFF
            }
            topU[0] = if (hasLeft) recU[aboveUv - 1].toInt() and 0xFF else 129
            topV[0] = if (hasLeft) recV[aboveUv - 1].toInt() and 0xFF else 129
        } else {
            topY.fill(127); topU.fill(127); topV.fill(127)
        }
        if (hasLeft) {
            for (j in 0 until 16) leftY[j] = recY[(y0 + j) * yStride + x0 - 1].toInt() and 0xFF
            for (j in 0 until 8) {
                leftU[j] = recU[(cy + j) * uvStride + cx - 1].toInt() and 0xFF
                leftV[j] = recV[(cy + j) * uvStride + cx - 1].toInt() and 0xFF
            }
        } else {
            leftY.fill(129); leftU.fill(129); leftV.fill(129)
        }
    }

    private fun encodeMacroblock(mbX: Int, mbY: Int) {
        val mb = mbY * mbW + mbX
        val seg = segments[segmentOf[mb].toInt()]
        loadContext(mbX, mbY)

        // 16x16 luma: each of the four modes, coded in full.
        var bestI16 = Double.MAX_VALUE
        var bestMode16 = DC_PRED
        for (mode in 0 until 4) {
            val score = tryI16(mode, seg, mbX)
            if (score < bestI16) {
                bestI16 = score
                bestMode16 = mode
                tryRec.copyInto(i16Rec)
                tryLev.copyInto(i16Lev)
            }
        }
        val scoreI4 = tryI4(seg, mbX, bestI16)
        val useI4 = scoreI4 < bestI16
        isI4[mb] = useI4
        mode16[mb] = bestMode16.toByte()

        // Chroma.
        var bestUv = Double.MAX_VALUE
        var bestUvMode = DC_PRED
        for (mode in 0 until 4) {
            val score = tryUv(mode, seg, mbX)
            if (score < bestUv) {
                bestUv = score
                bestUvMode = mode
                tryUvRec.copyInto(uvRec)
                tryUvLev.copyInto(uvLev)
            }
        }
        uvMode[mb] = bestUvMode.toByte()

        // Commit the reconstruction and the levels.
        val rec = if (useI4) i4Rec else i16Rec
        val x0 = mbX * 16
        val y0 = mbY * 16
        for (j in 0 until 16) {
            val row = (y0 + j) * yStride + x0
            for (i in 0 until 16) recY[row + i] = rec[j * 16 + i].toByte()
        }
        val cx = mbX * 8
        val cy = mbY * 8
        for (j in 0 until 8) {
            val row = (cy + j) * uvStride + cx
            for (i in 0 until 8) {
                recU[row + i] = uvRec[j * 8 + i].toByte()
                recV[row + i] = uvRec[64 + j * 8 + i].toByte()
            }
        }
        levelsStart[mb] = levelsSize
        var any = false
        if (!useI4) {
            any = storeBlock(i16Lev, 256) or any
            for (b in 0 until 16) any = storeBlock(i16Lev, b * 16) or any
        } else {
            for (b in 0 until 16) any = storeBlock(i4Lev, b * 16) or any
        }
        for (b in 0 until 8) any = storeBlock(uvLev, b * 16) or any
        skip[mb] = !any

        // Neighbour state for the macroblocks to come.
        if (useI4) {
            for (b in 0 until 16) modes4[mb * 16 + b] = i4Modes[b].toByte()
            for (i in 0 until 4) {
                modeTop[mbX * 4 + i] = i4Modes[12 + i]
                modeLeft[i] = i4Modes[i * 4 + 3]
            }
        } else {
            for (i in 0 until 4) {
                modeTop[mbX * 4 + i] = bestMode16
                modeLeft[i] = bestMode16
            }
            nzDcTop[mbX] = if (lastNonZero(i16Lev, 256, 0) >= 0) 1 else 0
            nzDcLeft = nzDcTop[mbX]
        }
        val lev = if (useI4) i4Lev else i16Lev
        val first = if (useI4) 0 else 1
        for (by in 0 until 4) for (bx in 0 until 4) {
            val nz = if (lastNonZero(lev, (by * 4 + bx) * 16, first) >= 0) 1 else 0
            if (by == 3) nzTopY[mbX * 4 + bx] = nz
            if (bx == 3) nzLeftY[by] = nz
        }
        for (by in 0 until 2) for (bx in 0 until 2) {
            val nzU = if (lastNonZero(uvLev, (by * 2 + bx) * 16, 0) >= 0) 1 else 0
            val nzV = if (lastNonZero(uvLev, (4 + by * 2 + bx) * 16, 0) >= 0) 1 else 0
            if (by == 1) { nzTopU[mbX * 2 + bx] = nzU; nzTopV[mbX * 2 + bx] = nzV }
            if (bx == 1) { nzLeftU[by] = nzU; nzLeftV[by] = nzV }
        }
    }

    private fun storeBlock(lev: IntArray, at: Int): Boolean {
        val last = lastNonZero(lev, at, 0)
        val n = last + 1
        if (levelsSize + n + 1 > levels.size) levels = levels.copyOf(maxOf(levels.size * 2, levelsSize + n + 1))
        levels[levelsSize++] = n.toShort()
        for (k in 0 until n) levels[levelsSize++] = lev[at + k].toShort()
        return n > 0
    }

    // --- 16x16 luma -----------------------------------------------------------------------------

    private fun tryI16(mode: Int, seg: Segment, mbX: Int): Double {
        predict16(mode, pred)
        for (b in 0 until 16) {
            val bx = (b and 3) * 4
            val by = (b shr 2) * 4
            for (j in 0 until 4) for (i in 0 until 4) {
                val k = (by + j) * 16 + bx + i
                tmp[j * 4 + i] = srcY[k] - pred[k]
            }
            fdct(tmp, coeffs, b * 16, scratch)
            dcs[b] = coeffs[b * 16]
        }
        fwht(dcs, wht, scratch)
        quantize(wht, 0, seg.y2Dc, seg.y2Ac, 0, tryLev, 256, Y2_ROUND)
        // The DC terms come back through the inverse Walsh-Hadamard transform, as the decoder's do.
        dequantize(tryLev, 256, seg.y2Dc, seg.y2Ac, tmp)
        iwht(tmp, dcs, scratch)
        var rate = MODE16_COST[mode]
        val dcCtx = nzDcTop[mbX] + nzDcLeft
        rate += blockCost(tryLev, 256, 0, TYPE_Y2, dcCtx)
        var tnz = 0
        var lnz = 0
        for (i in 0 until 4) { tnz = tnz or (nzTopY[mbX * 4 + i] shl i); lnz = lnz or (nzLeftY[i] shl i) }
        var sse = 0
        for (b in 0 until 16) {
            val bx = b and 3
            val by = b shr 2
            val ctx = ((tnz shr bx) and 1) + ((lnz shr by) and 1)
            if (effort >= 3) {
                trellis(coeffs, b * 16, seg.y1Dc, seg.y1Ac, 1, TYPE_Y_AFTER_Y2, ctx, seg.lambda16 * TRELLIS / 16, tryLev, b * 16)
            } else {
                quantize(coeffs, b * 16, seg.y1Dc, seg.y1Ac, 1, tryLev, b * 16, Y1_ROUND)
            }
            rate += blockCost(tryLev, b * 16, 1, TYPE_Y_AFTER_Y2, ctx)
            val nz = if (lastNonZero(tryLev, b * 16, 1) >= 0) 1 else 0
            tnz = (tnz and (1 shl bx).inv()) or (nz shl bx)
            lnz = (lnz and (1 shl by).inv()) or (nz shl by)
            dequantize(tryLev, b * 16, seg.y1Dc, seg.y1Ac, tmp)
            tmp[0] = dcs[b]
            sse += reconstruct4(tmp, pred, (by * 4) * 16 + bx * 4, 16, tryRec, srcY)
        }
        return sse * 4096.0 + seg.lambda16 * rate
    }

    private fun predict16(mode: Int, out: IntArray) {
        when (mode) {
            DC_PRED -> {
                var sum = 0
                val dc = if (hasTop && hasLeft) {
                    for (i in 0 until 16) sum += topY[1 + i] + leftY[i]
                    (sum + 16) shr 5
                } else if (hasTop) {
                    for (i in 0 until 16) sum += topY[1 + i]
                    (sum + 8) shr 4
                } else if (hasLeft) {
                    for (i in 0 until 16) sum += leftY[i]
                    (sum + 8) shr 4
                } else {
                    128
                }
                out.fill(dc, 0, 256)
            }
            V_PRED -> for (j in 0 until 16) for (i in 0 until 16) out[j * 16 + i] = topY[1 + i]
            H_PRED -> for (j in 0 until 16) for (i in 0 until 16) out[j * 16 + i] = leftY[j]
            else -> for (j in 0 until 16) for (i in 0 until 16) out[j * 16 + i] = clip255(leftY[j] + topY[1 + i] - topY[0])
        }
    }

    // --- 4x4 luma -------------------------------------------------------------------------------

    /** The best sixteen 4x4 modes, in [i4Rec], [i4Lev] and [i4Modes]; gives up past [limit]. */
    private fun tryI4(seg: Segment, mbX: Int, limit: Double): Double {
        var score = seg.lambda4 * I4_FLAG_COST
        var tnz = 0
        var lnz = 0
        for (i in 0 until 4) { tnz = tnz or (nzTopY[mbX * 4 + i] shl i); lnz = lnz or (nzLeftY[i] shl i) }
        for (b in 0 until 16) {
            val bx = b and 3
            val by = b shr 2
            loadEdge(bx, by)
            for (j in 0 until 4) for (i in 0 until 4) l4[j * 4 + i] = srcY[(by * 4 + j) * 16 + bx * 4 + i]
            val topMode = if (by == 0) modeTop[mbX * 4 + bx] else i4Modes[b - 4]
            val leftMode = if (bx == 0) modeLeft[by] else i4Modes[b - 1]
            val probs = (topMode * 10 + leftMode) * 9
            val ctx = ((tnz shr bx) and 1) + ((lnz shr by) and 1)
            val tried = if (effort == 1) {
                // Effort 1 codes only the three modes whose prediction alone scores best.
                for (m in 0 until 10) {
                    predict4(m, edge, p4)
                    var e = 0
                    for (k in 0 until 16) { val d = l4[k] - p4[k]; e += d * d }
                    preScore[m] = e * 4096.0 + seg.lambda4 * bmodeCost(probs, m)
                    candidates[m] = m
                }
                for (i in 0 until 3) {
                    var min = i
                    for (j in i + 1 until 10) if (preScore[candidates[j]] < preScore[candidates[min]]) min = j
                    val t = candidates[i]; candidates[i] = candidates[min]; candidates[min] = t
                }
                3
            } else {
                for (m in 0 until 10) candidates[m] = m
                10
            }
            var best = Double.MAX_VALUE
            var bestMode = 0
            for (c in 0 until tried) {
                val m = candidates[c]
                predict4(m, edge, p4)
                for (k in 0 until 16) r4[k] = l4[k] - p4[k]
                fdct(r4, d4, 0, scratch)
                if (effort >= 3) {
                    trellis(d4, 0, seg.y1Dc, seg.y1Ac, 0, TYPE_I4, ctx, seg.lambda4 * TRELLIS / 16, r4, 0)
                } else {
                    quantize(d4, 0, seg.y1Dc, seg.y1Ac, 0, r4, 0, Y1_ROUND)
                }
                val rate = bmodeCost(probs, m) + blockCost(r4, 0, 0, TYPE_I4, ctx)
                dequantize(r4, 0, seg.y1Dc, seg.y1Ac, tmp)
                val sse = reconstruct4(tmp, p4, 0, 4, d4, l4)
                val s = sse * 4096.0 + seg.lambda4 * rate
                if (s < best) {
                    best = s
                    bestMode = m
                    d4.copyInto(best4Rec)
                    r4.copyInto(best4Lev)
                }
            }
            score += best
            if (score >= limit) return Double.MAX_VALUE
            i4Modes[b] = bestMode
            best4Lev.copyInto(i4Lev, b * 16)
            for (j in 0 until 4) for (i in 0 until 4) i4Rec[(by * 4 + j) * 16 + bx * 4 + i] = best4Rec[j * 4 + i]
            val nz = if (lastNonZero(best4Lev, 0, 0) >= 0) 1 else 0
            tnz = (tnz and (1 shl bx).inv()) or (nz shl bx)
            lnz = (lnz and (1 shl by).inv()) or (nz shl by)
        }
        return score
    }

    private fun bmodeCost(probs: Int, mode: Int): Int {
        val p = Vp8Tables.BMODES_PROBA
        val nodes = BMODE_NODES[mode]
        val bits = BMODE_BITS[mode]
        var cost = 0
        for (k in nodes.indices) cost += bitCost(p[probs + nodes[k]], bits[k])
        return cost
    }

    /** The 13 edge pixels of 4x4 block ([bx], [by]): L3 L2 L1 L0, the corner, A0..A7. */
    private fun loadEdge(bx: Int, by: Int) {
        val x0 = bx * 4
        val y0 = by * 4
        for (j in 0 until 4) {
            edge[3 - j] = if (bx == 0) leftY[y0 + j] else i4Rec[(y0 + j) * 16 + x0 - 1]
        }
        edge[4] = when {
            by == 0 -> topY[x0] // topY[0] is the corner, topY[x0] the pixel above-left otherwise
            bx == 0 -> leftY[y0 - 1]
            else -> i4Rec[(y0 - 1) * 16 + x0 - 1]
        }
        for (i in 0 until 4) edge[5 + i] = if (by == 0) topY[1 + x0 + i] else i4Rec[(y0 - 1) * 16 + x0 + i]
        for (i in 0 until 4) {
            edge[9 + i] = when {
                by == 0 -> topY[1 + x0 + 4 + i]
                bx == 3 -> topY[17 + i] // the right column takes the macroblock's above-right
                else -> i4Rec[(y0 - 1) * 16 + x0 + 4 + i]
            }
        }
    }

    // --- chroma ---------------------------------------------------------------------------------

    private fun tryUv(mode: Int, seg: Segment, mbX: Int): Double {
        predict8(mode, topU, leftU, predU)
        predict8(mode, topV, leftV, predV)
        var rate = UV_MODE_COST[mode]
        var sse = 0
        for (plane in 0 until 2) {
            val src = if (plane == 0) srcU else srcV
            val p = if (plane == 0) predU else predV
            var tnz = if (plane == 0) nzTopU[mbX * 2] or (nzTopU[mbX * 2 + 1] shl 1) else nzTopV[mbX * 2] or (nzTopV[mbX * 2 + 1] shl 1)
            var lnz = if (plane == 0) nzLeftU[0] or (nzLeftU[1] shl 1) else nzLeftV[0] or (nzLeftV[1] shl 1)
            for (b in 0 until 4) {
                val bx = b and 1
                val by = b shr 1
                for (j in 0 until 4) for (i in 0 until 4) {
                    val k = (by * 4 + j) * 8 + bx * 4 + i
                    tmp[j * 4 + i] = src[k] - p[k]
                }
                fdct(tmp, d4, 0, scratch)
                val at = (plane * 4 + b) * 16
                val ctx = ((tnz shr bx) and 1) + ((lnz shr by) and 1)
                if (effort >= 3) {
                    trellis(d4, 0, seg.uvDc, seg.uvAc, 0, TYPE_UV, ctx, seg.lambdaUv * TRELLIS / 16, tryUvLev, at)
                } else {
                    quantize(d4, 0, seg.uvDc, seg.uvAc, 0, tryUvLev, at, UV_ROUND)
                }
                rate += blockCost(tryUvLev, at, 0, TYPE_UV, ctx)
                val nz = if (lastNonZero(tryUvLev, at, 0) >= 0) 1 else 0
                tnz = (tnz and (1 shl bx).inv()) or (nz shl bx)
                lnz = (lnz and (1 shl by).inv()) or (nz shl by)
                dequantize(tryUvLev, at, seg.uvDc, seg.uvAc, tmp)
                sse += reconstruct4Into(tmp, p, (by * 4) * 8 + bx * 4, 8, tryUvRec, plane * 64, src)
            }
        }
        return sse * 4096.0 + seg.lambdaUv * rate
    }

    private fun predict8(mode: Int, top: IntArray, left: IntArray, out: IntArray) {
        when (mode) {
            DC_PRED -> {
                var sum = 0
                val dc = if (hasTop && hasLeft) {
                    for (i in 0 until 8) sum += top[1 + i] + left[i]
                    (sum + 8) shr 4
                } else if (hasTop) {
                    for (i in 0 until 8) sum += top[1 + i]
                    (sum + 4) shr 3
                } else if (hasLeft) {
                    for (i in 0 until 8) sum += left[i]
                    (sum + 4) shr 3
                } else {
                    128
                }
                out.fill(dc, 0, 64)
            }
            V_PRED -> for (j in 0 until 8) for (i in 0 until 8) out[j * 8 + i] = top[1 + i]
            H_PRED -> for (j in 0 until 8) for (i in 0 until 8) out[j * 8 + i] = left[j]
            else -> for (j in 0 until 8) for (i in 0 until 8) out[j * 8 + i] = clip255(left[j] + top[1 + i] - top[0])
        }
    }

    // --- transforms and quantization ------------------------------------------------------------

    /**
     * Add the inverse transform of [c] to the 4x4 prediction at [at] of [p] (row length
     * [stride]), into [rec] at the same place, and return the squared error against [src].
     */
    private fun reconstruct4(c: IntArray, p: IntArray, at: Int, stride: Int, rec: IntArray, src: IntArray): Int =
        reconstruct4Into(c, p, at, stride, rec, 0, src)

    private fun reconstruct4Into(c: IntArray, p: IntArray, at: Int, stride: Int, rec: IntArray, recBase: Int, src: IntArray): Int {
        idct(c, tmp, scratch)
        var sse = 0
        for (j in 0 until 4) for (i in 0 until 4) {
            val k = at + j * stride + i
            val v = clip255(p[k] + tmp[j * 4 + i])
            rec[recBase + k] = v
            val d = v - src[k]
            sse += d * d
        }
        return sse
    }

    private fun quantize(c: IntArray, from: Int, qDc: Int, qAc: Int, first: Int, out: IntArray, at: Int, round: Int) {
        for (n in 0 until 16) {
            if (n < first) {
                out[at + n] = 0
                continue
            }
            val v = c[from + ZIGZAG[n]]
            val q = if (n == 0) qDc else qAc
            val a = if (v < 0) -v else v
            var level = (a * 256 + q * round) / (q * 256)
            // The decoder keeps a dequantized coefficient in 16 bits.
            val max = if (q * MAX_LEVEL > 32767) 32767 / q else MAX_LEVEL
            if (level > max) level = max
            out[at + n] = if (v < 0) -level else level
        }
    }

    // Trellis state: 0 the block's start, 1 after a zero (no end of block may follow), 2 after a
    // one, 3 after a larger level. The context of the next token is 0, 0, 1 and 2.
    private val trellisCost = DoubleArray(4)
    private val trellisNext = DoubleArray(4)
    private val trellisBack = IntArray(16 * 4) // level shl 2 or previous state
    private val trellisDist = DoubleArray(17 * 3)
    private val trellisLevel = IntArray(16 * 3)
    private val zeroTail = DoubleArray(17)

    /**
     * The levels of the block at [from] of [c] that cost least in distortion plus [lambda] times
     * rate, each position choosing among the nearest level, the one below it and zero, with the
     * token context each choice leaves for the next position. Writes them in zigzag order to [out].
     */
    private fun trellis(c: IntArray, from: Int, qDc: Int, qAc: Int, first: Int, type: Int, ctx0: Int, lambda: Double, out: IntArray, at: Int) {
        for (n in 0 until 16) out[at + n] = 0
        // Candidates and their distortion: twice the orthonormal transform, so a pixel's squared
        // error is a quarter of the coefficient's, times the score's 4096.
        zeroTail[16] = 0.0
        for (n in 15 downTo first) {
            val v = c[from + ZIGZAG[n]]
            val a = if (v < 0) -v else v
            val q = if (n == 0) qDc else qAc
            val max = if (q * MAX_LEVEL > 32767) 32767 / q else MAX_LEVEL
            val near = minOf((a + (q shr 1)) / q, max)
            for (k in 0 until 3) {
                val l = when (k) {
                    0 -> near
                    1 -> if (near >= 2) near - 1 else -1
                    else -> if (near >= 1) 0 else -1
                }
                trellisLevel[n * 3 + k] = l
                if (l >= 0) {
                    val d = (a - l * q).toDouble()
                    trellisDist[n * 3 + k] = d * d * 1024
                }
            }
            val d0 = a.toDouble()
            zeroTail[n] = zeroTail[n + 1] + d0 * d0 * 1024
        }
        val inf = 1e300
        trellisCost.fill(inf)
        trellisCost[if (ctx0 == 0) 0 else ctx0 + 1] = 0.0
        var bestEnd = inf
        var endN = first
        var endState = if (ctx0 == 0) 0 else ctx0 + 1
        for (n in first until 16) {
            val band = BANDS[n]
            for (s in 0 until 4) {
                val cost = trellisCost[s]
                if (cost >= inf || s == 1) continue
                val ctx = if (s <= 1) 0 else s - 1
                val eob = cost + lambda * bitCost(proba[((type * 8 + band) * 3 + ctx) * 11], 0) + zeroTail[n]
                if (eob < bestEnd) { bestEnd = eob; endN = n; endState = s }
            }
            trellisNext.fill(inf)
            for (s in 0 until 4) {
                val cost = trellisCost[s]
                if (cost >= inf) continue
                val ctx = if (s <= 1) 0 else s - 1
                val ci = (type * 8 + band) * 3 + ctx
                val more = if (s == 1) 0 else bitCost(proba[ci * 11], 1)
                for (k in 0 until 3) {
                    val l = trellisLevel[n * 3 + k]
                    if (l < 0) continue
                    val bits = more + tokenCost[ci * 11 + TOKEN_OF[l]] + EXTRA_COST[l]
                    val total = cost + lambda * bits + trellisDist[n * 3 + k]
                    val ns = if (l == 0) 1 else if (l == 1) 2 else 3
                    if (total < trellisNext[ns]) {
                        trellisNext[ns] = total
                        trellisBack[n * 4 + ns] = (l shl 2) or s
                    }
                }
            }
            trellisNext.copyInto(trellisCost)
        }
        for (s in 0 until 4) {
            if (trellisCost[s] < bestEnd) { bestEnd = trellisCost[s]; endN = 16; endState = s }
        }
        var s = endState
        for (n in endN - 1 downTo first) {
            val back = trellisBack[n * 4 + s]
            val l = back shr 2
            val v = c[from + ZIGZAG[n]]
            out[at + n] = if (v < 0) -l else l
            s = back and 3
        }
    }

    private fun dequantize(lev: IntArray, at: Int, qDc: Int, qAc: Int, out: IntArray) {
        for (n in 0 until 16) out[ZIGZAG[n]] = lev[at + n] * if (n == 0) qDc else qAc
    }

    // --- token costs ----------------------------------------------------------------------------

    private fun computeTokenCosts() {
        for (ctx in 0 until NUM_CTX) {
            val p = ctx * 11
            val base = ctx * 11
            val pr = proba
            val notZero = bitCost(pr[p + 1], 1)
            tokenCost[base] = bitCost(pr[p + 1], 0)
            val notOne = notZero + bitCost(pr[p + 2], 1)
            tokenCost[base + 1] = notZero + bitCost(pr[p + 2], 0)
            val small = notOne + bitCost(pr[p + 3], 0)
            tokenCost[base + 2] = small + bitCost(pr[p + 4], 0)
            tokenCost[base + 3] = small + bitCost(pr[p + 4], 1) + bitCost(pr[p + 5], 0)
            tokenCost[base + 4] = small + bitCost(pr[p + 4], 1) + bitCost(pr[p + 5], 1)
            val large = notOne + bitCost(pr[p + 3], 1)
            tokenCost[base + 5] = large + bitCost(pr[p + 6], 0) + bitCost(pr[p + 7], 0)
            tokenCost[base + 6] = large + bitCost(pr[p + 6], 0) + bitCost(pr[p + 7], 1)
            val larger = large + bitCost(pr[p + 6], 1)
            tokenCost[base + 7] = larger + bitCost(pr[p + 8], 0) + bitCost(pr[p + 9], 0)
            tokenCost[base + 8] = larger + bitCost(pr[p + 8], 0) + bitCost(pr[p + 9], 1)
            tokenCost[base + 9] = larger + bitCost(pr[p + 8], 1) + bitCost(pr[p + 10], 0)
            tokenCost[base + 10] = larger + bitCost(pr[p + 8], 1) + bitCost(pr[p + 10], 1)
        }
    }

    /** The bits, in 1/256, of the tokens of the block at [at] of [lev] from position [first]. */
    private fun blockCost(lev: IntArray, at: Int, first: Int, type: Int, ctx0: Int): Int {
        val last = lastNonZero(lev, at, first)
        var ctx = ctx0
        if (last < 0) return bitCost(proba[((type * 8 + BANDS[first]) * 3 + ctx) * 11], 0)
        var cost = 0
        var prevZero = false
        for (n in first..last) {
            val c = (type * 8 + BANDS[n]) * 3 + ctx
            if (!prevZero) cost += bitCost(proba[c * 11], 1)
            val raw = lev[at + n]
            val v = if (raw < 0) -raw else raw
            cost += tokenCost[c * 11 + TOKEN_OF[v]] + EXTRA_COST[v]
            ctx = if (v == 0) 0 else if (v == 1) 1 else 2
            prevZero = v == 0
        }
        if (last < 15) cost += bitCost(proba[((type * 8 + BANDS[last + 1]) * 3 + ctx) * 11], 0)
        return cost
    }

    // --- token statistics and probabilities -----------------------------------------------------

    /** Walk every recorded block in coding order, handing each to [visit] with its type and context. */
    private inline fun forEachBlock(visit: (mb: Int, type: Int, first: Int, ctx: Int, at: Int, n: Int) -> Boolean) {
        nzTopY.fill(0); nzTopU.fill(0); nzTopV.fill(0); nzDcTop.fill(0)
        for (mbY in 0 until mbH) {
            nzLeftY.fill(0); nzLeftU.fill(0); nzLeftV.fill(0); nzDcLeft = 0
            for (mbX in 0 until mbW) {
                val mb = mbY * mbW + mbX
                var at = levelsStart[mb]
                if (skip[mb] && useSkip) {
                    for (i in 0 until 4) { nzTopY[mbX * 4 + i] = 0; nzLeftY[i] = 0 }
                    for (i in 0 until 2) { nzTopU[mbX * 2 + i] = 0; nzLeftU[i] = 0; nzTopV[mbX * 2 + i] = 0; nzLeftV[i] = 0 }
                    if (!isI4[mb]) { nzDcTop[mbX] = 0; nzDcLeft = 0 }
                    continue
                }
                val first: Int
                val yType: Int
                if (!isI4[mb]) {
                    val n = levels[at].toInt()
                    val nz = visit(mb, TYPE_Y2, 0, nzDcTop[mbX] + nzDcLeft, at + 1, n)
                    nzDcTop[mbX] = if (nz) 1 else 0
                    nzDcLeft = nzDcTop[mbX]
                    at += 1 + n
                    first = 1
                    yType = TYPE_Y_AFTER_Y2
                } else {
                    first = 0
                    yType = TYPE_I4
                }
                for (b in 0 until 16) {
                    val bx = b and 3
                    val by = b shr 2
                    val n = levels[at].toInt()
                    val nz = visit(mb, yType, first, nzTopY[mbX * 4 + bx] + nzLeftY[by], at + 1, n)
                    nzTopY[mbX * 4 + bx] = if (nz) 1 else 0
                    nzLeftY[by] = nzTopY[mbX * 4 + bx]
                    at += 1 + n
                }
                for (plane in 0 until 2) {
                    val top = if (plane == 0) nzTopU else nzTopV
                    val left = if (plane == 0) nzLeftU else nzLeftV
                    for (b in 0 until 4) {
                        val bx = b and 1
                        val by = b shr 1
                        val n = levels[at].toInt()
                        val nz = visit(mb, TYPE_UV, 0, top[mbX * 2 + bx] + left[by], at + 1, n)
                        top[mbX * 2 + bx] = if (nz) 1 else 0
                        left[by] = top[mbX * 2 + bx]
                        at += 1 + n
                    }
                }
            }
        }
    }

    /**
     * The tokens of one recorded block, [n] levels at [at] of which positions before [first] are
     * not coded, through [bit] with each node's probability index. Returns whether any is non-zero.
     */
    private inline fun walkTokens(type: Int, first: Int, ctx0: Int, at: Int, n: Int, bit: (node: Int, value: Int) -> Unit, extra: (v: Int) -> Unit): Boolean {
        var ctx = ctx0
        if (n <= first) {
            bit(((type * 8 + BANDS[first]) * 3 + ctx) * 11, 0)
            return false
        }
        var prevZero = false
        for (pos in first until n) {
            val p = ((type * 8 + BANDS[pos]) * 3 + ctx) * 11
            if (!prevZero) bit(p, 1)
            val raw = levels[at + pos].toInt()
            val v = if (raw < 0) -raw else raw
            if (v == 0) {
                bit(p + 1, 0)
            } else {
                bit(p + 1, 1)
                if (v == 1) {
                    bit(p + 2, 0)
                } else {
                    bit(p + 2, 1)
                    if (v <= 4) {
                        bit(p + 3, 0)
                        if (v == 2) {
                            bit(p + 4, 0)
                        } else {
                            bit(p + 4, 1)
                            bit(p + 5, if (v == 3) 0 else 1)
                        }
                    } else if (v <= 10) {
                        bit(p + 3, 1)
                        bit(p + 6, 0)
                        bit(p + 7, if (v <= 6) 0 else 1)
                    } else {
                        bit(p + 3, 1)
                        bit(p + 6, 1)
                        val cat = if (v <= 18) 0 else if (v <= 34) 1 else if (v <= 66) 2 else 3
                        bit(p + 8, cat shr 1)
                        bit(p + 9 + (cat shr 1), cat and 1)
                    }
                    extra(v)
                }
                extra(if (raw < 0) -1 else -2)
            }
            ctx = if (v == 0) 0 else if (v == 1) 1 else 2
            prevZero = v == 0
        }
        if (n < 16) bit(((type * 8 + BANDS[n]) * 3 + ctx) * 11, 0)
        return true
    }

    private fun countTokens(): IntArray {
        val counts = IntArray(NUM_CTX * 11 * 2)
        forEachBlock { _, type, first, ctx, at, n ->
            walkTokens(type, first, ctx, at, n, { node, value -> counts[node * 2 + value]++ }, { })
        }
        return counts
    }

    /** Set [proba] to the best of the default and the counted probability of each node. */
    private fun fitProbabilities() {
        val counts = countTokens()
        val defaults = Vp8Tables.COEFFS_PROBA_0
        val update = Vp8Tables.COEFFS_UPDATE_PROBA
        for (i in proba.indices) {
            val n0 = counts[2 * i].toLong()
            val n1 = counts[2 * i + 1].toLong()
            val total = n0 + n1
            val old = defaults[i]
            proba[i] = old
            if (total == 0L) continue
            val fitted = ((n0 * 256 + total / 2) / total).toInt().coerceIn(1, 255)
            val oldCost = n0 * bitCost(old, 0) + n1 * bitCost(old, 1) + bitCost(update[i], 0)
            val newCost = n0 * bitCost(fitted, 0) + n1 * bitCost(fitted, 1) + bitCost(update[i], 1) + 8 * 256
            if (newCost < oldCost) proba[i] = fitted
        }
    }

    /** Use the skip flag when it costs less than coding the empty blocks of skipped macroblocks. */
    private fun decideSkip() {
        useSkip = false
        var skipped = 0
        for (s in skip) if (s) skipped++
        if (skipped == 0) return
        val p = ((mbCount - skipped).toLong() * 256 / mbCount).toInt().coerceIn(1, 255)
        val withFlag = 8L * 256 + skipped.toLong() * bitCost(p, 1) + (mbCount - skipped).toLong() * bitCost(p, 0)
        var without = 0L
        forEachBlock { mb, type, first, ctx, at, n ->
            if (skip[mb]) without += bitCost(proba[((type * 8 + BANDS[first]) * 3 + ctx) * 11], 0)
            n > first
        }
        if (withFlag < without) {
            useSkip = true
            skipProb = p
        }
    }

    // --- loop filter ----------------------------------------------------------------------------

    private fun chooseFilter() {
        filterSharpness = 0
        // The frame's own level only switches the filter on when segments carry their own.
        filterLevelBase = 0
        for (s in 0 until segmentCount) filterLevelBase = maxOf(filterLevelBase, segments[s].filterLevel)
        if (filterOff) filterLevelBase = 0
    }

    // --- bitstream ------------------------------------------------------------------------------

    private fun writeTokens(): ByteArray {
        val bw = Vp8BoolWriter(maxOf(1024, levelsSize))
        forEachBlock { _, type, first, ctx, at, n ->
            walkTokens(type, first, ctx, at, n, { node, value -> bw.put(value, proba[node]) }) { v ->
                when {
                    v == -1 -> bw.put(1, 128)
                    v == -2 -> bw.put(0, 128)
                    v >= 5 -> writeExtra(bw, v)
                }
            }
        }
        return bw.finish()
    }

    private fun writeExtra(bw: Vp8BoolWriter, v: Int) {
        val cat = CATEGORY_OF[v]
        if (cat < 0) return
        val probs = CAT_PROBS[cat]
        val rest = v - CAT_BASE[cat]
        for (k in probs.indices) bw.put((rest shr (probs.size - 1 - k)) and 1, probs[k])
    }

    private fun writeFirstPartition(): ByteArray {
        val bw = Vp8BoolWriter(maxOf(256, mbCount))
        bw.put(0, 128) // colour space: YUV of RFC 6386 section 9.2
        bw.put(0, 128) // clamping required
        val useSegments = segmentCount > 1
        bw.put(if (useSegments) 1 else 0, 128)
        val counts = IntArray(4)
        for (s in segmentOf) counts[s.toInt()]++
        val segProbs = IntArray(3)
        if (useSegments) {
            bw.put(1, 128) // update the map
            bw.put(1, 128) // update the segment data
            bw.put(1, 128) // absolute values
            for (s in 0 until 4) bw.optionalSigned(segmentQ[s], 7)
            for (s in 0 until 4) bw.optionalSigned(segments[s].filterLevel, 6)
            segProbs[0] = treeProb(counts[0] + counts[1], counts[2] + counts[3])
            segProbs[1] = treeProb(counts[0], counts[1])
            segProbs[2] = treeProb(counts[2], counts[3])
            for (p in segProbs) {
                if (p == 255) {
                    bw.put(0, 128)
                } else {
                    bw.put(1, 128)
                    bw.literal(p, 8)
                }
            }
        }
        bw.put(0, 128) // normal loop filter
        bw.literal(filterLevelBase, 6)
        bw.literal(filterSharpness, 3)
        bw.put(0, 128) // no loop filter deltas
        bw.literal(0, 2) // one token partition
        bw.literal(segmentQ[0], 7)
        repeat(5) { bw.put(0, 128) } // no quantizer deltas
        bw.put(0, 128) // refresh_entropy_probs: nothing follows this frame
        val update = Vp8Tables.COEFFS_UPDATE_PROBA
        val defaults = Vp8Tables.COEFFS_PROBA_0
        for (i in proba.indices) {
            if (proba[i] != defaults[i]) {
                bw.put(1, update[i])
                bw.literal(proba[i], 8)
            } else {
                bw.put(0, update[i])
            }
        }
        bw.put(if (useSkip) 1 else 0, 128)
        if (useSkip) bw.literal(skipProb, 8)

        val top = IntArray(mbW * 4)
        val left = IntArray(4)
        for (mbY in 0 until mbH) {
            left.fill(B_DC)
            for (mbX in 0 until mbW) {
                val mb = mbY * mbW + mbX
                if (useSegments) {
                    val s = segmentOf[mb].toInt()
                    bw.put(s shr 1, segProbs[0])
                    bw.put(s and 1, if (s < 2) segProbs[1] else segProbs[2])
                }
                if (useSkip) bw.put(if (skip[mb]) 1 else 0, skipProb)
                if (isI4[mb]) {
                    bw.put(0, 145)
                    val p = Vp8Tables.BMODES_PROBA
                    for (b in 0 until 16) {
                        val bx = b and 3
                        val by = b shr 2
                        val m = modes4[mb * 16 + b].toInt()
                        val probs = (top[mbX * 4 + bx] * 10 + left[by]) * 9
                        val nodes = BMODE_NODES[m]
                        val bits = BMODE_BITS[m]
                        for (k in nodes.indices) bw.put(bits[k], p[probs + nodes[k]])
                        top[mbX * 4 + bx] = m
                        left[by] = m
                    }
                } else {
                    val m = mode16[mb].toInt()
                    bw.put(1, 145)
                    // The key frame luma tree of RFC 6386 section 11.2: DC, V, H, TM.
                    when (m) {
                        DC_PRED -> { bw.put(0, 156); bw.put(0, 163) }
                        V_PRED -> { bw.put(0, 156); bw.put(1, 163) }
                        H_PRED -> { bw.put(1, 156); bw.put(0, 128) }
                        else -> { bw.put(1, 156); bw.put(1, 128) }
                    }
                    for (i in 0 until 4) { top[mbX * 4 + i] = m; left[i] = m }
                }
                when (uvMode[mb].toInt()) {
                    DC_PRED -> bw.put(0, 142)
                    V_PRED -> { bw.put(1, 142); bw.put(0, 114) }
                    H_PRED -> { bw.put(1, 142); bw.put(1, 114); bw.put(0, 183) }
                    else -> { bw.put(1, 142); bw.put(1, 114); bw.put(1, 183) }
                }
            }
        }
        return bw.finish()
    }

    private fun treeProb(zeros: Int, ones: Int): Int {
        val total = zeros + ones
        if (total == 0 || ones == 0) return 255
        return ((zeros.toLong() * 256 + total / 2) / total).toInt().coerceIn(1, 255)
    }

    /** Reconstruction of the last pass, for tests: Y, U and V before the loop filter. */
    internal class Result(val bytes: ByteArray, val y: ByteArray, val u: ByteArray, val v: ByteArray)

    companion object {
        /** Encode [planes] as one VP8 key frame, the payload of a `VP8 ` chunk. */
        fun encode(planes: Vp8Planes, quality: Int, effort: Int, cancel: (() -> Unit)? = null): ByteArray =
            Vp8Encoder(planes, quality, effort, cancel).run()

        internal fun encodeForTest(planes: Vp8Planes, quality: Int, effort: Int, filterOff: Boolean): Result {
            val e = Vp8Encoder(planes, quality, effort, null, filterOff)
            val bytes = e.run()
            return Result(bytes, e.recY, e.recU, e.recV)
        }

        // Mode numbers of the decoder's tables: the 16x16 modes share the first four 4x4 numbers.
        const val DC_PRED = 0
        const val TM_PRED = 1
        const val V_PRED = 2
        const val H_PRED = 3
        const val B_DC = 0
        const val B_TM = 1
        const val B_VE = 2
        const val B_HE = 3
        const val B_RD = 4
        const val B_VR = 5
        const val B_LD = 6
        const val B_VL = 7
        const val B_HD = 8
        const val B_HU = 9

        const val TYPE_Y_AFTER_Y2 = 0
        const val TYPE_Y2 = 1
        const val TYPE_UV = 2
        const val TYPE_I4 = 3
        const val NUM_CTX = 4 * 8 * 3
        const val MAX_LEVEL = 2047

        // Tuning, set by measuring the size at equal SSIMULACRA 2 against cwebp -m 4 on PhotoScenes.
        /** Rounding of quantization, in 1/256 of a step: below 128 widens the band that goes to zero. */
        const val Y1_ROUND = 80
        const val Y2_ROUND = 128
        const val UV_ROUND = 115

        /** Lambda of each decision, in 1/16 of the squared AC step. */
        const val LAMBDA_I16 = 4
        const val LAMBDA_I4 = 4
        const val LAMBDA_UV = 4

        /** Quantizer steps a segment moves for each doubling of its luma energy over the mean. */
        const val SNS_STRENGTH = 2

        /** Loop filter level: FILTER_BASE plus FILTER_SLOPE / 64 of the AC step. */
        const val FILTER_BASE = 0
        const val FILTER_SLOPE = 14

        /** The trellis lambda, in 1/16 of the decision's. */
        const val TRELLIS = 16

        /** Coefficient order of RFC 6386 section 13: zigzag position to raster index. */
        val ZIGZAG = intArrayOf(0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15)

        /** The band of each zigzag position (RFC 6386 section 13.3), and one past the end. */
        val BANDS = intArrayOf(0, 1, 2, 3, 6, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7, 0)

        /** Extra-bit probabilities of DCT_CAT1 to DCT_CAT6 (RFC 6386 section 13.2). */
        val CAT_PROBS = arrayOf(
            intArrayOf(159),
            intArrayOf(165, 145),
            intArrayOf(173, 148, 140),
            intArrayOf(176, 155, 140, 135),
            intArrayOf(180, 157, 141, 134, 130),
            intArrayOf(254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129),
        )
        val CAT_BASE = intArrayOf(5, 7, 11, 19, 35, 67)

        /** Category of each level, -1 below 5. */
        val CATEGORY_OF = IntArray(MAX_LEVEL + 1) { v ->
            when {
                v < 5 -> -1
                v < 7 -> 0
                v < 11 -> 1
                v < 19 -> 2
                v < 35 -> 3
                v < 67 -> 4
                else -> 5
            }
        }

        /** Token of each level: 0 to 4 themselves, then 5 + the category. */
        val TOKEN_OF = IntArray(MAX_LEVEL + 1) { v -> if (v < 5) v else 5 + CATEGORY_OF[v] }

        /**
         * Cost in 1/256 bit, -log2(i / 256) for i from 1 to 256, by an integer logarithm so that
         * every platform has the same table.
         */
        val COST = IntArray(257) { if (it == 0) 0 else 2048 - log2Q8(it) }

        fun bitCost(prob: Int, bit: Int): Int = if (bit == 0) COST[prob] else COST[256 - prob]

        /** The extra bits and the sign of each level. */
        val EXTRA_COST = IntArray(MAX_LEVEL + 1) { v ->
            if (v == 0) {
                0
            } else {
                var c = 256
                val cat = CATEGORY_OF[v]
                if (cat >= 0) {
                    val probs = CAT_PROBS[cat]
                    val rest = v - CAT_BASE[cat]
                    for (k in probs.indices) c += bitCost(probs[k], (rest shr (probs.size - 1 - k)) and 1)
                }
                c
            }
        }

        val MODE16_COST = IntArray(4).also {
            val flag = bitCost(145, 1)
            it[DC_PRED] = flag + bitCost(156, 0) + bitCost(163, 0)
            it[V_PRED] = flag + bitCost(156, 0) + bitCost(163, 1)
            it[H_PRED] = flag + bitCost(156, 1) + bitCost(128, 0)
            it[TM_PRED] = flag + bitCost(156, 1) + bitCost(128, 1)
        }
        val I4_FLAG_COST = bitCost(145, 0)
        val UV_MODE_COST = IntArray(4).also {
            it[DC_PRED] = bitCost(142, 0)
            it[V_PRED] = bitCost(142, 1) + bitCost(114, 0)
            it[H_PRED] = bitCost(142, 1) + bitCost(114, 1) + bitCost(183, 0)
            it[TM_PRED] = bitCost(142, 1) + bitCost(114, 1) + bitCost(183, 1)
        }

        /**
         * The 4x4 mode tree of RFC 6386 section 11.2: for each mode, the probability index of each
         * node on its path and the bit taken there.
         */
        val BMODE_NODES: Array<IntArray> = arrayOf(
            intArrayOf(0), intArrayOf(0, 1), intArrayOf(0, 1, 2), intArrayOf(0, 1, 2, 3, 4),
            intArrayOf(0, 1, 2, 3, 4, 5), intArrayOf(0, 1, 2, 3, 4, 5), intArrayOf(0, 1, 2, 3, 6),
            intArrayOf(0, 1, 2, 3, 6, 7), intArrayOf(0, 1, 2, 3, 6, 7, 8), intArrayOf(0, 1, 2, 3, 6, 7, 8),
        )
        val BMODE_BITS: Array<IntArray> = arrayOf(
            intArrayOf(0), intArrayOf(1, 0), intArrayOf(1, 1, 0), intArrayOf(1, 1, 1, 0, 0), // DC TM VE HE
            intArrayOf(1, 1, 1, 0, 1, 0), intArrayOf(1, 1, 1, 0, 1, 1), intArrayOf(1, 1, 1, 1, 0), // RD VR LD
            intArrayOf(1, 1, 1, 1, 1, 0), intArrayOf(1, 1, 1, 1, 1, 1, 0), intArrayOf(1, 1, 1, 1, 1, 1, 1), // VL HD HU
        )

        /** log2([x]) in 1/256, for x from 1 to 65536, by repeated squaring of the mantissa. */
        fun log2Q8(x: Int): Int {
            val n = 31 - x.countLeadingZeroBits()
            var y = (x.toLong() shl 16) shr n // mantissa in [1, 2), 16 fraction bits
            var frac = 0
            for (i in 0 until 8) {
                y = (y * y) shr 16
                if (y >= (2L shl 16)) {
                    y = y shr 1
                    frac = frac or (1 shl (7 - i))
                }
            }
            return n * 256 + frac
        }

        fun clip255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

        /** The quantizer index of [quality], interpolated between anchors set against `cwebp -q`. */
        fun baseQuantizer(quality: Int): Int {
            val qs = QUALITY_ANCHORS
            var i = 0
            while (qs[i + 2] < quality) i += 2
            val q0 = qs[i]
            val q1 = qs[i + 2]
            val v0 = qs[i + 1]
            val v1 = qs[i + 3]
            return v0 + ((v1 - v0) * (quality - q0) * 2 + (q1 - q0)) / (2 * (q1 - q0))
        }

        /** (quality, quantizer index) pairs. */
        val QUALITY_ANCHORS = intArrayOf(0, 127, 10, 74, 25, 54, 50, 36, 75, 22, 90, 8, 100, 0)

        /** The forward transform of the 4x4 block [x] (raster order) into [out] at [at]: twice the orthonormal DCT, the inverse of section 14.3's. */
        fun fdct(x: IntArray, out: IntArray, at: Int, t: IntArray = IntArray(16)) {
            for (r in 0 until 4) {
                val x0 = x[r * 4]; val x1 = x[r * 4 + 1]; val x2 = x[r * 4 + 2]; val x3 = x[r * 4 + 3]
                val a = x0 + x3; val b = x1 + x2; val c = x1 - x2; val d = x0 - x3
                t[r * 4] = (a + b) shl 3
                t[r * 4 + 2] = (a - b) shl 3
                // sqrt(2) cos(pi/8) and sqrt(2) cos(3pi/8) in 1/4096, the result times 8.
                t[r * 4 + 1] = (d * 5352 + c * 2217 + 256) shr 9
                t[r * 4 + 3] = (d * 2217 - c * 5352 + 256) shr 9
            }
            for (i in 0 until 4) {
                val y0 = t[i]; val y1 = t[4 + i]; val y2 = t[8 + i]; val y3 = t[12 + i]
                val a = y0 + y3; val b = y1 + y2; val c = y1 - y2; val d = y0 - y3
                out[at + i] = (a + b + 8) shr 4
                out[at + 8 + i] = (a - b + 8) shr 4
                out[at + 4 + i] = (d * 5352 + c * 2217 + (1 shl 15)) shr 16
                out[at + 12 + i] = (d * 2217 - c * 5352 + (1 shl 15)) shr 16
            }
        }

        /** The inverse transform of RFC 6386 section 14.3, [c] in raster order into [out]. */
        fun idct(c: IntArray, out: IntArray, t: IntArray = IntArray(16)) {
            for (i in 0 until 4) {
                val a1 = c[i] + c[8 + i]
                val b1 = c[i] - c[8 + i]
                var temp1 = (c[4 + i] * 35468) shr 16
                var temp2 = c[12 + i] + ((c[12 + i] * 20091) shr 16)
                val c1 = temp1 - temp2
                temp1 = c[4 + i] + ((c[4 + i] * 20091) shr 16)
                temp2 = (c[12 + i] * 35468) shr 16
                val d1 = temp1 + temp2
                t[i] = a1 + d1
                t[12 + i] = a1 - d1
                t[4 + i] = b1 + c1
                t[8 + i] = b1 - c1
            }
            for (r in 0 until 4) {
                val i0 = t[r * 4]; val i1 = t[r * 4 + 1]; val i2 = t[r * 4 + 2]; val i3 = t[r * 4 + 3]
                val a1 = i0 + i2
                val b1 = i0 - i2
                var temp1 = (i1 * 35468) shr 16
                var temp2 = i3 + ((i3 * 20091) shr 16)
                val c1 = temp1 - temp2
                temp1 = i1 + ((i1 * 20091) shr 16)
                temp2 = (i3 * 35468) shr 16
                val d1 = temp1 + temp2
                out[r * 4] = (a1 + d1 + 4) shr 3
                out[r * 4 + 3] = (a1 - d1 + 4) shr 3
                out[r * 4 + 1] = (b1 + c1 + 4) shr 3
                out[r * 4 + 2] = (b1 - c1 + 4) shr 3
            }
        }

        /** The forward Walsh-Hadamard transform of the 16 DC terms: half of M X M, M the inverse's matrix. */
        fun fwht(x: IntArray, out: IntArray, t: IntArray = IntArray(16)) {
            for (i in 0 until 4) {
                val x0 = x[i]; val x1 = x[4 + i]; val x2 = x[8 + i]; val x3 = x[12 + i]
                t[i] = x0 + x1 + x2 + x3
                t[4 + i] = x0 + x1 - x2 - x3
                t[8 + i] = x0 - x1 - x2 + x3
                t[12 + i] = x0 - x1 + x2 - x3
            }
            for (r in 0 until 4) {
                val x0 = t[r * 4]; val x1 = t[r * 4 + 1]; val x2 = t[r * 4 + 2]; val x3 = t[r * 4 + 3]
                out[r * 4] = half(x0 + x1 + x2 + x3)
                out[r * 4 + 1] = half(x0 + x1 - x2 - x3)
                out[r * 4 + 2] = half(x0 - x1 - x2 + x3)
                out[r * 4 + 3] = half(x0 - x1 + x2 - x3)
            }
        }

        private fun half(v: Int): Int = if (v < 0) -((1 - v) shr 1) else (v + 1) shr 1

        /** The inverse Walsh-Hadamard transform of RFC 6386 section 14.3. */
        fun iwht(c: IntArray, out: IntArray, t: IntArray = IntArray(16)) {
            for (i in 0 until 4) {
                val a1 = c[i] + c[12 + i]
                val b1 = c[4 + i] + c[8 + i]
                val c1 = c[4 + i] - c[8 + i]
                val d1 = c[i] - c[12 + i]
                t[i] = a1 + b1
                t[4 + i] = c1 + d1
                t[8 + i] = a1 - b1
                t[12 + i] = d1 - c1
            }
            for (r in 0 until 4) {
                val a1 = t[r * 4] + t[r * 4 + 3]
                val b1 = t[r * 4 + 1] + t[r * 4 + 2]
                val c1 = t[r * 4 + 1] - t[r * 4 + 2]
                val d1 = t[r * 4] - t[r * 4 + 3]
                out[r * 4] = (a1 + b1 + 3) shr 3
                out[r * 4 + 1] = (c1 + d1 + 3) shr 3
                out[r * 4 + 2] = (a1 - b1 + 3) shr 3
                out[r * 4 + 3] = (d1 - c1 + 3) shr 3
            }
        }

        private fun avg2(a: Int, b: Int): Int = (a + b + 1) shr 1
        private fun avg3(a: Int, b: Int, c: Int): Int = (a + 2 * b + c + 2) shr 2

        /**
         * The 4x4 prediction of RFC 6386 section 12.3 from the edge [e]: L3 L2 L1 L0 at 0..3, the
         * corner P at 4, the eight pixels above and above-right A0..A7 at 5..12.
         */
        fun predict4(mode: Int, e: IntArray, out: IntArray) {
            val p = e[4]
            when (mode) {
                B_DC -> {
                    var s = 4
                    for (i in 0 until 4) s += e[5 + i] + e[i]
                    out.fill(s shr 3, 0, 16)
                }
                B_TM -> for (r in 0 until 4) for (c in 0 until 4) out[r * 4 + c] = clip255(e[3 - r] + e[5 + c] - p)
                B_VE -> for (c in 0 until 4) {
                    val v = avg3(e[4 + c], e[5 + c], e[6 + c])
                    for (r in 0 until 4) out[r * 4 + c] = v
                }
                B_HE -> {
                    // L[-1] is the corner, and L[4] repeats L[3].
                    val l = intArrayOf(p, e[3], e[2], e[1], e[0], e[0])
                    for (r in 0 until 4) {
                        val v = avg3(l[r], l[r + 1], l[r + 2])
                        for (c in 0 until 4) out[r * 4 + c] = v
                    }
                }
                B_LD -> for (r in 0 until 4) for (c in 0 until 4) {
                    val i = r + c
                    out[r * 4 + c] = if (i < 6) avg3(e[5 + i], e[6 + i], e[7 + i]) else avg3(e[11], e[12], e[12])
                }
                B_RD -> for (r in 0 until 4) for (c in 0 until 4) {
                    val i = 3 - r + c
                    out[r * 4 + c] = avg3(e[i], e[i + 1], e[i + 2])
                }
                B_VR -> {
                    // e[0..8] is L3 L2 L1 L0 P A0 A1 A2 A3, the edge from bottom-left to top-right.
                    fun set(r: Int, c: Int, v: Int) { out[r * 4 + c] = v }
                    set(3, 0, avg3(e[1], e[2], e[3]))
                    set(2, 0, avg3(e[2], e[3], e[4]))
                    avg3(e[3], e[4], e[5]).let { set(3, 1, it); set(1, 0, it) }
                    avg2(e[4], e[5]).let { set(2, 1, it); set(0, 0, it) }
                    avg3(e[4], e[5], e[6]).let { set(3, 2, it); set(1, 1, it) }
                    avg2(e[5], e[6]).let { set(2, 2, it); set(0, 1, it) }
                    avg3(e[5], e[6], e[7]).let { set(3, 3, it); set(1, 2, it) }
                    avg2(e[6], e[7]).let { set(2, 3, it); set(0, 2, it) }
                    set(1, 3, avg3(e[6], e[7], e[8]))
                    set(0, 3, avg2(e[7], e[8]))
                }
                B_VL -> {
                    fun a(i: Int) = e[5 + i]
                    fun set(r: Int, c: Int, v: Int) { out[r * 4 + c] = v }
                    set(0, 0, avg2(a(0), a(1)))
                    set(1, 0, avg3(a(0), a(1), a(2)))
                    avg2(a(1), a(2)).let { set(2, 0, it); set(0, 1, it) }
                    avg3(a(1), a(2), a(3)).let { set(1, 1, it); set(3, 0, it) }
                    avg2(a(2), a(3)).let { set(2, 1, it); set(0, 2, it) }
                    avg3(a(2), a(3), a(4)).let { set(3, 1, it); set(1, 2, it) }
                    avg2(a(3), a(4)).let { set(2, 2, it); set(0, 3, it) }
                    avg3(a(3), a(4), a(5)).let { set(3, 2, it); set(1, 3, it) }
                    set(2, 3, avg3(a(4), a(5), a(6)))
                    set(3, 3, avg3(a(5), a(6), a(7)))
                }
                B_HD -> {
                    fun set(r: Int, c: Int, v: Int) { out[r * 4 + c] = v }
                    set(3, 0, avg2(e[0], e[1]))
                    set(3, 1, avg3(e[0], e[1], e[2]))
                    avg2(e[1], e[2]).let { set(2, 0, it); set(3, 2, it) }
                    avg3(e[1], e[2], e[3]).let { set(2, 1, it); set(3, 3, it) }
                    avg2(e[2], e[3]).let { set(2, 2, it); set(1, 0, it) }
                    avg3(e[2], e[3], e[4]).let { set(2, 3, it); set(1, 1, it) }
                    avg2(e[3], e[4]).let { set(1, 2, it); set(0, 0, it) }
                    avg3(e[3], e[4], e[5]).let { set(1, 3, it); set(0, 1, it) }
                    set(0, 2, avg3(e[4], e[5], e[6]))
                    set(0, 3, avg3(e[5], e[6], e[7]))
                }
                else -> { // B_HU, from L0 L1 L2 L3
                    val l0 = e[3]; val l1 = e[2]; val l2 = e[1]; val l3 = e[0]
                    fun set(r: Int, c: Int, v: Int) { out[r * 4 + c] = v }
                    set(0, 0, avg2(l0, l1))
                    set(0, 1, avg3(l0, l1, l2))
                    avg2(l1, l2).let { set(0, 2, it); set(1, 0, it) }
                    avg3(l1, l2, l3).let { set(0, 3, it); set(1, 1, it) }
                    avg2(l2, l3).let { set(1, 2, it); set(2, 0, it) }
                    avg3(l2, l3, l3).let { set(1, 3, it); set(2, 1, it) }
                    set(2, 2, l3); set(2, 3, l3)
                    for (c in 0 until 4) set(3, c, l3)
                }
            }
        }

        /** The index of the last non-zero level of the block at [at] from position [first], or -1. */
        fun lastNonZero(lev: IntArray, at: Int, first: Int): Int {
            for (n in 15 downTo first) if (lev[at + n] != 0) return n
            return -1
        }

        /** The segment centre nearest to [v]. */
        fun nearest(centres: IntArray, v: Int): Int {
            var best = 0
            for (s in 1 until centres.size) {
                if (kotlin.math.abs(centres[s] - v) < kotlin.math.abs(centres[best] - v)) best = s
            }
            return best
        }

        /** [k] centres of [values] by Lloyd's iteration from the quantiles, in increasing order. */
        fun kMeans(values: IntArray, k: Int): IntArray {
            val sorted = values.sortedArray()
            val centres = IntArray(k) { sorted[((2 * it + 1) * sorted.size) / (2 * k)] }
            val sum = LongArray(k)
            val count = IntArray(k)
            repeat(8) {
                sum.fill(0); count.fill(0)
                for (v in values) {
                    val s = nearest(centres, v)
                    sum[s] += v.toLong()
                    count[s]++
                }
                for (s in 0 until k) if (count[s] > 0) centres[s] = (sum[s] / count[s]).toInt()
            }
            centres.sort()
            return centres
        }
    }
}
