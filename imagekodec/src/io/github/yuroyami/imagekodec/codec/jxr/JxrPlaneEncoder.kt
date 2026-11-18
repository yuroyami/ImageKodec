package io.github.yuroyami.imagekodec.codec.jxr

import kotlin.math.abs

/**
 * The coding of one JPEG XR image plane, macroblock by macroblock (ITU-T T.832 clauses 8.7 and
 * 9.3 to 9.8 in the encoding direction): quantization, the prediction of DC, lowpass and highpass
 * coefficients, the coded block patterns, and the run-level codes with their adaptive tables,
 * scan orders and flexbits models.
 *
 * Each step writes what the matching step of [JxrPlane] reads, and moves every adaptive model by
 * the same symbols in the same order, so the decoder's state follows the encoder's.
 *
 * [coef] holds each channel's transform coefficients in the decoder's macroblock layout,
 * [JxrForward]'s output. [dcStep], [lpStep] and [hpStep] are the quantization steps of each
 * channel, and [dcRound], [lpRound] and [hpRound] what is added to a magnitude before it is
 * divided by them, in 64ths of the step.
 */
internal class JxrPlaneEncoder(
    val cf: Int,
    val channels: Int,
    val mbWidth: Int,
    val mbHeight: Int,
    val tileX: IntArray,
    val tileY: IntArray,
    private val coef: Array<IntArray>,
    frameDcStep: IntArray,
    frameLpStep: IntArray,
    frameHpStep: IntArray,
    private val dcRound: Int,
    private val lpRound: Int,
    private val hpRound: Int,
    /** DC, lowpass and highpass steps of each channel for each tile in raster order, or null for the frame's. */
    private val tileSteps: Array<Array<IntArray>>?,
) {
    /** Where the bands of a tile go: one writer for all four in spatial order, a packet each in frequency order. */
    class Packets(val dc: JxrBitWriter, val lp: JxrBitWriter, val hp: JxrBitWriter, val fl: JxrBitWriter)

    private var dcStep = frameDcStep
    private var lpStep = frameLpStep
    private var hpStep = frameHpStep

    private val numV = tileX.size - 1
    private val numH = tileY.size - 1
    private val subsampled = cf == JxrColor.YUV_420
    private val planes = if (subsampled) 1 else channels
    private val stride = IntArray(channels) { if (it == 0 || !subsampled) 256 else 64 }

    val contexts = Array(numV + 1) {
        JxrContext(if (cf == JxrColor.Y_ONLY) 5 else 9)
    }

    private var pred = Array(channels) { JxrPlane.PredRow(mbWidth) }
    private var predAbove = Array(channels) { JxrPlane.PredRow(mbWidth) }

    private var column = 0
    private var tileColumn = 0
    private var tileRow = 0
    private var ctxLeft = false
    private var ctxTop = false
    private var resetContext = false
    private var resetTotals = false

    // The macroblock being coded: quantized DC and lowpass (blockDC of the decoder), their residuals,
    // quantized highpass in the decoder's layout, and the coded block patterns.
    private val q = Array(channels) { IntArray(16) }
    private val res = Array(channels) { IntArray(16) }
    private val hq = Array(channels) { IntArray(256) }
    private val recon = IntArray(256)
    private val cbp = IntArray(3)
    private var orientation = 0

    /** Which tile column a macroblock of column [x] and row [y] lies in, as the decoder's getTilePos finds it. */
    fun tilePosition(x: Int, y: Int) {
        if (x == 0) tileColumn = 0 else if (tileColumn < numV && x == tileX[tileColumn + 1]) tileColumn++
        if (y == 0) tileRow = 0 else if (tileRow < numH && y == tileY[tileRow + 1]) tileRow++
        ctxLeft = x == tileX[tileColumn]
        ctxTop = y == tileY[tileRow]
        resetContext = ((x - tileX[tileColumn]) and 0xF) == 0
        resetTotals = resetContext
        if (tileColumn == numV) {
            if (x + 1 == mbWidth) resetContext = true
        } else if (x + 1 == tileX[tileColumn + 1]) {
            resetContext = true
        }
    }

    val currentTileColumn: Int get() = tileColumn
    val currentTileRow: Int get() = tileRow

    fun endRow() {
        val t = pred
        pred = predAbove
        predAbove = t
    }

    /** Codes the macroblock at column [x] of row [y], which lies in tile [tile], to [out], with the context of its tile column. */
    fun encode(x: Int, y: Int, out: Packets, tile: Int) {
        column = x
        tileSteps?.let { t ->
            dcStep = t[tile][0]
            lpStep = t[tile][1]
            hpStep = t[tile][2]
        }
        val ctx = contexts[tileColumn]
        val mb = y * mbWidth + x
        quantizeLowpass(mb)
        predictDCAC()
        writeDC(ctx, out.dc)
        writeLowpass(ctx, out.lp)
        quantizeHighpass(mb)
        if (resetTotals) {
            ctx.scanHoriz.resetTotals()
            ctx.scanVert.resetTotals()
        }
        writeCBP(ctx, out.hp)
        writeCoeffs(ctx, out.hp, out.fl)
        if (resetContext) ctx.adaptHighpass()
        updatePrediction()
    }

    // --- Quantization --------------------------------------------------------------------------

    private fun quant(v: Int, step: Int, round: Int): Int {
        if (step == 1) return v
        val m = (abs(v) + (step * round shr 6)) / step
        return if (v < 0) -m else m
    }

    private fun quantizeLowpass(mb: Int) {
        for (ch in 0 until channels) {
            val c = coef[ch]
            val b = mb * stride[ch]
            val o = q[ch]
            o[0] = quant(c[b], dcStep[ch], dcRound)
            if (ch == 0 || !subsampled) {
                for (k in 1 until 16) o[k] = quant(c[b + JxrPlane.DC_INDEX[k]], lpStep[ch], lpRound)
            } else {
                o[1] = quant(c[b + 32], lpStep[ch], lpRound)
                o[2] = quant(c[b + 16], lpStep[ch], lpRound)
                o[3] = quant(c[b + 48], lpStep[ch], lpRound)
            }
        }
    }

    /**
     * The highpass coefficients quantized, with the first row or column of each block coded as
     * the difference from the block above or to the left, as the decoder's predACDec adds it back:
     * from the neighbour as the decoder reconstructs it, so rounding does not build up.
     */
    private fun quantizeHighpass(mb: Int) {
        val mode = 2 - orientation
        for (ch in 0 until channels) {
            val c = coef[ch]
            val b = mb * stride[ch]
            val out = hq[ch]
            val step = hpStep[ch]
            val blocks = stride[ch] / 16
            val luma = ch == 0 || !subsampled
            for (n in 0 until blocks) {
                val o = n * 16
                // The neighbour this block predicts from: the block above (n - 1) or to the left.
                var from = -1
                var predicted = NONE
                if (mode == 1 && (if (luma) n % 4 != 0 else n % 2 != 0)) {
                    from = o - 16
                    predicted = TOP_POSITIONS
                } else if (mode == 0 && n >= (if (luma) 4 else 2)) {
                    from = o - (if (luma) 64 else 32)
                    predicted = LEFT_POSITIONS
                }
                for (j in 1 until 16) {
                    val target = c[b + o + j]
                    val p = if (from >= 0 && (j == predicted[0] || j == predicted[1] || j == predicted[2])) recon[from + j] else 0
                    val v = quant(target - p, step, hpRound)
                    out[o + j] = v
                    recon[o + j] = v * step + p
                }
            }
        }
    }

    // --- Prediction ----------------------------------------------------------------------------

    /** getDCACPredMode, on the neighbours' quantized coefficients, as the decoder sees them. */
    private fun dcacMode(x: Int): Int {
        val dcMode = if (ctxLeft && ctxTop) {
            3
        } else if (ctxLeft) {
            1
        } else if (ctxTop) {
            0
        } else {
            val l = pred[0].dc[x - 1]
            val tp = predAbove[0].dc[x]
            val tl = predAbove[0].dc[x - 1]
            val strH: Int
            val strV: Int
            if (cf == JxrColor.Y_ONLY) {
                strH = abs(tl - l)
                strV = abs(tl - tp)
            } else {
                val scale = if (subsampled) 8 else 2
                strH = abs(tl - l) * scale + abs(predAbove[1].dc[x - 1] - pred[1].dc[x - 1]) +
                    abs(predAbove[2].dc[x - 1] - pred[2].dc[x - 1])
                strV = abs(tl - tp) * scale + abs(predAbove[1].dc[x - 1] - predAbove[1].dc[x]) +
                    abs(predAbove[2].dc[x - 1] - predAbove[2].dc[x])
            }
            if (strH * JxrPlane.ORIENT_WEIGHT < strV) 1 else if (strV * JxrPlane.ORIENT_WEIGHT < strH) 0 else 2
        }
        // One quantizer index for every macroblock, so the lowpass of a neighbour always predicts.
        val adMode = if (dcMode == 1) 1 else if (dcMode == 0) 0 else 2
        return dcMode + (adMode shl 2)
    }

    /** getACPredMode on the macroblock's quantized lowpass. */
    private fun acMode(): Int {
        val y = q[0]
        var strH = abs(y[1]) + abs(y[2]) + abs(y[3])
        var strV = abs(y[4]) + abs(y[8]) + abs(y[12])
        if (cf != JxrColor.Y_ONLY) {
            val u = q[1]
            val v = q[2]
            strH += abs(u[1]) + abs(v[1])
            strV += if (subsampled) abs(u[2]) + abs(v[2]) else abs(u[4]) + abs(v[4])
        }
        return if (strH * JxrPlane.ORIENT_WEIGHT < strV) 1 else if (strV * JxrPlane.ORIENT_WEIGHT < strH) 0 else 2
    }

    /** The residuals the decoder's predDCACDec adds the prediction back to. */
    private fun predictDCAC() {
        val x = column
        val mode = dcacMode(x)
        val dcMode = mode and 3
        val adMode = mode and 0xC
        for (ch in 0 until channels) q[ch].copyInto(res[ch])
        for (ii in 0 until planes) {
            val r = res[ii]
            when (dcMode) {
                1 -> r[0] -= predAbove[ii].dc[x]
                0 -> r[0] -= pred[ii].dc[x - 1]
                2 -> r[0] -= (pred[ii].dc[x - 1] + predAbove[ii].dc[x]) shr 1
            }
            if (adMode == 4) {
                val a = predAbove[ii].ad
                r[4] -= a[6 * x + 3]
                r[8] -= a[6 * x + 4]
                r[12] -= a[6 * x + 5]
            } else if (adMode == 0) {
                val a = pred[ii].ad
                r[1] -= a[6 * (x - 1)]
                r[2] -= a[6 * (x - 1) + 1]
                r[3] -= a[6 * (x - 1) + 2]
            }
        }
        if (subsampled) {
            for (ii in 1..2) {
                val r = res[ii]
                when (dcMode) {
                    1 -> r[0] -= predAbove[ii].dc[x]
                    0 -> r[0] -= pred[ii].dc[x - 1]
                    2 -> r[0] -= (pred[ii].dc[x - 1] + predAbove[ii].dc[x] + 1) shr 1
                }
                if (adMode == 4) {
                    r[2] -= predAbove[ii].ad[6 * x + 1]
                } else if (adMode == 0) {
                    r[1] -= pred[ii].ad[6 * (x - 1)]
                }
            }
        }
        orientation = 2 - acMode()
    }

    /** updatePredInfo. */
    private fun updatePrediction() {
        val x = column
        for (i in 0 until planes) {
            val p = q[i]
            val r = pred[i]
            r.dc[x] = p[0]
            r.qp[x] = 0
            r.ad[6 * x] = p[1]
            r.ad[6 * x + 1] = p[2]
            r.ad[6 * x + 2] = p[3]
            r.ad[6 * x + 3] = p[4]
            r.ad[6 * x + 4] = p[8]
            r.ad[6 * x + 5] = p[12]
        }
        if (subsampled) {
            for (i in 1..2) {
                val p = q[i]
                val r = pred[i]
                r.dc[x] = p[0]
                r.qp[x] = 0
                r.ad[6 * x] = p[1]
                r.ad[6 * x + 1] = p[2]
            }
        }
    }

    // --- DC ------------------------------------------------------------------------------------

    /** EncodeMacroblockDC: the mirror of DecodeMacroblockDC. */
    private fun writeDC(ctx: JxrContext, w: JxrBitWriter) {
        val lm = IntArray(2)
        if (cf == JxrColor.Y_ONLY) {
            var modelBits = ctx.modelDC.flcBits[0]
            var lmAt = 0
            for (i in 0 until channels) {
                val v = res[i][0]
                val a = abs(v)
                val hi = a ushr modelBits
                w.bit(hi != 0)
                if (hi != 0) {
                    writeAbsLevel(ctx.expt[3], hi + 1, w)
                    lm[lmAt]++
                }
                if (modelBits != 0) w.bits(a, modelBits)
                if (v != 0) w.bit(v < 0)
                lmAt = 1
                modelBits = ctx.modelDC.flcBits[1]
            }
        } else {
            val mbY = ctx.modelDC.flcBits[0]
            val mbC = ctx.modelDC.flcBits[1]
            val vy = res[0][0]
            val vu = res[1][0]
            val vv = res[2][0]
            val hy = abs(vy) ushr mbY
            val hu = abs(vu) ushr mbC
            val hv = abs(vv) ushr mbC
            val index = (if (hy != 0) 4 else 0) or (if (hu != 0) 2 else 0) or (if (hv != 0) 1 else 0)
            w.code(ctx.expt[2].codes()[index])
            if (hy != 0) {
                writeAbsLevel(ctx.expt[3], hy + 1, w)
                lm[0]++
            }
            if (mbY != 0) w.bits(abs(vy), mbY)
            if (vy != 0) w.bit(vy < 0)
            if (hu != 0) {
                writeAbsLevel(ctx.expt[4], hu + 1, w)
                lm[1]++
            }
            if (mbC != 0) w.bits(abs(vu), mbC)
            if (vu != 0) w.bit(vu < 0)
            if (hv != 0) {
                writeAbsLevel(ctx.expt[4], hv + 1, w)
                lm[1]++
            }
            if (mbC != 0) w.bits(abs(vv), mbC)
            if (vv != 0) w.bit(vv < 0)
        }
        JxrContext.updateModel(cf, channels, lm, ctx.modelDC)
    }

    // --- Lowpass -------------------------------------------------------------------------------

    private val runs = IntArray(16)
    private val levels = IntArray(16)
    private val positions = IntArray(16)

    /** EncodeMacroblockLowpass: the mirror of DecodeMacroblockLowpass. */
    private fun writeLowpass(ctx: JxrContext, w: JxrBitWriter) {
        val scan = ctx.scanLowpass
        val fullPlanes = if (subsampled) 2 else channels
        val lm = IntArray(2)
        if (resetTotals) scan.resetTotals()
        // Which blocks have a coefficient left once the flexbits are taken off.
        var cbpLP = 0
        for (ch in 0 until fullPlanes) {
            val mb = ctx.modelLP.flcBits[if (ch == 0) 0 else 1]
            var any = false
            if (subsampled && ch == 1) {
                for (k in 1..3) for (c in 1..2) if (abs(res[c][k]) ushr mb != 0) any = true
            } else {
                for (k in 1 until 16) if (abs(res[ch][k]) ushr mb != 0) any = true
            }
            if (any) cbpLP = cbpLP or (1 shl ch)
        }
        if (cf == JxrColor.Y_ONLY) {
            for (ch in 0 until channels) w.bits(cbpLP ushr ch, 1)
        } else {
            val countM = ctx.cbpCountMax
            val countZ = ctx.cbpCountZero
            val max = fullPlanes * 4 - 5
            if (countZ <= 0 || countM < 0) {
                val v = if (countM < countZ) max - cbpLP else cbpLP
                if (v == 0) {
                    w.bit(false)
                } else {
                    w.bit(true)
                    if (v == 1) {
                        w.bits(0, fullPlanes - 1)
                    } else {
                        w.bits(v shr 1, fullPlanes - 1)
                        w.bits(v and 1, 1)
                    }
                }
            } else {
                w.bits(cbpLP, fullPlanes)
            }
            ctx.cbpCountMax = (countM + 1 - 4 * (if (cbpLP == max) 1 else 0)).coerceIn(-8, 7)
            ctx.cbpCountZero = (countZ + 1 - 4 * (if (cbpLP == 0) 1 else 0)).coerceIn(-8, 7)
        }
        var lmAt = 0
        for (ch in 0 until fullPlanes) {
            val mb = ctx.modelLP.flcBits[if (ch == 0) 0 else 1]
            val chroma420 = subsampled && ch == 1
            if ((cbpLP ushr ch) and 1 != 0) {
                var n = 0
                var run = 0
                if (chroma420) {
                    // U and V interleave, k = 1, 2, 3, through six positions from 10.
                    for (k in 0 until 6) {
                        val v = res[(k and 1) + 1][1 + (k shr 1)]
                        val hi = abs(v) ushr mb
                        if (hi == 0) {
                            run++
                        } else {
                            runs[n] = run
                            levels[n] = if (v < 0) -hi else hi
                            n++
                            run = 0
                        }
                    }
                    writeRunLevel(ctx.expt, JxrContext.CTDC, true, n, 10, w)
                } else {
                    val r = res[ch]
                    for (index in 1 until 16) {
                        val v = r[scan.scan[index]]
                        val hi = abs(v) ushr mb
                        if (hi == 0) {
                            run++
                        } else {
                            runs[n] = run
                            levels[n] = if (v < 0) -hi else hi
                            positions[n] = index
                            n++
                            run = 0
                        }
                    }
                    writeRunLevel(ctx.expt, JxrContext.CTDC, ch > 0, n, 1, w)
                    for (k in 0 until n) scan.hit(positions[k])
                }
                lm[lmAt] += n
            }
            if (mb != 0) {
                if (chroma420) {
                    for (k in 1..3) for (c in 1..2) writeFlex(res[c][k], mb, w)
                } else {
                    for (k in 1 until 16) writeFlex(res[ch][k], mb, w)
                }
            }
            lmAt = 1
        }
        JxrContext.updateModel(cf, channels, lm, ctx.modelLP)
        if (resetContext) ctx.adaptLowpass()
    }

    /** The low [mb] bits of a coefficient, and its sign when the coded part is 0 and they are not. */
    private fun writeFlex(v: Int, mb: Int, w: JxrBitWriter) {
        val a = abs(v)
        w.bits(a, mb)
        if (a ushr mb == 0 && a != 0) w.bit(v < 0)
    }

    // --- Highpass ------------------------------------------------------------------------------

    /** The coded block patterns of the macroblock, and their differences from the prediction, as EncodeCBP writes them. */
    private fun writeCBP(ctx: JxrContext, w: JxrBitWriter) {
        val mbLuma = ctx.modelAC.flcBits[0]
        val mbChroma = ctx.modelAC.flcBits[1]
        for (i in 0 until channels) {
            val luma = i == 0 || !subsampled
            val mb = if (i == 0) mbLuma else mbChroma
            var bits = 0
            val n = if (luma) 16 else 4
            for (index in 0 until n) {
                val o = if (luma) JxrPlane.BLOCK_OFFSET[index] else JxrPlane.BLOCK_OFFSET_UV[index]
                var any = false
                for (j in 1 until 16) if (abs(hq[i][o + j]) ushr mb != 0) {
                    any = true
                    break
                }
                if (any) bits = bits or (1 shl index)
            }
            cbp[i] = bits
        }
        // The decoder's predCBP, run backwards: each plane's difference from its prediction.
        val x = column
        val diff = IntArray(3)
        for (i in 0 until planes) {
            diff[i] = diffCBP16(cbp[i], x, i, ctx)
            pred[i].cbp[x] = cbp[i]
        }
        if (subsampled) {
            for (c in 1..2) {
                diff[c] = diffCBPChroma(cbp[c], x, c, ctx)
                pred[c].cbp[x] = cbp[c]
            }
        }
        val cbpY1 = ctx.cbpY1
        val cbpY = ctx.cbpY
        var quads = 0
        val chromaFlags = IntArray(4)
        for (block in 0 until 4) {
            val lumaBits = (diff[0] ushr (block * 4)) and 0xF
            var flags = 0
            if (subsampled) {
                if ((diff[1] ushr block) and 1 != 0) flags = flags or 1
                if ((diff[2] ushr block) and 1 != 0) flags = flags or 2
            } else if (cf == JxrColor.YUV_444) {
                if ((diff[1] ushr (block * 4)) and 0xF != 0) flags = flags or 1
                if ((diff[2] ushr (block * 4)) and 0xF != 0) flags = flags or 2
            }
            chromaFlags[block] = flags
            if (lumaBits != 0 || flags != 0) quads = quads or (1 shl block)
        }
        val kind = quads.countOneBits()
        w.code(cbpY1.codes()[kind])
        cbpY1.count(kind)
        writePattern(quads, kind, w)
        for (block in 0 until 4) {
            if ((quads ushr block) and 1 == 0) continue
            val lumaBits = (diff[0] ushr (block * 4)) and 0xF
            val code1 = FLC_IN0[lumaBits]
            val cls = classOf(code1)
            val flags = chromaFlags[block]
            val value = if (flags == 0) cls else cls + 6
            val symbol = minOf(value, 9) - 1
            w.code(cbpY.codes()[symbol])
            cbpY.count(symbol)
            if (value >= 6) {
                when (flags) {
                    1 -> w.bits(1, 1)
                    2 -> w.bits(1, 2)
                    else -> w.bits(0, 2)
                }
                if (value >= 9) {
                    when (value) {
                        9 -> w.bits(1, 1)
                        10 -> w.bits(1, 2)
                        else -> w.bits(0, 2)
                    }
                }
            }
            val flc = JxrPlane.FLC0[cls]
            if (flc != 0) w.bits(code1 - JxrPlane.FLC_OFF0[cls], flc)
            if (cf == JxrColor.YUV_444) {
                for (k in 0 until 2) {
                    if ((flags ushr k) and 1 == 0) continue
                    val p = (diff[1 + k] ushr (block * 4)) and 0xF
                    val ones = p.countOneBits()
                    w.code(ctx.expt[1].codes()[ones - 1])
                    writePattern(p, ones, w)
                }
            }
        }
    }

    /** The bits that follow a pattern's count of set bits, as the decoder's pattern reads them. */
    private fun writePattern(p: Int, kind: Int, w: JxrBitWriter) {
        when (kind) {
            1 -> w.bits(p.countTrailingZeroBits(), 2)
            2 -> when (p) {
                3 -> w.bits(0, 2)
                5 -> w.bits(1, 2)
                6 -> w.bits(4, 3)
                9 -> w.bits(5, 3)
                10 -> w.bits(6, 3)
                else -> w.bits(7, 3)
            }
            3 -> w.bits((p xor 0xF).countTrailingZeroBits(), 2)
        }
    }

    /** The inverse of predCBPCDec: the difference the decoder turns back into [actual]. */
    private fun diffCBP16(actual: Int, x: Int, c: Int, ctx: JxrContext): Int {
        val c1 = if (c != 0) 1 else 0
        var v = actual
        if (ctx.cbpState[c1] == 0) {
            v = v xor ((v and 0x3300) shl 2)
            v = v xor ((v and 0xCC) shl 6)
            v = v xor ((v and 0x33) shl 2)
            v = v xor (0x20 and (v shl 1))
            v = v xor (0x10 and (v shl 3))
            v = v xor (0x02 and (v shl 1))
            v = if (ctxLeft) {
                if (ctxTop) v xor 1 else v xor ((predAbove[c].cbp[x] shr 10) and 1)
            } else {
                v xor ((pred[c].cbp[x - 1] shr 5) and 1)
            }
        } else if (ctx.cbpState[c1] == 2) {
            v = v xor 0xFFFF
        }
        updateCbpModel(ctx, c1, JxrPlane.ones(actual))
        return v
    }

    /** The inverse of predCBPC420Dec. */
    private fun diffCBPChroma(actual: Int, x: Int, c: Int, ctx: JxrContext): Int {
        var v = actual
        if (ctx.cbpState[1] == 0) {
            v = v xor ((v and 0x3) shl 2)
            v = v xor (0x02 and (v shl 1))
            v = if (ctxLeft) {
                if (ctxTop) v xor 1 else v xor ((predAbove[c].cbp[x] shr 2) and 1)
            } else {
                v xor ((pred[c].cbp[x - 1] shr 1) and 1)
            }
        } else if (ctx.cbpState[1] == 2) {
            v = v xor 0xF
        }
        updateCbpModel(ctx, 1, JxrPlane.ones(actual) * 4)
        return v
    }

    private fun updateCbpModel(ctx: JxrContext, c1: Int, orig: Int) {
        ctx.cbpCount0[c1] = JxrPlane.saturate32(ctx.cbpCount0[c1] + orig - JxrPlane.AVG_NDIFF)
        ctx.cbpCount1[c1] = JxrPlane.saturate32(ctx.cbpCount1[c1] + 16 - orig - JxrPlane.AVG_NDIFF)
        ctx.cbpState[c1] = if (ctx.cbpCount0[c1] < 0) {
            if (ctx.cbpCount0[c1] < ctx.cbpCount1[c1]) 1 else 2
        } else if (ctx.cbpCount1[c1] < 0) {
            2
        } else {
            0
        }
    }

    /** EncodeCoeffs: the mirror of DecodeCoeffs. */
    private fun writeCoeffs(ctx: JxrContext, w: JxrBitWriter, fl: JxrBitWriter) {
        var modelBits = ctx.modelAC.flcBits[0]
        val lm = IntArray(2)
        var lmAt = 0
        val scan = if (orientation == 1) ctx.scanVert else ctx.scanHoriz
        val blocks = if (subsampled) 6 else 4
        var chroma = false
        for (i in 0 until planes) {
            var index = 0
            for (block in 0 until blocks) {
                for (sub in 0 until 4) {
                    var ch = i
                    var at = JxrPlane.BLOCK_OFFSET[index and 0xF]
                    var bit = index
                    if (block >= 4) {
                        ch = block - 3
                        at = JxrPlane.BLOCK_OFFSET_UV[sub]
                        bit = sub
                    }
                    val coded = (cbp[ch] ushr bit) and 1 != 0
                    lm[lmAt] += writeBlockHighpass(coded, chroma, ctx, hq[ch], at, scan, modelBits, w, fl)
                    index++
                }
                if (block == 3) {
                    modelBits = ctx.modelAC.flcBits[1]
                    lmAt = 1
                    chroma = true
                }
            }
        }
        JxrContext.updateModel(cf, channels, lm, ctx.modelAC)
    }

    /** One highpass block: its run-level codes when [coded], then its flexbits. */
    private fun writeBlockHighpass(
        coded: Boolean, chroma: Boolean, ctx: JxrContext, v: IntArray, at: Int, scan: JxrContext.Scan, mb: Int, w: JxrBitWriter, fl: JxrBitWriter,
    ): Int {
        var n = 0
        if (coded) {
            var run = 0
            for (loc in 1 until 16) {
                val x = v[at + scan.scan[loc]]
                val hi = abs(x) ushr mb
                if (hi == 0) {
                    run++
                } else {
                    runs[n] = run
                    levels[n] = if (x < 0) -hi else hi
                    positions[n] = loc
                    n++
                    run = 0
                }
            }
            writeRunLevel(ctx.expt, JxrContext.CTDC + JxrContext.CONTEXTX, chroma, n, 1, w)
            for (k in 0 until n) scan.hit(positions[k])
        }
        if (mb != 0) {
            for (k in 1 until 16) writeFlex(v[at + JxrContext.DCT_INDEX[k]], mb, fl)
        }
        return n
    }

    // --- Run-level codes -----------------------------------------------------------------------

    /**
     * The [n] run-level pairs in [runs] and [levels] of a block whose first position is
     * [location0], as DecodeBlock and DecodeBlockHighpass read them: the first symbol tells
     * whether the first run is 0, whether the level passes 1 and what follows; each later symbol
     * whether its level passes 1 and what follows, in an alphabet that narrows at the block's end.
     */
    private fun writeRunLevel(ah: Array<JxrVlc>, offset: Int, chroma: Boolean, n: Int, location0: Int, w: JxrBitWriter) {
        check(n > 0)
        val ah1 = offset + if (chroma) 3 else 0
        fun next(i: Int) = if (i + 1 >= n) 0 else if (runs[i + 1] == 0) 1 else 2
        var location = location0
        val sr = if (runs[0] == 0) 1 else 0
        var srn = next(0)
        val gt1 = abs(levels[0]) > 1
        val first = (srn shl 2) or (if (gt1) 2 else 0) or sr
        val t = ah[ah1]
        w.code(t.codes()[first])
        t.count2(first)
        var cont = sr and srn
        w.bit(levels[0] < 0)
        if (gt1) writeAbsLevel(ah[6 + offset + cont], abs(levels[0]), w)
        if (sr == 0) writeSignificantRun(runs[0], 15 - location, ah[0], w)
        location += runs[0] + 1
        for (i in 1 until n) {
            if (srn and 1 == 0) writeSignificantRun(runs[i], 15 - location, ah[0], w)
            location += runs[i] + 1
            val srnNext = next(i)
            val g = abs(levels[i]) > 1
            val index = (srnNext shl 1) or (if (g) 1 else 0)
            writeIndex(location, ah[ah1 + cont + 1], index, w)
            srn = srnNext
            cont = cont and srn
            w.bit(levels[i] < 0)
            if (g) writeAbsLevel(ah[6 + offset + cont], abs(levels[i]), w)
        }
    }

    /** The symbol after the first (DecodeIndex). */
    private fun writeIndex(location: Int, ah: JxrVlc, index: Int, w: JxrBitWriter) {
        when {
            location < 15 -> {
                w.code(ah.codes()[index])
                ah.count2(index)
            }
            location == 15 -> when (index) {
                0 -> w.bits(0, 1)
                2 -> w.bits(2, 2)
                1 -> w.bits(6, 3)
                else -> w.bits(7, 3)
            }
            else -> w.bits(index, 1)
        }
    }

    /** A run of 1 or more zeros, at most [maxRun] (DecodeSignificantRun). */
    private fun writeSignificantRun(run: Int, maxRun: Int, ah: JxrVlc, w: JxrBitWriter) {
        if (maxRun < 5) {
            if (maxRun == 1) return
            when (run) {
                1 -> w.bits(1, 1)
                2 -> if (maxRun == 2) w.bits(0, 1) else w.bits(1, 2)
                3 -> if (maxRun == 3) w.bits(0, 2) else w.bits(1, 3)
                else -> w.bits(0, 3)
            }
            return
        }
        val bin = JxrPlane.SIGNIFICANT_RUN_BIN[maxRun] * 5
        var s = 4
        while (s > 0 && JxrPlane.RUN_REMAP[bin + s] > run) s--
        w.code(ah.codes()[s])
        val flc = JxrPlane.SIGNIFICANT_RUN_FIXED_LENGTH[bin + s]
        if (flc != 0) w.bits(run - JxrPlane.RUN_REMAP[bin + s], flc)
    }

    /** A magnitude of 2 or more (DecodeSignificantAbsLevel). */
    private fun writeAbsLevel(ah: JxrVlc, value: Int, w: JxrBitWriter) {
        val index = when {
            value < 4 -> value - 2
            value < 6 -> 2
            value < 10 -> 3
            value < 14 -> 4
            value < 18 -> 5
            else -> 6
        }
        w.code(ah.codes()[index])
        ah.count(index)
        if (index in 2..5) {
            w.bits(value - JxrPlane.LEVEL_REMAP[index], JxrPlane.LEVEL_FIXED_LENGTH[index])
        } else if (index == 6) {
            val v = value - 2
            val fixed = 31 - v.countLeadingZeroBits()
            when {
                fixed < 19 -> w.bits(fixed - 4, 4)
                fixed < 22 -> {
                    w.bits(15, 4)
                    w.bits(fixed - 19, 2)
                }
                else -> {
                    w.bits(15, 4)
                    w.bits(3, 2)
                    w.bits(fixed - 22, 3)
                }
            }
            w.bits(v - (1 shl fixed), fixed)
        }
    }

    private companion object {
        val NONE = intArrayOf(-1, -1, -1)

        /** The positions predACDec predicts from the block above and from the block to the left. */
        val TOP_POSITIONS = intArrayOf(2, 10, 9)
        val LEFT_POSITIONS = intArrayOf(1, 5, 6)

        /** The inverse of FLC_OUT0: which code a block's four luma bits take. */
        val FLC_IN0 = IntArray(16).also { t -> for (i in 0 until 16) t[JxrPlane.FLC_OUT0[i]] = i }

        /** The class (0 to 5) whose range of FLC_OFF0 holds [code1]. */
        fun classOf(code1: Int): Int {
            for (v in 0 until 6) {
                val lo = JxrPlane.FLC_OFF0[v]
                if (code1 >= lo && code1 < lo + (1 shl JxrPlane.FLC0[v])) return v
            }
            error("code $code1")
        }
    }
}
