// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_group.cc, dec_frame.cc, dec_modular.cc,
// compressed_dc.cc, quantizer.h, entropy_coder.cc, chroma_from_luma.cc, coeff_order.cc, ac_context.h
// and epf.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import kotlin.math.abs
import kotlin.math.pow

/**
 * The lossy mode of a frame, which libjxl calls VarDCT. The frame is cut into blocks of 8 by 8
 * samples. The LF image holds one value for each block, its mean. A transform that covers one
 * block or several holds the rest as HF coefficients, which are quantized.
 *
 * Channel 0 is X (or Cb), channel 1 is Y and channel 2 is B (or Cr). Call [readGlobal], then
 * [readLfGroup] for every LF group, [finishLf], [readHfGlobal] and [readGroup] for every
 * group; [planes] then gives the three channels.
 */
internal class JxlVarDct(
    private val fh: JxlFrameHeader,
    private val image: JxlImageHeader,
    private val modular: JxlModularFrame,
    /** The factors that turn the quantized LF of each channel into samples. */
    private val lfQuant: FloatArray,
    store: JxlFrameStore,
) {
    private val dim = fh.dim
    private val xb = dim.xsizeBlocks
    private val yb = dim.ysizeBlocks
    private val hs = IntArray(3) { fh.hShift(it) }
    private val vs = IntArray(3) { fh.vShift(it) }

    // The quantizer: the scale of the whole frame, and the step of each channel's LF.
    private var invGlobalScale = 0.0
    private var quantScale = 0.0
    private val lfStep = DoubleArray(3)

    // The block context map: which set of distributions a block's coefficients use.
    private val lfThresholds = Array(3) { IntArray(0) }
    private var qfThresholds = IntArray(0)
    private var blockCtxMap = DEFAULT_BLOCK_CTX_MAP
    private var numBlockCtxs = DEFAULT_BLOCK_CTX_MAP.max() + 1
    private var numLfCtxs = 1

    // Chroma from luma: X and B are coded as their difference from a multiple of Y.
    private var colorScale = f(1.0 / DEFAULT_COLOR_FACTOR)
    private var baseX = 0.0
    private var baseB = 1.0
    private var lfFactorX = 0.0
    private var lfFactorB = 1.0

    /** For each block, its transform times two, plus one for the top-left block of the transform. -1 until it is read. */
    private val strategy = ByteArray(xb * yb) { -1 }
    private val rawQuant = IntArray(xb * yb)
    private val sharpness = ByteArray(xb * yb)
    private val lfContext = ByteArray(xb * yb)

    // The chroma from luma factors, one for each tile of 64 by 64 samples.
    private val tilesX = (xb + 7) shr 3
    private val tilesY = (yb + 7) shr 3
    private val yToXMap = ByteArray(tilesX * tilesY)
    private val yToBMap = ByteArray(tilesX * tilesY)

    private val lf: Array<FloatArray>
    private val lfStride: Int
    private var usedStrategies = 0

    /** libjxl's 1/sigma of each block, for the edge-preserving filter; null when the frame has none. */
    val sigma: JxlPlane? = if (fh.loopFilter.epfIters > 0) JxlPlane(xb, yb) else null

    // What the HF global section gives.
    private var matrices: JxlDequantMatrices? = null
    private var numHistograms = 0
    private var numAcContexts = 0
    private val orders = arrayOfNulls<Array<IntArray?>>(fh.numPasses)
    private val codes = arrayOfNulls<JxlCode>(fh.numPasses)
    private val contextMaps = arrayOfNulls<IntArray>(fh.numPasses)

    // Working space of a group.
    private val nonZeros = Array(fh.numPasses * 3) { IntArray(GROUP_BLOCKS * GROUP_BLOCKS) }
    private val ctxOffset = IntArray(fh.numPasses)
    private val symbols = arrayOfNulls<JxlSymbolReader>(fh.numPasses)
    private var coefficients = IntArray(0)
    private var block = FloatArray(0)
    private var scratch = DoubleArray(0)

    private val xDmMultiplier = f((1.0 / 1.25).pow(fh.xQmScale - 2.0))
    private val bDmMultiplier = f((1.0 / 1.25).pow(fh.bQmScale - 2.0))
    private val quantBias = DoubleArray(4) { f(image.quantBiases[it]) }

    // The decoded channels, each as wide and as high as its blocks.
    private val out = arrayOfNulls<FloatArray>(3)
    private val outStride = IntArray(3) { (xb shr hs[it]) * 8 }

    init {
        if (fh.usesLfFrame) {
            if (fh.dcLevel >= store.lfFrames.size) jxlFail("LF frame of a level that does not exist")
            val planes = store.lfFrames[fh.dcLevel] ?: jxlFail("frame uses an LF frame that the file does not have")
            if (planes[0].w < xb || planes[0].h < yb) jxlFail("LF frame is smaller than the frame that uses it")
            lf = Array(3) { planes[it].data }
            lfStride = planes[0].w
        } else {
            lf = Array(3) { FloatArray(xb * yb) }
            lfStride = xb
        }
    }

    /** The factor of Y that the X of a block with map value [factor] adds. */
    private fun yToX(factor: Int): Double = f(baseX + f(factor * colorScale))

    private fun yToB(factor: Int): Double = f(baseB + f(factor * colorScale))

    /** The chroma from luma factors of a block that has no factor of its own; splines and noise use them. */
    val baseYToX: Double get() = yToX(0)
    val baseYToB: Double get() = yToB(0)

    /** Reads the quantizer, the block context map and the chroma from luma parameters from the frame's first section. */
    fun readGlobal(br: JxlBitReader) {
        val globalScale = br.u32(fo(11, 1), fo(11, 2049), fo(12, 4097), fo(16, 8193))
        val quantLf = br.u32(fv(16), fo(5, 1), fo(8, 1), fo(16, 1))
        quantScale = f(globalScale * (1.0 / GLOBAL_SCALE_DENOM))
        invGlobalScale = f(GLOBAL_SCALE_DENOM.toDouble() / globalScale)
        val invQuantLf = f(invGlobalScale / quantLf)
        for (c in 0 until 3) lfStep[c] = f(invQuantLf * lfQuant[c])
        readBlockContexts(br)
        readColorCorrelation(br)
    }

    private fun readBlockContexts(br: JxlBitReader) {
        if (br.bool()) return
        numLfCtxs = 1
        for (c in 0 until 3) {
            val t = IntArray(br.bits(4)) { unpackSigned(br.u32(fb(4), fo(8, 16), fo(16, 272), fo(32, 65808))) }
            lfThresholds[c] = t
            numLfCtxs *= t.size + 1
        }
        qfThresholds = IntArray(br.bits(4)) { br.u32(fb(2), fo(3, 4), fo(5, 12), fo(8, 44)) + 1 }
        if (numLfCtxs * (qfThresholds.size + 1) > 64) jxlFail("block context map is too big")
        val map = IntArray(3 * NUM_ORDERS * numLfCtxs * (qfThresholds.size + 1))
        numBlockCtxs = JxlCode.readContextMap(br, map)
        if (numBlockCtxs > 16) jxlFail("block context map has too many contexts")
        blockCtxMap = map
    }

    private fun readColorCorrelation(br: JxlBitReader) {
        if (!br.bool()) {
            colorScale = f(1.0 / br.u32(fv(DEFAULT_COLOR_FACTOR), fv(256), fo(8, 2), fo(16, 258)))
            baseX = br.f16()
            if (abs(baseX) > 4.0) jxlFail("base X correlation is out of range")
            baseB = br.f16()
            if (abs(baseB) > 4.0) jxlFail("base B correlation is out of range")
            lfFactorX = yToX(br.bits(8) - 128)
            lfFactorB = yToB(br.bits(8) - 128)
        } else {
            lfFactorX = yToX(0)
            lfFactorB = yToB(0)
        }
    }

    /** The LF image of one LF group: a Modular image of the three channels, Y first. */
    fun readLfCoefficients(br: JxlBitReader, group: Int) {
        val x0 = (group % dim.xsizeDcGroups) * dim.groupDim
        val y0 = (group / dim.xsizeDcGroups) * dim.groupDim
        readLfCoefficients(br, group, x0, y0, minOf(dim.groupDim, xb - x0), minOf(dim.groupDim, yb - y0))
    }

    private fun readLfCoefficients(br: JxlBitReader, group: Int, x0: Int, y0: Int, w: Int, h: Int) {
        val mul = 1.0 / (1 shl br.bits(2))
        val part = JxlModularImage(image.bitDepth.bits)
        // The stream holds Y, then X, then B.
        for (i in 0 until 3) {
            val c = STREAM_CHANNEL[i]
            part.channels.add(JxlChannel(w shr hs[c], h shr vs[c]))
        }
        modular.decodeStream(br, part, modular.lfCoefficientsStream(group))
        val qy = part.channels[0].data
        val qx = part.channels[1].data
        val qb = part.channels[2].data
        if (fh.is444) {
            val fx = f(lfStep[0] * mul)
            val fy = f(lfStep[1] * mul)
            val fb = f(lfStep[2] * mul)
            for (y in 0 until h) {
                var at = (y0 + y) * xb + x0
                var from = y * w
                for (x in 0 until w) {
                    val vy = f(qy[from] * fy)
                    lf[1][at] = vy.toFloat()
                    lf[0][at] = (vy * lfFactorX + f(qx[from] * fx)).toFloat()
                    lf[2][at] = (vy * lfFactorB + f(qb[from] * fb)).toFloat()
                    at++
                    from++
                }
            }
        } else {
            for (i in 0 until 3) {
                val c = STREAM_CHANNEL[i]
                val q = part.channels[i].data
                val fac = f(lfStep[c] * mul)
                val cw = w shr hs[c]
                for (y in 0 until (h shr vs[c])) {
                    val at = ((y0 shr vs[c]) + y) * xb + (x0 shr hs[c])
                    for (x in 0 until cw) lf[c][at + x] = (q[y * cw + x] * fac).toFloat()
                }
            }
        }
        if (numLfCtxs <= 1) return
        // The context of each block from its quantized LF, for the coefficients read later.
        val widths = IntArray(3) { part.channels[it].w }
        for (y in 0 until h) {
            val rowX = (y shr vs[0]) * widths[1]
            val rowY = (y shr vs[1]) * widths[0]
            val rowB = (y shr vs[2]) * widths[2]
            for (x in 0 until w) {
                val vx = qx[rowX + (x shr hs[0])]
                val vy = qy[rowY + (x shr hs[1])]
                val vb = qb[rowB + (x shr hs[2])]
                var bx = 0
                var by = 0
                var bb = 0
                for (t in lfThresholds[0]) if (vx > t) bx++
                for (t in lfThresholds[1]) if (vy > t) by++
                for (t in lfThresholds[2]) if (vb > t) bb++
                val bucket = (bx * (lfThresholds[2].size + 1) + bb) * (lfThresholds[1].size + 1) + by
                lfContext[(y0 + y) * xb + x0 + x] = bucket.toByte()
            }
        }
    }

    /**
     * The HF metadata of one LF group: the chroma from luma factors of its tiles, and the
     * transform, the quantizer and the filter sharpness of each block.
     */
    fun readHfMetadata(br: JxlBitReader, group: Int) {
        val x0 = (group % dim.xsizeDcGroups) * dim.groupDim
        val y0 = (group / dim.xsizeDcGroups) * dim.groupDim
        val w = minOf(dim.groupDim, xb - x0)
        val h = minOf(dim.groupDim, yb - y0)
        val count = br.bits(ceilLog2(w * h)) + 1
        val tw = (w + 7) shr 3
        val th = (h + 7) shr 3
        val part = JxlModularImage(image.bitDepth.bits)
        part.channels.add(JxlChannel(tw, th, 3, 3))
        part.channels.add(JxlChannel(tw, th, 3, 3))
        // One column for each transform, in the order their first blocks come: the kind, then the quantizer.
        part.channels.add(JxlChannel(count, 2))
        part.channels.add(JxlChannel(w, h))
        modular.decodeStream(br, part, modular.hfMetadataStream(group))

        val fromX = part.channels[0].data
        val fromB = part.channels[1].data
        for (y in 0 until th) {
            val at = ((y0 shr 3) + y) * tilesX + (x0 shr 3)
            for (x in 0 until tw) {
                yToXMap[at + x] = fromX[y * tw + x].coerceIn(-128, 127).toByte()
                yToBMap[at + x] = fromB[y * tw + x].coerceIn(-128, 127).toByte()
            }
        }

        val kinds = part.channels[2].data
        val sharp = part.channels[3].data
        var num = 0
        for (iy in 0 until h) {
            val y = y0 + iy
            for (ix in 0 until w) {
                val x = x0 + ix
                val s = sharp[iy * w + ix]
                if (s < 0 || s >= JxlLoopFilter.EPF_SHARP_ENTRIES) jxlFail("filter sharpness $s")
                sharpness[y * xb + x] = s.toByte()
                if (strategy[y * xb + x] >= 0) continue
                if (num >= count) jxlFail("HF metadata names fewer transforms than the blocks need")
                val kind = kinds[num]
                if (kind < 0 || kind >= JxlAcStrategy.COUNT) jxlFail("transform kind $kind")
                usedStrategies = usedStrategies or (1 shl kind)
                val cx = JxlAcStrategy.coveredBlocksX(kind)
                val cy = JxlAcStrategy.coveredBlocksY(kind)
                if ((cx > 1 || cy > 1) && !fh.is444) jxlFail("a transform of several blocks in a frame with subsampled chroma")
                // A transform stays inside its group of 32 by 32 blocks, and inside the LF group.
                if (x + cx > (x / GROUP_BLOCKS + 1) * GROUP_BLOCKS || x + cx > x0 + w) jxlFail("transform passes the right edge of its group")
                if (y + cy > (y / GROUP_BLOCKS + 1) * GROUP_BLOCKS || y + cy > y0 + h) jxlFail("transform passes the bottom edge of its group")
                for (by in 0 until cy) {
                    for (bx in 0 until cx) {
                        val at = (y + by) * xb + x + bx
                        if (strategy[at] >= 0) jxlFail("transforms overlap")
                        strategy[at] = ((kind shl 1) or (if (bx or by == 0) 1 else 0)).toByte()
                    }
                }
                rawQuant[y * xb + x] = 1 + kinds[count + num].coerceIn(0, QUANT_MAX - 1)
                num++
            }
        }
        if (sigma != null) computeSigma(x0, y0, w, h, sigma)
    }

    /** How strong the edge-preserving filter is on each block: stronger where the quantizer is coarser. */
    private fun computeSigma(x0: Int, y0: Int, w: Int, h: Int, sigma: JxlPlane) {
        val lfi = fh.loopFilter
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val s = strategy[y * xb + x].toInt()
                if (s and 1 == 0) continue
                val kind = s shr 1
                val sigmaQuant = f(lfi.epfQuantMul / f(f(quantScale * rawQuant[y * xb + x]) * INV_SIGMA_NUM))
                for (by in 0 until JxlAcStrategy.coveredBlocksY(kind)) {
                    for (bx in 0 until JxlAcStrategy.coveredBlocksX(kind)) {
                        val at = (y + by) * xb + x + bx
                        val v = minOf(-1e-4, f(sigmaQuant * f(lfi.epfSharpLut[sharpness[at].toInt()])))
                        sigma.data[at] = (1.0 / f(v)).toFloat()
                    }
                }
            }
        }
    }

    /** Smooths the LF image where its steps are small, once every LF group is read (AdaptiveDCSmoothing). */
    fun finishLf() {
        if (fh.usesLfFrame || fh.skipAdaptiveLfSmoothing) return
        if (xb <= 2 || yb <= 2) return
        val smoothed = Array(3) { lf[it].copyOf() }
        for (y in 1 until yb - 1) {
            for (x in 1 until xb - 1) {
                val at = y * xb + x
                var gap = 0.5
                for (c in 0 until 3) {
                    val p = lf[c]
                    val mc = p[at].toDouble()
                    val corner = p[at - xb - 1].toDouble() + p[at - xb + 1] + p[at + xb - 1] + p[at + xb + 1]
                    val side = p[at - 1].toDouble() + p[at + 1] + p[at - xb] + p[at + xb]
                    val sm = f(corner * SMOOTH_W2 + f(side * SMOOTH_W1 + f(mc * SMOOTH_W0)))
                    smoothed[c][at] = sm.toFloat()
                    gap = maxOf(gap, abs(f((mc - sm) / lfStep[c])))
                }
                val factor = maxOf(0.0, f(3.0 - 4.0 * gap))
                for (c in 0 until 3) {
                    val mc = lf[c][at].toDouble()
                    smoothed[c][at] = ((smoothed[c][at] - mc) * factor + mc).toFloat()
                }
            }
        }
        for (c in 0 until 3) smoothed[c].copyInto(lf[c])
    }

    /** Reads the dequantization matrices, and for each pass the order of the coefficients and their entropy code. */
    fun readHfGlobal(br: JxlBitReader) {
        val m = JxlDequantMatrices.read(br, modular)
        m.ensureComputed(usedStrategies)
        matrices = m
        numHistograms = 1 + br.bits(ceilLog2(dim.numGroups))
        numAcContexts = numBlockCtxs * (NON_ZERO_BUCKETS + ZERO_DENSITY_CONTEXTS)
        for (pass in 0 until fh.numPasses) {
            orders[pass] = readCoefficientOrders(br)
            val contexts = numHistograms.toLong() * numAcContexts
            // The count is a header field, so a few bytes can ask for any number of them.
            if (contexts * fh.numPasses > MAX_CONTEXTS) jxlFail("$contexts coefficient contexts in each of ${fh.numPasses} passes")
            val code = JxlCode.read(br, contexts.toInt())
            codes[pass] = code
            // A broken stream can ask for a context past the last one; the spare entries keep that inside the map.
            contextMaps[pass] = code.contextMap.copyOf(contexts.toInt() + ZERO_DENSITY_SPARE)
        }
        var maxArea = 0
        for (kind in 0 until JxlAcStrategy.COUNT) {
            if (usedStrategies and (1 shl kind) == 0) continue
            maxArea = maxOf(maxArea, JxlAcStrategy.coveredBlocksX(kind) * JxlAcStrategy.coveredBlocksY(kind) * 64)
        }
        coefficients = IntArray(3 * maxArea)
        block = FloatArray(3 * maxArea)
        scratch = DoubleArray(JxlDct.scratchSize(usedStrategies))
        for (c in 0 until 3) out[c] = FloatArray(outStride[c] * (yb shr vs[c]) * 8)
    }

    /**
     * The order in which a pass stores the coefficients of each kind of transform, for each
     * channel. A pass uses the natural order, low frequencies first, or a permutation of it.
     */
    private fun readCoefficientOrders(br: JxlBitReader): Array<IntArray?> {
        val usedOrders = br.u32(fv(0x5F), fv(0x13), fv(0), fb(NUM_ORDERS))
        val result = arrayOfNulls<IntArray>(NUM_ORDERS * 3)
        var reader: JxlSymbolReader? = null
        if (usedOrders != 0) reader = JxlSymbolReader(JxlCode.read(br, JXL_PERMUTATION_CONTEXTS), br)
        var needed = 0
        for (kind in 0 until JxlAcStrategy.COUNT) {
            if (usedStrategies and (1 shl kind) != 0) needed = needed or (1 shl JxlAcStrategy.STRATEGY_ORDER[kind])
        }
        var done = 0
        for (kind in 0 until JxlAcStrategy.COUNT) {
            val ord = JxlAcStrategy.STRATEGY_ORDER[kind]
            if (done and (1 shl ord) != 0) continue
            done = done or (1 shl ord)
            val used = needed and (1 shl ord) != 0
            val llf = JxlAcStrategy.coveredBlocksX(kind) * JxlAcStrategy.coveredBlocksY(kind)
            val size = llf * 64
            if (usedOrders and (1 shl ord) == 0) {
                if (used) {
                    val natural = JxlAcStrategy.naturalCoeffOrder(kind)
                    for (c in 0 until 3) result[ord * 3 + c] = natural
                }
                continue
            }
            for (c in 0 until 3) {
                // An order no transform uses is still in the stream, and is read past.
                val order = if (used) IntArray(size) else null
                jxlReadPermutation(reader!!, llf, size, order)
                if (order != null) {
                    val natural = JxlAcStrategy.naturalCoeffOrder(kind)
                    for (k in 0 until size) order[k] = natural[order[k]]
                    result[ord * 3 + c] = order
                }
            }
        }
        reader?.checkFinalState()
        return result
    }

    /**
     * Reads the coefficients of group [group] and transforms its blocks to samples.
     * [readers] holds the group's section of each pass; every pass adds to the same coefficients.
     */
    fun readGroup(readers: Array<JxlBitReader>, group: Int) {
        val m = matrices ?: jxlFail("group before the HF global section")
        val passes = fh.numPasses
        val bx0 = (group % dim.xsizeGroups) * GROUP_BLOCKS
        val by0 = (group / dim.xsizeGroups) * GROUP_BLOCKS
        val xs = minOf(GROUP_BLOCKS, xb - bx0)
        val ys = minOf(GROUP_BLOCKS, yb - by0)
        val selectorBits = ceilLog2(numHistograms)
        for (p in 0 until passes) {
            val selector = if (selectorBits != 0) readers[p].bits(selectorBits) else 0
            if (selector >= numHistograms) jxlFail("histogram set $selector of $numHistograms")
            ctxOffset[p] = selector * numAcContexts
            symbols[p] = JxlSymbolReader(codes[p]!!, readers[p])
        }
        val coeffs = coefficients
        val block = block
        for (by in 0 until ys) {
            val ay = by0 + by
            var bx = 0
            while (bx < xs) {
                val ax = bx0 + bx
                val s = strategy[ay * xb + ax].toInt()
                if (s < 0) jxlFail("block without a transform")
                val kind = s shr 1
                val cx = JxlAcStrategy.coveredBlocksX(kind)
                if (s and 1 == 0) {
                    bx += cx
                    continue
                }
                val cy = JxlAcStrategy.coveredBlocksY(kind)
                val log2 = JxlAcStrategy.log2CoveredBlocks(kind)
                val size = 64 shl log2
                coeffs.fill(0, 0, 3 * size)
                val quant = rawQuant[ay * xb + ax]
                for (i in 0 until 3) {
                    val c = CHANNEL_ORDER[i]
                    val sbx = bx shr hs[c]
                    val sby = by shr vs[c]
                    if ((sbx shl hs[c]) != bx || (sby shl vs[c]) != by) continue
                    // libjxl looks the quantizer up at the column of the subsampled channel, and this does the same.
                    val qf = rawQuant[ay * xb + bx0 + sbx]
                    val lfCtx = lfContext[ay * xb + ax].toInt()
                    for (p in 0 until passes) readBlock(p, c, kind, log2, cx, cy, sbx, sby, lfCtx, qf, c * size)
                }

                // Dequantize, and add the multiple of Y that X and B were coded against.
                val scale = f(invGlobalScale / quant)
                val scaleX = f(scale * xDmMultiplier)
                val scaleB = f(scale * bDmMultiplier)
                val tile = (ay shr 3) * tilesX + (ax shr 3)
                val xCc = yToX(yToXMap[tile].toInt())
                val bCc = yToB(yToBMap[tile].toInt())
                val mx = m.matrix(kind, 0)
                val my = m.matrix(kind, 1)
                val mb = m.matrix(kind, 2)
                val ox = m.matrixOffset(kind, 0)
                val oy = m.matrixOffset(kind, 1)
                val ob = m.matrixOffset(kind, 2)
                for (k in 0 until size) {
                    val qy = coeffs[size + k]
                    val qx = coeffs[k]
                    val qb = coeffs[2 * size + k]
                    if (qx or qy or qb == 0) {
                        block[k] = 0f
                        block[size + k] = 0f
                        block[2 * size + k] = 0f
                        continue
                    }
                    val dy = unbias(qy, 1) * (my[oy + k] * scale)
                    block[size + k] = dy.toFloat()
                    block[k] = (xCc * dy + unbias(qx, 0) * (mx[ox + k] * scaleX)).toFloat()
                    block[2 * size + k] = (bCc * dy + unbias(qb, 2) * (mb[ob + k] * scaleB)).toFloat()
                }
                for (c in 0 until 3) {
                    val at = (ay shr vs[c]) * lfStride + (ax shr hs[c])
                    JxlDct.lowestFrequenciesFromLf(kind, lf[c], at, lfStride, block, c * size, scratch)
                }
                for (c in 0 until 3) {
                    val sbx = bx shr hs[c]
                    val sby = by shr vs[c]
                    if ((sbx shl hs[c]) != bx || (sby shl vs[c]) != by) continue
                    val at = ((ay shr vs[c]) * 8) * outStride[c] + (ax shr hs[c]) * 8
                    JxlDct.transformToPixels(kind, block, c * size, out[c]!!, at, outStride[c], scratch)
                }
                bx += cx
            }
        }
        for (p in 0 until passes) {
            symbols[p]!!.checkFinalState()
            symbols[p] = null
        }
    }

    /** A quantized coefficient as a number: the middle of what rounds to it is nearer zero than the value itself. */
    private fun unbias(q: Int, c: Int): Double = when (q) {
        0 -> 0.0
        1 -> quantBias[c]
        -1 -> -quantBias[c]
        else -> q - quantBias[3] / q
    }

    /** Adds what pass [p] holds of one transform's coefficients of channel [c] (DecodeACVarBlock). */
    private fun readBlock(p: Int, c: Int, kind: Int, log2: Int, cx: Int, cy: Int, sbx: Int, sby: Int, lfCtx: Int, qf: Int, base: Int) {
        val covered = 1 shl log2
        val size = covered shl 6
        val nz = nonZeros[p * 3 + c]
        val at = sby * GROUP_BLOCKS + sbx
        // The count of non-zero coefficients is predicted from the blocks above and to the left.
        val predicted = when {
            sbx == 0 -> if (sby == 0) 32 else nz[at - GROUP_BLOCKS]
            sby == 0 -> nz[at - 1]
            else -> (nz[at - GROUP_BLOCKS] + nz[at - 1] + 1) / 2
        }
        val ord = JxlAcStrategy.STRATEGY_ORDER[kind]
        val order = orders[p]!![ord * 3 + c] ?: jxlFail("transform without a coefficient order")
        var qfIndex = 0
        for (t in qfThresholds) if (qf > t) qfIndex++
        val blockCtx = blockCtxMap[(((if (c < 2) c xor 1 else 2) * NUM_ORDERS + ord) * (qfThresholds.size + 1) + qfIndex) * numLfCtxs + lfCtx]
        val bucket = if (predicted >= 64) 36 else if (predicted < 8) predicted else 4 + predicted / 2
        val reader = symbols[p]!!
        val map = contextMaps[p]!!
        val offset = ctxOffset[p]
        var left = reader.readClustered(map[offset + bucket * numBlockCtxs + blockCtx])
        if (left < 0 || left > size - covered) jxlFail("$left non-zero coefficients in a transform of $covered blocks")
        val stored = (left + covered - 1) shr log2
        for (y in 0 until cy) for (x in 0 until cx) nz[at + y * GROUP_BLOCKS + x] = stored
        if (left == 0) return

        val histo = offset + numBlockCtxs * NON_ZERO_BUCKETS + ZERO_DENSITY_CONTEXTS * blockCtx
        val shift = fh.passShift[p]
        val coeffs = coefficients
        var prev = if (left > size / 16) 0 else 1
        var k = covered
        while (k < size && left != 0) {
            val ctx = histo + (NUM_NONZERO_CONTEXT[(left + covered - 1) shr log2] + FREQ_CONTEXT[k shr log2]) * 2 + prev
            val u = reader.readClustered(map[ctx])
            coeffs[base + order[k]] += ((u ushr 1) xor -(u and 1)) shl shift
            prev = if (u != 0) 1 else 0
            left -= prev
            k++
        }
        if (left != 0) jxlFail("transform ends with $left coefficients unread")
    }

    /** The three channels, each cut to the part of its blocks that the frame shows. */
    fun planes(): Array<JxlPlane> = Array(3) { c ->
        val w = JxlFrameDim.ceilDiv(dim.xsize, 1 shl hs[c])
        val h = JxlFrameDim.ceilDiv(dim.ysize, 1 shl vs[c])
        val from = out[c] ?: jxlFail("frame without an HF global section")
        val stride = outStride[c]
        if (stride == w) {
            JxlPlane(w, h, if (from.size == w * h) from else from.copyOf(w * h))
        } else {
            val plane = JxlPlane(w, h)
            for (y in 0 until h) from.copyInto(plane.data, y * w, y * stride, y * stride + w)
            plane
        }
    }

    private companion object {
        const val GLOBAL_SCALE_DENOM = 1 shl 16
        const val QUANT_MAX = 256
        const val DEFAULT_COLOR_FACTOR = 84
        const val NUM_ORDERS = 13

        /** A VarDCT group is 256 samples wide, 32 blocks. */
        const val GROUP_BLOCKS = 32

        const val NON_ZERO_BUCKETS = 37
        const val ZERO_DENSITY_CONTEXTS = 458

        /** How far past its own contexts a broken stream can point. */
        const val ZERO_DENSITY_SPARE = 474 - 458

        /** libjxl writes one set of contexts for each LF group at most: about half a million for a square frame at the pixel ceiling. */
        private const val MAX_CONTEXTS = 1L shl 26

        const val INV_SIGMA_NUM = -1.1715728752538099024

        // The weights of the LF smoothing: a side, a corner and the centre.
        const val SMOOTH_W1 = 0.20345139757231578
        const val SMOOTH_W2 = 0.0334829185968739
        const val SMOOTH_W0 = 1.0 - 4.0 * (SMOOTH_W1 + SMOOTH_W2)

        /** Y is read first, because X and B are coded against it. */
        val CHANNEL_ORDER = intArrayOf(1, 0, 2)

        /** The channel that each channel of an LF stream holds. */
        val STREAM_CHANNEL = intArrayOf(1, 0, 2)

        /** The block contexts of a frame that does not code its own: the large transforms share one. */
        val DEFAULT_BLOCK_CTX_MAP = intArrayOf(
            0, 1, 2, 2, 3, 3, 4, 5, 6, 6, 6, 6, 6,
            7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
            7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
        )

        /** The context of a coefficient from its place in the scan (kCoeffFreqContext). Entry 0 is never used. */
        val FREQ_CONTEXT = intArrayOf(
            0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 15, 16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 21, 21, 22, 22,
            23, 23, 23, 23, 24, 24, 24, 24, 25, 25, 25, 25, 26, 26, 26, 26,
            27, 27, 27, 27, 28, 28, 28, 28, 29, 29, 29, 29, 30, 30, 30, 30,
        )

        /** The context of a coefficient from how many non-zero ones are left (kCoeffNumNonzeroContext). Entry 0 is never used. */
        val NUM_NONZERO_CONTEXT = intArrayOf(
            0, 0, 31, 62, 62, 93, 93, 93, 93, 123, 123, 123, 123,
            152, 152, 152, 152, 152, 152, 152, 152, 180, 180, 180, 180, 180,
            180, 180, 180, 180, 180, 180, 180, 206, 206, 206, 206, 206, 206,
            206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206,
            206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206,
        )

        /** Rounds to single precision, the precision of libjxl's arithmetic. */
        fun f(x: Double): Double = x.toFloat().toDouble()
    }
}
