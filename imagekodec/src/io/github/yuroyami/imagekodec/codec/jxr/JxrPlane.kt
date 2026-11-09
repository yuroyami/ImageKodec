/*
 * One JPEG XR image plane: a Kotlin port of jxrlib's macroblock decoding (image/decode/segdec.c),
 * its prediction and dequantization (image/decode/strPredQuantDec.c, image/sys/strPredQuant.c),
 * its tile and quantizer bookkeeping (image/sys/strcodec.c, image/decode/strdec.c), at
 * 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE. Altered for Kotlin: the two macroblock rows of a channel share one array,
 * so jxrlib's pointers into them become indices.
 */
package io.github.yuroyami.imagekodec.codec.jxr

import kotlin.math.abs

/**
 * The decoding state of one image plane, jxrlib's CWMImageStrCodec: the colour plane, or the
 * alpha plane that T.832 interleaves with it macroblock by macroblock.
 *
 * Each channel keeps two macroblock rows in [buf]: the row being decoded, which the transform
 * finishes once the row below is there, and the row above it. [p0] and [p1] are the indices of the
 * current macroblock in each, as jxrlib's p0MBbuffer and p1MBbuffer point into its rows.
 */
internal class JxrPlane(
    val image: JxrImageHeader,
    val header: JxrPlaneHeader,
    val secondary: Boolean,
) {
    val cf: Int = if (secondary) JxrColor.Y_ONLY else header.colorFormat
    val channels: Int = if (secondary) 1 else header.channels
    val scaled: Boolean = header.scaledArith
    val subband: Int = header.subband
    val qpMode: Int = header.qpMode
    val mbWidth: Int = image.mbWidth
    val mbHeight: Int = image.mbHeight
    val tileX: IntArray = image.tileX
    val tileY: IntArray = image.tileY
    val numV: Int = tileX.size - 1
    val numH: Int = tileY.size - 1

    /** The planes whose blocks are coded alone: one for 4:2:0 and 4:2:2, whose chroma rides with luma. */
    val planes: Int = if (cf == JxrColor.YUV_420 || cf == JxrColor.YUV_422) 1 else channels

    /** Samples of one macroblock of each channel. */
    val stride = IntArray(channels) { if (it == 0) 256 else CHROMA_BLOCKS[cf] * 16 }
    private val rowLength = IntArray(channels) { stride[it] * mbWidth }
    val buf = Array(channels) { IntArray(2 * rowLength[it]) }
    private var swapped = false

    /** Where row a0 (the row above) and a1 (the current row) start in [buf] of channel [ch]. */
    fun a0(ch: Int): Int = if (swapped) rowLength[ch] else 0
    fun a1(ch: Int): Int = if (swapped) 0 else rowLength[ch]

    val p0 = IntArray(channels)
    val p1 = IntArray(channels)

    var row = 0
    var column = 0
    var tileColumn = 0
    var tileRow = 0
    var ctxLeft = false
    var ctxTop = false
    var resetContext = false
    var resetTotals = false

    /** Set from the alpha plane's subbands when there is one, as jxrlib's shared m_Dparam is. */
    var decodeLP = true
    var decodeHP = true
    var skipFlexbits = false

    val contexts = Array(numV + 1) {
        JxrContext(if (cf == JxrColor.Y_ONLY || cf == JxrColor.NCOMPONENT || cf == JxrColor.CMYK) 5 else 9)
    }
    val tiles = Array(numV + 1) { Tile(channels) }

    // The macroblock being decoded (CWMIMBInfo).
    val blockDC = Array(channels) { IntArray(16) }
    var orientation = 0
    val cbp = IntArray(16)
    val diffCbp = IntArray(16)
    var qIndexLP = 0
    var qIndexHP = 0

    // CWMIPredInfo for this row and the row above, per channel.
    private var pred = Array(channels) { PredRow(mbWidth) }
    private var predAbove = Array(channels) { PredRow(mbWidth) }

    // The state of the hard tile edges of invTransformMacroblock_alteredOperators_hard.
    var mbX = 0
    var mbY = 0
    var tX = 0
    var tY = 0
    var vertTileBoundary = false
    var horiTileBoundary = false
    var oneMBLeftVertTB = false
    var oneMBRightVertTB = false
    val predBefore = Array(2) { IntArray(2) }
    val predAfter = Array(2) { IntArray(2) }

    class PredRow(n: Int) {
        val qp = IntArray(n)
        val cbp = IntArray(n)
        val dc = IntArray(n)
        val ad = IntArray(6 * n)
    }

    /** A tile column's quantizers (CWMITile): index and step of each band, channel and position. */
    class Tile(channels: Int) {
        val dcIndex = Array(channels) { IntArray(1) }
        val dcQP = Array(channels) { IntArray(1) }
        val lpIndex = Array(channels) { IntArray(16) }
        val lpQP = Array(channels) { IntArray(16) }
        val hpIndex = Array(channels) { IntArray(16) }
        val hpQP = Array(channels) { IntArray(16) }
        var numQPLP = 1
        var numQPHP = 1
        var bitsLP = 0
        var bitsHP = 0
    }

    init {
        // StrDecInit: the quantizers the frame fixes for every tile.
        if (qpMode and 1 == 0) {
            for (t in tiles) {
                for (i in 0 until channels) t.dcIndex[i][0] = header.qpIndexDC[i]
                formatQuantizer(t.dcIndex, t.dcQP, (qpMode shr 3) and 3, 0, true)
            }
        }
        if (subband != JxrBands.DC_ONLY) {
            if (qpMode and 2 == 0) {
                for (t in tiles) {
                    if (qpMode and 0x200 == 0) {
                        useDC(t)
                    } else {
                        for (i in 0 until channels) t.lpIndex[i][0] = header.qpIndexLP[i]
                        formatQuantizer(t.lpIndex, t.lpQP, (qpMode shr 5) and 3, 0, true)
                    }
                }
            }
            if (subband != JxrBands.NO_HIGHPASS && qpMode and 4 == 0) {
                for (t in tiles) {
                    if (qpMode and 0x400 == 0) {
                        useLP(t, 1)
                    } else {
                        for (i in 0 until channels) t.hpIndex[i][0] = header.qpIndexHP[i]
                        formatQuantizer(t.hpIndex, t.hpQP, (qpMode shr 7) and 3, 0, false)
                    }
                }
            }
        }
    }

    // --- Rows ----------------------------------------------------------------------------------

    /** initMRPtr and the clearing of the current row at the start of a macroblock row. */
    fun startRow() {
        for (ch in 0 until channels) {
            p0[ch] = a0(ch)
            p1[ch] = a1(ch)
            buf[ch].fill(0, a1(ch), a1(ch) + rowLength[ch])
        }
    }

    /** advanceMRPtr. */
    fun advance() {
        for (ch in 0 until channels) {
            p0[ch] += stride[ch]
            p1[ch] += stride[ch]
        }
    }

    /** advanceOneMBRow and swapMRPtr. */
    fun endRow() {
        val t = pred
        pred = predAbove
        predAbove = t
        swapped = !swapped
    }

    /** getTilePos: which tile macroblock ([x], [y]) lies in, and whether it starts a tile or a run of 16. */
    fun tilePosition(x: Int, y: Int) {
        if (x == 0) {
            tileColumn = 0
        } else if (tileColumn < numV && x == tileX[tileColumn + 1]) {
            tileColumn++
        }
        if (y == 0) {
            tileRow = 0
        } else if (tileRow < numH && y == tileY[tileRow + 1]) {
            tileRow++
        }
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

    // --- Quantizers ----------------------------------------------------------------------------

    /** formatQuantizer: copies the shared indices to the channels that share them, and finds each step. */
    fun formatQuantizer(index: Array<IntArray>, qp: Array<IntArray>, chMode: Int, pos: Int, shiftedUV: Boolean) {
        for (ch in 0 until channels) {
            if (ch > 0) {
                if (chMode == 0) index[ch][pos] = index[0][pos] else if (chMode == 1) index[ch][pos] = index[1][pos]
            }
            qp[ch][pos] = remapQP(index[ch][pos], if (ch > 0 && shiftedUV) SHIFT_ZERO - 1 else SHIFT_ZERO, scaled)
        }
    }

    /** useDCQuantizer. */
    fun useDC(t: Tile) {
        for (ch in 0 until channels) {
            t.lpIndex[ch][0] = t.dcIndex[ch][0]
            t.lpQP[ch][0] = t.dcQP[ch][0]
        }
    }

    /** useLPQuantizer. */
    fun useLP(t: Tile, count: Int) {
        for (ch in 0 until channels) {
            for (q in 0 until count) {
                t.hpIndex[ch][q] = t.lpIndex[ch][q]
                t.hpQP[ch][q] = t.lpQP[ch][q]
            }
        }
    }

    /** readQuantizer: a tile's quantizer indices at [pos], and how its channels share them. */
    private fun readQuantizer(index: Array<IntArray>, io: JxrBits, pos: Int): Int {
        val mode = if (channels > 1) io.bits(2) else 0
        index[0][pos] = io.bits(8)
        if (mode == 1) {
            index[1][pos] = io.bits(8)
        } else if (mode > 0) {
            for (i in 1 until channels) index[i][pos] = io.bits(8)
        }
        return mode
    }

    /** readTileHeaderDC. */
    fun readTileHeaderDC(io: JxrBits) {
        if (qpMode and 1 == 0) return
        val t = tiles[tileColumn]
        val mode = readQuantizer(t.dcIndex, io, 0)
        formatQuantizer(t.dcIndex, t.dcQP, mode, 0, true)
    }

    /** readTileHeaderLP. */
    fun readTileHeaderLP(io: JxrBits) {
        if (subband == JxrBands.DC_ONLY || qpMode and 2 == 0) return
        val t = tiles[tileColumn]
        val useDC = io.bool() == 1
        t.bitsLP = 0
        t.numQPLP = 1
        if (useDC) {
            useDC(t)
        } else {
            t.numQPLP = io.bits(4) + 1
            t.bitsLP = dquantBits(t.numQPLP)
            for (i in 0 until t.numQPLP) {
                val mode = readQuantizer(t.lpIndex, io, i)
                formatQuantizer(t.lpIndex, t.lpQP, mode, i, true)
            }
        }
    }

    /** readTileHeaderHP. */
    fun readTileHeaderHP(io: JxrBits) {
        if (subband == JxrBands.DC_ONLY || subband == JxrBands.NO_HIGHPASS || qpMode and 4 == 0) return
        val t = tiles[tileColumn]
        val useLP = io.bool() == 1
        t.bitsHP = 0
        t.numQPHP = 1
        if (useLP) {
            t.numQPHP = t.numQPLP
            useLP(t, t.numQPHP)
        } else {
            t.numQPHP = io.bits(4) + 1
            t.bitsHP = dquantBits(t.numQPHP)
            for (i in 0 until t.numQPHP) {
                val mode = readQuantizer(t.hpIndex, io, i)
                formatQuantizer(t.hpIndex, t.hpQP, mode, i, false)
            }
        }
    }

    // --- DC ------------------------------------------------------------------------------------

    /** DecodeMacroblockDC. */
    fun decodeDC(ctx: JxrContext) {
        val t = tiles[tileColumn]
        val io = ctx.ioDC
        for (i in 0 until channels) blockDC[i].fill(0)
        val lm = IntArray(2)
        var lmAt = 0
        var modelBits = ctx.modelDC.flcBits[0]
        qIndexLP = 0
        qIndexHP = 0
        if (!image.frequencyOrder && subband != JxrBands.DC_ONLY) {
            if (t.bitsLP > 0) qIndexLP = decodeQPIndex(io, t.bitsLP)
            if (subband != JxrBands.NO_HIGHPASS && t.bitsHP > 0) qIndexHP = decodeQPIndex(io, t.bitsHP)
        }
        if (t.bitsHP == 0 && t.numQPHP > 1) qIndexHP = qIndexLP
        if (qIndexLP >= t.numQPLP || qIndexHP >= t.numQPHP) jxrFail("a quantizer index past the tile's quantizers")
        if (cf == JxrColor.Y_ONLY || cf == JxrColor.CMYK || cf == JxrColor.NCOMPONENT) {
            for (i in 0 until channels) {
                var q = 0
                if (io.bool() == 1) {
                    q = absLevel(ctx.expt[3], io) - 1
                    lm[lmAt]++
                }
                if (modelBits != 0) q = (q shl modelBits) or io.bits(modelBits)
                if (q != 0 && io.bool() == 1) q = -q
                blockDC[i][0] = q
                lmAt = 1
                modelBits = ctx.modelDC.flcBits[1]
            }
        } else {
            val index = io.huff(ctx.expt[2].table)
            var qY = index shr 2
            var qU = (index shr 1) and 1
            var qV = index and 1
            if (qY != 0) {
                qY = absLevel(ctx.expt[3], io) - 1
                lm[0]++
            }
            if (modelBits != 0) qY = (qY shl modelBits) or io.bits(modelBits)
            if (qY != 0 && io.bool() == 1) qY = -qY
            blockDC[0][0] = qY
            modelBits = ctx.modelDC.flcBits[1]
            if (qU != 0) {
                qU = absLevel(ctx.expt[4], io) - 1
                lm[1]++
            }
            if (modelBits != 0) qU = (qU shl modelBits) or io.bits(modelBits)
            if (qU != 0 && io.bool() == 1) qU = -qU
            blockDC[1][0] = qU
            if (qV != 0) {
                qV = absLevel(ctx.expt[4], io) - 1
                lm[1]++
            }
            if (modelBits != 0) qV = (qV shl modelBits) or io.bits(modelBits)
            if (qV != 0 && io.bool() == 1) qV = -qV
            blockDC[2][0] = qV
        }
        JxrContext.updateModel(cf, channels, lm, ctx.modelDC)
        if (subband == JxrBands.DC_ONLY && resetContext) {
            for (k in 2 until 5) ctx.expt[k].adapt()
        }
    }

    // --- Lowpass -------------------------------------------------------------------------------

    private val runLevels = IntArray(32)

    /** DecodeMacroblockLowpass. */
    fun decodeLowpass(ctx: JxrContext) {
        val scan = ctx.scanLowpass
        val io = ctx.ioLP
        val fullPlanes = if (cf == JxrColor.YUV_420 || cf == JxrColor.YUV_422) 2 else channels
        var modelBits = ctx.modelLP.flcBits[0]
        val lm = IntArray(2)
        var lmAt = 0
        var cbpLP = 0
        if (image.frequencyOrder && tiles[tileColumn].bitsLP > 0) {
            qIndexLP = decodeQPIndex(io, tiles[tileColumn].bitsLP)
            if (qIndexLP >= tiles[tileColumn].numQPLP) jxrFail("a quantizer index past the tile's quantizers")
        }
        if (resetTotals) scan.resetTotals()
        if (cf == JxrColor.YUV_420 || cf == JxrColor.YUV_422 || cf == JxrColor.YUV_444) {
            var countM = ctx.cbpCountMax
            var countZ = ctx.cbpCountZero
            val max = fullPlanes * 4 - 5
            if (countZ <= 0 || countM < 0) {
                cbpLP = 0
                if (io.bool() == 1) {
                    cbpLP = 1
                    val k = io.bits(fullPlanes - 1)
                    if (k != 0) cbpLP = k * 2 + io.bits(1)
                }
                if (countM < countZ) cbpLP = max - cbpLP
            } else {
                cbpLP = io.bits(fullPlanes)
            }
            countM += 1 - 4 * (if (cbpLP == max) 1 else 0)
            countZ += 1 - 4 * (if (cbpLP == 0) 1 else 0)
            ctx.cbpCountMax = countM.coerceIn(-8, 7)
            ctx.cbpCountZero = countZ.coerceIn(-8, 7)
        } else {
            for (ch in 0 until channels) cbpLP = cbpLP or (io.bits(1) shl ch)
        }
        val wide = ctx.modelLP.flcBits[0] > 14 || ctx.modelLP.flcBits[1] > 14
        for (ch in 0 until fullPlanes) {
            val coeffs = blockDC[ch]
            if (cbpLP and 1 != 0) {
                val location = 1 + (if (cf == JxrColor.YUV_420 && ch == 1) 9 else 0) + (if (cf == JxrColor.YUV_422 && ch == 1) 1 else 0)
                val nonzero = decodeBlock(ch > 0, runLevels, ctx.expt, JxrContext.CTDC, io, location)
                lm[lmAt] += nonzero
                if ((cf == JxrColor.YUV_420 || cf == JxrColor.YUV_422) && ch != 0) {
                    val temp = IntArray(16)
                    val remapAt = if (cf == JxrColor.YUV_420) 1 else 0
                    val count = if (cf == JxrColor.YUV_420) 6 else 14
                    var index = 0
                    for (k in 0 until nonzero) {
                        index += runLevels[k * 2]
                        temp[index and 0xF] = runLevels[k * 2 + 1]
                        index++
                    }
                    for (k in 0 until count) blockDC[(k and 1) + 1][CHROMA_REMAP[remapAt + (k shr 1)]] = temp[k]
                } else {
                    var index = 1
                    for (k in 0 until nonzero) {
                        index += runLevels[k * 2]
                        if (index > 15) jxrFail("a lowpass run past the block")
                        coeffs[scan.scan[index]] = runLevels[k * 2 + 1]
                        scan.hit(index)
                        index++
                    }
                }
            }
            if (modelBits != 0) {
                if ((cf == JxrColor.YUV_420 || cf == JxrColor.YUV_422) && ch != 0) {
                    for (k in 1 until (if (cf == JxrColor.YUV_420) 4 else 8)) {
                        for (c in 1..2) {
                            val d = blockDC[c]
                            if (d[k] > 0) {
                                d[k] = (d[k] shl modelBits) + flexBits(io, modelBits, wide)
                            } else if (d[k] < 0) {
                                d[k] = (d[k] shl modelBits) - flexBits(io, modelBits, wide)
                            } else {
                                d[k] = flexBits(io, modelBits, wide)
                                if (d[k] != 0 && io.bool() == 1) d[k] = -d[k]
                            }
                        }
                    }
                } else {
                    for (k in 1 until 16) {
                        if (coeffs[k] > 0) {
                            coeffs[k] = (coeffs[k] shl modelBits) + flexBits(io, modelBits, wide)
                        } else if (coeffs[k] < 0) {
                            coeffs[k] = (coeffs[k] shl modelBits) - flexBits(io, modelBits, wide)
                        } else {
                            coeffs[k] = io.signedBits(modelBits)
                        }
                    }
                }
            }
            lmAt = 1
            modelBits = ctx.modelLP.flcBits[1]
            cbpLP = cbpLP shr 1
        }
        JxrContext.updateModel(cf, channels, lm, ctx.modelLP)
        if (resetContext) ctx.adaptLowpass()
    }

    /** The getBits of DecodeMacroblockLowpass, which reads in two halves once a model passes 14 bits. */
    private fun flexBits(io: JxrBits, n: Int, wide: Boolean): Int = if (wide) io.bits32(n) else io.bits(n)

    // --- Highpass ------------------------------------------------------------------------------

    /** DecodeMacroblockHighpass. */
    fun decodeHighpass(ctx: JxrContext) {
        if (resetTotals) {
            ctx.scanHoriz.resetTotals()
            ctx.scanVert.resetTotals()
        }
        val t = tiles[tileColumn]
        if (image.frequencyOrder && t.bitsHP > 0) {
            qIndexHP = decodeQPIndex(ctx.ioAC, t.bitsHP)
            if (qIndexHP >= t.numQPHP) jxrFail("a quantizer index past the tile's quantizers")
        } else if (t.bitsHP == 0 && t.numQPHP > 1) {
            qIndexHP = qIndexLP
        }
        decodeCBP(ctx)
        predictCBP(ctx)
        decodeCoeffs(ctx)
        if (resetContext) ctx.adaptHighpass()
    }

    /** One of the 4-bit patterns that DecodeCBP reads with a 2-bit or 3-bit code. */
    private fun pattern(io: JxrBits, kind: Int): Int = when (kind) {
        2 -> {
            val n = io.bits(2)
            if (n == 0) 3 else if (n == 1) 5 else PATTERN_TAB[n * 2 + io.bool() - 4]
        }
        1 -> 1 shl io.bits(2)
        3 -> 0xF xor (1 shl io.bits(2))
        4 -> 0xF
        else -> 0
    }

    /** DecodeCBP. */
    private fun decodeCBP(ctx: JxrContext) {
        val io = ctx.ioAC
        val iChannel = if (cf == JxrColor.NCOMPONENT || cf == JxrColor.CMYK) channels else 1
        val ahCbp = ctx.cbpY
        val ahCbp1 = ctx.cbpY1
        val ahEx1 = ctx.expt[1]
        for (i in 0 until iChannel) {
            var cbpY = 0
            var cbpU = 0
            var cbpV = 0
            val n = io.huffShort(ahCbp1.table)
            ahCbp1.count(n)
            val numCbp = pattern(io, n)
            for (block in 0 until 4) {
                if (numCbp and (1 shl block) == 0) continue
                val symbol = io.huff(ahCbp.table)
                ahCbp.count(symbol)
                var value = symbol + 1
                var blockCbp = 0
                if (value >= 6) {
                    blockCbp = if (io.bool() == 1) 0x10 else if (io.bool() == 1) 0x20 else 0x30
                    if (value == 9) {
                        if (io.bool() == 1) {
                            // value stays 9
                        } else if (io.bool() == 1) {
                            value = 10
                        } else {
                            value = 11
                        }
                    }
                    value -= 6
                }
                var code1 = FLC_OFF0[value]
                if (FLC0[value] != 0) code1 += io.bits(FLC0[value])
                blockCbp += FLC_OUT0[code1]
                when (cf) {
                    JxrColor.YUV_444 -> {
                        cbpY = cbpY or ((blockCbp and 0xF) shl (block * 4))
                        for (k in 0 until 2) {
                            if ((blockCbp shr (k + 4)) and 1 != 0) {
                                val code = io.huffShort(ahEx1.table)
                                val p = pattern(io, code + 1)
                                if (k == 0) cbpU = cbpU or (p shl (block * 4)) else cbpV = cbpV or (p shl (block * 4))
                            }
                        }
                    }
                    JxrColor.YUV_420 -> {
                        cbpY = cbpY or ((blockCbp and 0xF) shl (block * 4))
                        cbpU = cbpU or (((blockCbp shr 4) and 1) shl block)
                        cbpV = cbpV or (((blockCbp shr 5) and 1) shl block)
                    }
                    JxrColor.YUV_422 -> {
                        cbpY = cbpY or ((blockCbp and 0xF) shl (block * 4))
                        for (k in 0 until 2) {
                            if ((blockCbp shr (k + 4)) and 1 != 0) {
                                var code = 5
                                if (io.bool() == 1) code = 1 else if (io.bool() == 1) code = 4
                                code = code shl SHIFT_422[block]
                                if (k == 0) cbpU = cbpU or code else cbpV = cbpV or code
                            }
                        }
                    }
                    else -> cbpY = cbpY or (blockCbp shl (block * 4))
                }
            }
            diffCbp[i] = cbpY
            if (cf == JxrColor.YUV_420 || cf == JxrColor.YUV_444 || cf == JxrColor.YUV_422) {
                diffCbp[1] = cbpU
                diffCbp[2] = cbpV
            }
        }
    }

    /** predCBPDec. */
    private fun predictCBP(ctx: JxrContext) {
        val x = column
        for (i in 0 until planes) {
            cbp[i] = predictCBP16(diffCbp[i], x, i, ctx)
            pred[i].cbp[x] = cbp[i]
        }
        if (cf == JxrColor.YUV_422) {
            for (c in 1..2) {
                cbp[c] = predictCBPChroma(diffCbp[c], x, c, ctx, 6, 0xFF, 2)
                pred[c].cbp[x] = cbp[c]
            }
        } else if (cf == JxrColor.YUV_420) {
            for (c in 1..2) {
                cbp[c] = predictCBPChroma(diffCbp[c], x, c, ctx, 2, 0xF, 4)
                pred[c].cbp[x] = cbp[c]
            }
        }
    }

    /** predCBPCDec: the coded block pattern of a 16-block plane from its difference. */
    private fun predictCBP16(diff: Int, x: Int, c: Int, ctx: JxrContext): Int {
        var v = diff
        val c1 = if (c != 0) 1 else 0
        if (ctx.cbpState[c1] == 0) {
            v = if (ctxLeft) {
                if (ctxTop) v xor 1 else v xor ((predAbove[c].cbp[x] shr 10) and 1)
            } else {
                v xor ((pred[c].cbp[x - 1] shr 5) and 1)
            }
            v = v xor (0x02 and (v shl 1))
            v = v xor (0x10 and (v shl 3))
            v = v xor (0x20 and (v shl 1))
            v = v xor ((v and 0x33) shl 2)
            v = v xor ((v and 0xCC) shl 6)
            v = v xor ((v and 0x3300) shl 2)
        } else if (ctx.cbpState[c1] == 2) {
            v = v xor 0xFFFF
        }
        updateCbpModel(ctx, c1, ones(v))
        return v
    }

    /** predCBPC420Dec and predCBPC422Dec, whose top neighbour is bit [topBit] of the block above. */
    private fun predictCBPChroma(diff: Int, x: Int, c: Int, ctx: JxrContext, topBit: Int, all: Int, scale: Int): Int {
        var v = diff
        if (ctx.cbpState[1] == 0) {
            v = if (ctxLeft) {
                if (ctxTop) v xor 1 else v xor ((predAbove[c].cbp[x] shr topBit) and 1)
            } else {
                v xor ((pred[c].cbp[x - 1] shr 1) and 1)
            }
            if (all == 0xF) {
                v = v xor (0x02 and (v shl 1))
                v = v xor ((v and 0x3) shl 2)
            } else {
                v = v xor ((v and 0x1) shl 1)
                v = v xor ((v and 0x3) shl 2)
                v = v xor ((v and 0xC) shl 2)
                v = v xor ((v and 0x30) shl 2)
            }
        } else if (ctx.cbpState[1] == 2) {
            v = v xor all
        }
        updateCbpModel(ctx, 1, ones(v) * scale)
        return v
    }

    private fun updateCbpModel(ctx: JxrContext, c1: Int, orig: Int) {
        ctx.cbpCount0[c1] = saturate32(ctx.cbpCount0[c1] + orig - AVG_NDIFF)
        ctx.cbpCount1[c1] = saturate32(ctx.cbpCount1[c1] + 16 - orig - AVG_NDIFF)
        ctx.cbpState[c1] = if (ctx.cbpCount0[c1] < 0) {
            if (ctx.cbpCount0[c1] < ctx.cbpCount1[c1]) 1 else 2
        } else if (ctx.cbpCount1[c1] < 0) {
            2
        } else {
            0
        }
    }

    /** DecodeCoeffs. */
    private fun decodeCoeffs(ctx: JxrContext) {
        val t = tiles[tileColumn]
        val io = ctx.ioAC
        val ioFL = ctx.ioFL
        var modelBits = ctx.modelAC.flcBits[0]
        val lm = IntArray(2)
        var lmAt = 0
        val scan = if (orientation == 1) ctx.scanVert else ctx.scanHoriz
        var blocks = 4
        var chroma = false
        var pattern = cbp[0]
        if (cf == JxrColor.YUV_420) {
            blocks = 6
            pattern += (cbp[1] shl 16) + (cbp[2] shl 20)
        } else if (cf == JxrColor.YUV_422) {
            blocks = 8
            pattern += (cbp[1] shl 16) + (cbp[2] shl 24)
        }
        for (i in 0 until planes) {
            var index = 0
            for (block in 0 until blocks) {
                val qpChannel = if (planes > 1) i else if (block > 3) (if (cf == JxrColor.YUV_420) block - 3 else block / 2 - 1) else 0
                val qp = t.hpQP[qpChannel][qIndexHP]
                for (sub in 0 until 4) {
                    var ch = i
                    var at = p1[i] + BLOCK_OFFSET[index and 0xF]
                    if (block >= 4) {
                        if (cf == JxrColor.YUV_420) {
                            ch = block - 3
                            at = p1[ch] + BLOCK_OFFSET_UV[sub]
                        } else {
                            ch = 1 + (1 and (block shr 1))
                            at = p1[ch] + (block and 1) * 32 + BLOCK_OFFSET_UV_422[sub]
                        }
                    }
                    val nonzero = decodeBlockAdaptive(
                        pattern and 1 != 0, chroma, ctx.expt, io, ioFL, buf[ch], at, scan,
                        modelBits, ctx.trimFlexbits, qp,
                    )
                    if (nonzero > 16) jxrFail("more than 16 coefficients in a block")
                    lm[lmAt] += nonzero
                    index++
                    pattern = pattern ushr 1
                }
                if (block == 3) {
                    modelBits = ctx.modelAC.flcBits[1]
                    lmAt = 1
                    chroma = true
                }
            }
            pattern = cbp[(i + 1) and 0xF]
        }
        JxrContext.updateModel(cf, channels, lm, ctx.modelAC)
    }

    /** DecodeBlockAdaptive: a block's coded coefficients, then its flexbits. */
    private fun decodeBlockAdaptive(
        noSkip: Boolean, chroma: Boolean, ah: Array<JxrVlc>, io: JxrBits, ioFL: JxrBits?,
        coeffs: IntArray, at: Int, scan: JxrContext.Scan, modelBits: Int, trim: Int, qp: Int,
    ): Int {
        var nonzero = 0
        var flex = modelBits - trim
        if (flex < 0 || skipFlexbits) flex = 0
        if (noSkip) nonzero = decodeBlockHighpass(chroma, ah, io, qp shl modelBits, coeffs, at, scan)
        if (flex != 0) {
            val fl = ioFL ?: jxrFail("flexbits without their packet")
            val step = qp shl trim
            for (k in 1 until 16) {
                val p = at + JxrContext.DCT_INDEX[k]
                val v = coeffs[p]
                if (v < 0) {
                    coeffs[p] = v - step * fl.bits(flex)
                } else if (v > 0) {
                    coeffs[p] = v + step * fl.bits(flex)
                } else {
                    coeffs[p] = step * fl.signedBits(flex)
                }
            }
        }
        return nonzero
    }

    /** DecodeBlockHighpass: the run-level coded coefficients of a highpass block, scaled by [qp]. */
    private fun decodeBlockHighpass(chroma: Boolean, ah: Array<JxrVlc>, io: JxrBits, qp: Int, coeffs: IntArray, at: Int, scan: JxrContext.Scan): Int {
        val offset = JxrContext.CTDC + JxrContext.CONTEXTX
        val ah1 = offset + if (chroma) 3 else 0
        var loc = 1
        var nonzero = 1
        val first = ah[ah1]
        var index = io.huff(first.table)
        first.count2(index)
        var sr = index and 1
        var srn = index shr 2
        var cont = sr and srn
        var sign = io.sign()
        var level = (qp xor sign) - sign
        if (index and 2 != 0) level *= absLevel(ah[6 + offset + cont], io)
        if (sr == 0) loc += significantRun(15 - loc, ah[0], io)
        loc = loc and 0xF
        coeffs[at + scan.scan[loc]] = level
        scan.hit(loc)
        loc = (loc + 1) and 0xF
        while (srn != 0) {
            sr = srn and 1
            if (sr == 0) {
                loc += significantRun(15 - loc, ah[0], io)
                if (loc >= 16) return 16
            }
            index = decodeIndex(loc + 1, ah[ah1 + cont + 1], io)
            srn = index shr 1
            cont = cont and srn
            sign = io.sign()
            level = (qp xor sign) - sign
            if (index and 1 != 0) level *= absLevel(ah[6 + offset + cont], io)
            coeffs[at + scan.scan[loc]] = level
            scan.hit(loc)
            loc = (loc + 1) and 0xF
            nonzero++
        }
        return nonzero
    }

    /** DecodeBlock: the run-level pairs of a lowpass block into [out], and how many there are. */
    private fun decodeBlock(chroma: Boolean, out: IntArray, ah: Array<JxrVlc>, offset: Int, io: JxrBits, location0: Int): Int {
        var location = location0
        val ah1 = offset + if (chroma) 3 else 0
        var nonzero = 1
        val first = ah[ah1]
        var index = io.huff(first.table)
        first.count2(index)
        var sr = index and 1
        var srn = index shr 2
        var cont = sr and srn
        var sign = io.sign()
        out[1] = if (index and 2 != 0) (absLevel(ah[6 + offset + cont], io) xor sign) - sign else 1 or sign
        out[0] = 0
        if (sr == 0) out[0] = significantRun(15 - location, ah[0], io)
        location += out[0] + 1
        while (srn != 0) {
            if (nonzero >= 16) jxrFail("more than 16 coefficients in a block")
            sr = srn and 1
            out[nonzero * 2] = 0
            if (sr == 0) out[nonzero * 2] = significantRun(15 - location, ah[0], io)
            location += out[nonzero * 2] + 1
            index = decodeIndex(location, ah[ah1 + cont + 1], io)
            srn = index shr 1
            cont = cont and srn
            sign = io.sign()
            out[nonzero * 2 + 1] = if (index and 1 != 0) (absLevel(ah[6 + offset + cont], io) xor sign) - sign else 1 or sign
            nonzero++
        }
        return nonzero
    }

    /** DecodeIndex: the symbol after the first, whose alphabet narrows near the end of the block. */
    private fun decodeIndex(location: Int, ah: JxrVlc, io: JxrBits): Int = when {
        location < 15 -> {
            val index = io.huffShort(ah.table)
            ah.count2(index)
            index
        }
        location == 15 -> if (io.bool() == 0) 0 else if (io.bool() == 0) 2 else 1 + 2 * io.bool()
        else -> io.bits(1)
    }

    /** DecodeSignificantRun. */
    private fun significantRun(maxRun: Int, ah: JxrVlc, io: JxrBits): Int {
        if (maxRun < 5) {
            if (maxRun == 1) return 1
            if (io.bool() == 1) return 1
            if (maxRun == 2 || io.bool() == 1) return 2
            if (maxRun == 3 || io.bool() == 1) return 3
            return 4
        }
        if (maxRun > 14) jxrFail("a run of $maxRun")
        val index = io.huffShort(ah.table) + SIGNIFICANT_RUN_BIN[maxRun] * 5
        var run = RUN_REMAP[index]
        val flc = SIGNIFICANT_RUN_FIXED_LENGTH[index]
        if (flc != 0) run += io.bits(flc)
        return run
    }

    /** DecodeSignificantAbsLevel. */
    private fun absLevel(ah: JxrVlc, io: JxrBits): Int {
        val index = io.huff(ah.table)
        ah.count(index)
        return when {
            index < 2 -> index + 2
            index < 6 -> LEVEL_REMAP[index] + io.bits(LEVEL_FIXED_LENGTH[index])
            else -> {
                var fixed = io.bits(4) + 4
                if (fixed == 19) {
                    fixed += io.bits(2)
                    if (fixed == 22) fixed += io.bits(3)
                }
                2 + (1 shl fixed) + io.bits32(fixed)
            }
        }
    }

    private fun decodeQPIndex(io: JxrBits, bits: Int): Int = if (io.bits(1) == 0) 0 else io.bits(bits) + 1

    // --- Prediction ----------------------------------------------------------------------------

    /** getDCACPredMode: DC from the left (0), the top (1), both (2) or neither (3), and the lowpass AC from the left (0), the top (4) or neither (8). */
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
            if (cf == JxrColor.Y_ONLY || cf == JxrColor.NCOMPONENT) {
                strH = abs(tl - l)
                strV = abs(tl - tp)
            } else {
                val scale = if (cf == JxrColor.YUV_420) 8 else if (cf == JxrColor.YUV_422) 4 else 2
                strH = abs(tl - l) * scale + abs(predAbove[1].dc[x - 1] - pred[1].dc[x - 1]) +
                    abs(predAbove[2].dc[x - 1] - pred[2].dc[x - 1])
                strV = abs(tl - tp) * scale + abs(predAbove[1].dc[x - 1] - predAbove[1].dc[x]) +
                    abs(predAbove[2].dc[x - 1] - predAbove[2].dc[x])
            }
            if (strH * ORIENT_WEIGHT < strV) 1 else if (strV * ORIENT_WEIGHT < strH) 0 else 2
        }
        var adMode = 2
        if (dcMode == 1 && qIndexLP == predAbove[0].qp[x]) adMode = 1
        if (dcMode == 0 && qIndexLP == pred[0].qp[x - 1]) adMode = 0
        return dcMode + (adMode shl 2)
    }

    /** getACPredMode: the direction the highpass AC is predicted from, 0 left, 1 top, 2 neither. */
    private fun acMode(): Int {
        val y = blockDC[0]
        var strH = abs(y[1]) + abs(y[2]) + abs(y[3])
        var strV = abs(y[4]) + abs(y[8]) + abs(y[12])
        if (cf != JxrColor.Y_ONLY && cf != JxrColor.NCOMPONENT) {
            val u = blockDC[1]
            val v = blockDC[2]
            strH += abs(u[1]) + abs(v[1])
            if (cf == JxrColor.YUV_420) {
                strV += abs(u[2]) + abs(v[2])
            } else if (cf == JxrColor.YUV_422) {
                strV += abs(u[2]) + abs(v[2]) + abs(u[6]) + abs(v[6])
                strH += abs(u[5]) + abs(v[5])
            } else {
                strV += abs(u[4]) + abs(v[4])
            }
        }
        return if (strH * ORIENT_WEIGHT < strV) 1 else if (strV * ORIENT_WEIGHT < strH) 0 else 2
    }

    /** predDCACDec. */
    fun predictDCAC() {
        val x = column
        val mode = dcacMode(x)
        val dcMode = mode and 3
        val adMode = mode and 0xC
        for (ii in 0 until planes) {
            val org = blockDC[ii]
            when (dcMode) {
                1 -> org[0] += predAbove[ii].dc[x]
                0 -> org[0] += pred[ii].dc[x - 1]
                2 -> org[0] += (pred[ii].dc[x - 1] + predAbove[ii].dc[x]) shr 1
            }
            if (adMode == 4) {
                val r = predAbove[ii].ad
                org[4] += r[6 * x + 3]
                org[8] += r[6 * x + 4]
                org[12] += r[6 * x + 5]
            } else if (adMode == 0) {
                val r = pred[ii].ad
                org[1] += r[6 * (x - 1)]
                org[2] += r[6 * (x - 1) + 1]
                org[3] += r[6 * (x - 1) + 2]
            }
        }
        if (cf == JxrColor.YUV_420) {
            for (ii in 1..2) {
                val org = blockDC[ii]
                when (dcMode) {
                    1 -> org[0] += predAbove[ii].dc[x]
                    0 -> org[0] += pred[ii].dc[x - 1]
                    2 -> org[0] += (pred[ii].dc[x - 1] + predAbove[ii].dc[x] + 1) shr 1
                }
                if (adMode == 4) {
                    org[2] += predAbove[ii].ad[6 * x + 1]
                } else if (adMode == 0) {
                    org[1] += pred[ii].ad[6 * (x - 1)]
                }
            }
        } else if (cf == JxrColor.YUV_422) {
            for (ii in 1..2) {
                val org = blockDC[ii]
                when (dcMode) {
                    1 -> org[0] += predAbove[ii].dc[x]
                    0 -> org[0] += pred[ii].dc[x - 1]
                    2 -> org[0] += (pred[ii].dc[x - 1] + predAbove[ii].dc[x] + 1) shr 1
                }
                if (adMode == 4) {
                    org[4] += predAbove[ii].ad[6 * x + 4]
                    org[2] += predAbove[ii].ad[6 * x + 3]
                    org[6] += org[2]
                } else if (adMode == 0) {
                    org[4] += pred[ii].ad[6 * (x - 1) + 4]
                    org[1] += pred[ii].ad[6 * (x - 1)]
                    org[5] += pred[ii].ad[6 * (x - 1) + 2]
                } else if (dcMode == 1) {
                    org[6] += org[2]
                }
            }
        }
        orientation = 2 - acMode()
    }

    /** dequantizeMacroblock: the DC and lowpass coefficients, scaled into the macroblock's samples. */
    fun dequantize() {
        val t = tiles[tileColumn]
        for (i in 0 until channels) {
            val b = buf[i]
            val at = p1[i]
            val org = blockDC[i]
            b[at] = org[0] * t.dcQP[i][0]
            if (subband == JxrBands.DC_ONLY) continue
            val qp = t.lpQP[i][qIndexLP]
            if (i == 0 || (cf != JxrColor.YUV_422 && cf != JxrColor.YUV_420)) {
                for (k in 1 until 16) b[at + DC_INDEX[k]] = org[k] * qp
            } else if (cf == JxrColor.YUV_422) {
                for (k in 1 until 8) b[at + LP_422[k]] = org[k] * qp
            } else {
                b[at + 32] = org[1] * qp
                b[at + 16] = org[2] * qp
                b[at + 48] = org[3] * qp
            }
        }
    }

    /** predACDec: the highpass AC of the first row or column of each block, from the block beside it. */
    fun predictAC() {
        val mode = 2 - orientation
        for (i in 0 until planes) {
            val b = buf[i]
            val src = p1[i]
            when (mode) {
                1 -> for (j in AC_TOP_BLOCKS) {
                    val o = src + 16 * j
                    val r = o - 16
                    b[o + 2] += b[r + 2]
                    b[o + 10] += b[r + 10]
                    b[o + 9] += b[r + 9]
                }
                0 -> for (j in 64 until 256 step 16) {
                    val o = src + j
                    val r = o - 64
                    b[o + 1] += b[r + 1]
                    b[o + 5] += b[r + 5]
                    b[o + 6] += b[r + 6]
                }
            }
        }
        if (cf == JxrColor.YUV_420) {
            for (c in 1..2) {
                val b = buf[c]
                val src = p1[c]
                when (mode) {
                    1 -> for (j in intArrayOf(1, 3)) {
                        val o = src + 16 * j
                        val r = o - 16
                        b[o + 2] += b[r + 2]
                        b[o + 10] += b[r + 10]
                        b[o + 9] += b[r + 9]
                    }
                    0 -> for (j in 2..3) {
                        val o = src + 16 * j
                        val r = o - 32
                        b[o + 1] += b[r + 1]
                        b[o + 5] += b[r + 5]
                        b[o + 6] += b[r + 6]
                    }
                }
            }
        } else if (cf == JxrColor.YUV_422) {
            for (c in 1..2) {
                val b = buf[c]
                val src = p1[c]
                when (mode) {
                    1 -> for (j in 2 until 8) {
                        val o = src + BLOCK_OFFSET_UV_422[j]
                        val r = o - 16
                        b[o + 10] += b[r + 10]
                        b[o + 2] += b[r + 2]
                        b[o + 9] += b[r + 9]
                    }
                    0 -> for (j in 1 until 8 step 2) {
                        val o = src + BLOCK_OFFSET_UV_422[j]
                        val r = o - 64
                        b[o + 1] += b[r + 1]
                        b[o + 5] += b[r + 5]
                        b[o + 6] += b[r + 6]
                    }
                }
            }
        }
    }

    /** updatePredInfo: what the macroblocks to the right and below predict from. */
    fun updatePrediction() {
        val x = column
        for (i in 0 until planes) {
            val p = blockDC[i]
            val r = pred[i]
            r.dc[x] = p[0]
            r.qp[x] = qIndexLP
            r.ad[6 * x] = p[1]
            r.ad[6 * x + 1] = p[2]
            r.ad[6 * x + 2] = p[3]
            r.ad[6 * x + 3] = p[4]
            r.ad[6 * x + 4] = p[8]
            r.ad[6 * x + 5] = p[12]
        }
        if (cf == JxrColor.YUV_420) {
            for (i in 1..2) {
                val p = blockDC[i]
                val r = pred[i]
                r.dc[x] = p[0]
                r.qp[x] = qIndexLP
                r.ad[6 * x] = p[1]
                r.ad[6 * x + 1] = p[2]
            }
        } else if (cf == JxrColor.YUV_422) {
            for (i in 1..2) {
                val p = blockDC[i]
                val r = pred[i]
                r.qp[x] = qIndexLP
                r.dc[x] = p[0]
                r.ad[6 * x] = p[1]
                r.ad[6 * x + 1] = p[2]
                r.ad[6 * x + 2] = p[5]
                r.ad[6 * x + 3] = p[6]
                r.ad[6 * x + 4] = p[4]
            }
        }
    }

    companion object {
        const val SHIFT_ZERO = 1
        const val QP_FRAC_BITS = 2
        private const val ORIENT_WEIGHT = 4
        private const val AVG_NDIFF = 3

        /** cblkChromas: blocks of 16 samples in a chroma macroblock of each colour format. */
        private val CHROMA_BLOCKS = intArrayOf(0, 4, 8, 16, 16, 16, 16, 0, 0)

        /** blkOffset, blkOffsetUV and blkOffsetUV_422 of strcodec.c: where each block starts in a macroblock. */
        val BLOCK_OFFSET = intArrayOf(0, 64, 16, 80, 128, 192, 144, 208, 32, 96, 48, 112, 160, 224, 176, 240)
        val BLOCK_OFFSET_UV = intArrayOf(0, 32, 16, 48)
        val BLOCK_OFFSET_UV_422 = intArrayOf(0, 64, 16, 80, 32, 96, 48, 112)

        /** dctIndex[2]: where each lowpass coefficient goes among the DCs of the 16 blocks. */
        private val DC_INDEX = intArrayOf(0, 128, 64, 208, 32, 240, 48, 224, 16, 192, 80, 144, 112, 176, 96, 160)

        /** dequantizeBlock4x2: where the lowpass coefficients of a 4:2:2 chroma macroblock go. */
        private val LP_422 = intArrayOf(0, 64, 16, 80, 32, 96, 48, 112)

        /** The chroma lowpass coefficients of 4:2:2 (from 0) and 4:2:0 (from 1) in DecodeMacroblockLowpass's order. */
        private val CHROMA_REMAP = intArrayOf(4, 1, 2, 3, 5, 6, 7)

        /** The blocks that predictAC predicts from the block above. */
        private val AC_TOP_BLOCKS = intArrayOf(1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 14, 15)

        private val PATTERN_TAB = intArrayOf(6, 9, 10, 12)
        private val FLC0 = intArrayOf(0, 2, 1, 2, 2, 0)
        private val FLC_OFF0 = intArrayOf(0, 4, 2, 8, 12, 1)
        private val FLC_OUT0 = intArrayOf(0, 15, 3, 12, 1, 2, 4, 8, 5, 6, 9, 10, 7, 11, 13, 14)
        private val SHIFT_422 = intArrayOf(0, 1, 4, 5)

        /** gSignificantRunBin, gSignificantRunFixedLength and DecodeSignificantRun's aRemap. */
        private val SIGNIFICANT_RUN_BIN = intArrayOf(-1, -1, -1, -1, 2, 2, 2, 1, 1, 1, 1, 0, 0, 0, 0)
        private val SIGNIFICANT_RUN_FIXED_LENGTH = intArrayOf(0, 0, 1, 1, 3, 0, 0, 1, 1, 2, 0, 0, 0, 0, 1)
        private val RUN_REMAP = intArrayOf(1, 2, 3, 5, 7, 1, 2, 3, 5, 7, 1, 2, 3, 4, 5)

        private val LEVEL_REMAP = intArrayOf(2, 3, 4, 6, 10, 14)
        private val LEVEL_FIXED_LENGTH = intArrayOf(0, 0, 1, 2, 2, 2)

        /** remapQP: the quantization step of index [index] (T.832 8.6.1). */
        fun remapQP(index: Int, shift: Int, scaled: Boolean): Int {
            if (index == 0) return 1
            if (!scaled) {
                val (man, exp) = when {
                    index < 32 -> ((index + 3) shr 2) to 0
                    index < 48 -> ((16 + (index and 0xF) + 1) shr 1) to ((index shr 4) - 2)
                    else -> (16 + (index and 0xF)) to ((index shr 4) - 3)
                }
                return man shl exp
            }
            return if (index < 16) index shl shift else (16 + (index and 0xF)) shl ((index shr 4) - 1 + shift)
        }

        /** dquantBits: the bits of a macroblock's quantizer index among [count]. */
        fun dquantBits(count: Int): Int = if (count < 2) 0 else if (count < 4) 1 else if (count < 6) 2 else if (count < 10) 3 else 4

        private fun ones(v: Int): Int = (v and 0xFFFF).countOneBits()

        /** SATURATE32 of strPredQuantDec.c. */
        private fun saturate32(x: Int): Int = if (x + 16 < 0 || x + 16 >= 32) (if (x < 0) -16 else 15) else x
    }
}
