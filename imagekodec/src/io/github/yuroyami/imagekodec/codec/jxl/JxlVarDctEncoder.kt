package io.github.yuroyami.imagekodec.codec.jxl

import io.github.yuroyami.imagekodec.JxlEncodeOptions

/**
 * The lossy mode, VarDCT (18181-1 I): the picture goes to XYB, is cut into transforms of 8 by 8
 * samples and larger, and each transform's coefficients are quantized and entropy coded. The
 * mean of every 8 by 8 block (the LF image) is quantized and coded with Modular. What the
 * decoder computes from the bits comes from its own code: the quantization tables of
 * [JxlDequantMatrices], the coefficient orders of [JxlAcStrategy] and the contexts of
 * [JxlVarDct]. The decisions are this file's own design.
 */
internal object JxlVarDctEncoder {

    /** Parameters of the encoder's decisions, kept apart so that a test can measure other values. */
    internal object Tuning {
        /** The quant field at distance 1: steps are the table's weights divided by it. */
        var acQuant = 0.79

        /** The step of Y's LF at distance 1, and the power of the distance it grows with. */
        var lfStep = 0.0017
        var lfPower = 0.6

        /** The adaptive quantization: how strongly busy blocks get coarser steps, and its bounds. */
        var aqStrength = 0.5
        var aqOffset = 0.004
        var aqMin = 0.55
        var aqMax = 1.7

        /** Below this fraction of a step a coefficient goes to zero. */
        var zeroBias = 0.58

        /** Bits against squared error, for the choice of transform. */
        var lambda = 0.06

        /** Distances from which the edge-preserving filter runs one, two and three times. */
        var epf1 = 0.8
        var epf2 = 2.5
        var epf3 = 6.0
    }

    private const val GROUP_BLOCKS = 32
    private const val TILE_BLOCKS = 8
    private const val COLOR_FACTOR = 84
    private const val GLOBAL_SCALE_DENOM = 65536.0
    private const val SHARPNESS = 4

    // The quantization biases of the default header, by channel X, Y, B, then the one of larger values.
    private val QUANT_BIAS = JxlImageHeader.DEFAULT_QUANT_BIAS

    // The inverse of the default opsin inverse matrix: linear sRGB to the three cone signals.
    private val OPSIN: DoubleArray = invert3(JxlImageHeader.DEFAULT_OPSIN_INVERSE)
    private const val OPSIN_BIAS = 0.0037930732552754493
    // The decoder adds the cube root of the (negative) header bias back; this takes the root of its magnitude away.
    private val BIAS_ROOT = JxlEncMath.cbrt(OPSIN_BIAS)

    private fun invert3(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val co0 = e * i - f * h
        val co1 = -(d * i - f * g)
        val co2 = d * h - e * g
        val det = a * co0 + b * co1 + c * co2
        return doubleArrayOf(
            co0 / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            co1 / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            co2 / det, -(a * h - b * g) / det, (a * e - b * d) / det,
        )
    }

    /** The sRGB transfer function's inverse for every 8-bit or 16-bit sample. */
    private val linear8: DoubleArray by lazy { DoubleArray(256) { srgbToLinear(it / 255.0) } }
    private val linear16: DoubleArray by lazy { DoubleArray(65536) { srgbToLinear(it / 65535.0) } }

    private fun srgbToLinear(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else JxlEncMath.pow((v + 0.055) / 1.055, 2.4)

    // DCT bases of 8, 16 and 32 samples: row k is b(k, i) / n, so a dot product gives coefficient k.
    private val BASIS = Array(6) { log ->
        val n = 1 shl log
        DoubleArray(n * n) { at ->
            val k = at / n
            val i = at % n
            (if (k == 0) 1.0 else JxlEncMath.sqrt(2.0) * JxlEncMath.cos((i + 0.5) * k * PI / n)) / n
        }
    }

    // b(k, i) of n samples, for the way from the lowest frequencies back to the LF image.
    private val SYNTH = Array(3) { log ->
        val n = 1 shl log
        DoubleArray(n * n) { at ->
            val k = at / n
            val i = at % n
            if (k == 0) 1.0 else JxlEncMath.sqrt(2.0) * JxlEncMath.cos((i + 0.5) * k * PI / n)
        }
    }

    private const val PI = 3.141592653589793

    /** What the mean over 8 samples does to frequency k of n blocks (the inverse of DCTTotalResampleScale). */
    private fun resample(k: Int, n: Int): Double {
        val big = 8.0 * n
        return JxlEncMath.cos(k * PI / (2 * big)) * JxlEncMath.cos(k * PI / big) * JxlEncMath.cos(k * PI / (big / 2))
    }

    private val RESAMPLE = Array(3) { log -> DoubleArray(1 shl log) { resample(it, 1 shl log) } }

    private class Ctx(
        val xb: Int,
        val yb: Int,
        val planes: Array<DoubleArray>,
        val stride: Int,
    ) {
        /** For each block: its transform kind when it is a transform's top-left block, else -1. */
        val kind = IntArray(xb * yb) { -1 }
        val covered = BooleanArray(xb * yb)

        /** The quant field of each block, before it becomes a whole number. */
        val qf = DoubleArray(xb * yb)
        val rawQuant = IntArray(xb * yb)
        val lf = Array(3) { DoubleArray(xb * yb) }
        val qLf = Array(3) { IntArray(xb * yb) }
        val tilesX = (xb + TILE_BLOCKS - 1) / TILE_BLOCKS
        val tilesY = (yb + TILE_BLOCKS - 1) / TILE_BLOCKS
        val yToX = IntArray(tilesX * tilesY)
        val yToB = IntArray(tilesX * tilesY)
    }

    fun writeFrame(bw: JxlBitWriter, image: JxlEncImage, frame: JxlEncFrame, picture: JxlEncPicture, options: JxlEncodeOptions, cancel: (() -> Unit)?) {
        val distance = options.effectiveDistance
        val effort = options.effort
        val epf = when {
            distance >= Tuning.epf3 -> 3
            distance >= Tuning.epf2 -> 2
            distance >= Tuning.epf1 -> 1
            else -> 0
        }
        val fr = JxlEncFrame(
            modular = false,
            x0 = frame.x0, y0 = frame.y0, cropWidth = frame.cropWidth, cropHeight = frame.cropHeight, source = frame.source,
            duration = frame.duration, isLast = frame.isLast, saveAsReference = frame.saveAsReference,
            gaborish = true, epfIters = epf,
        )
        JxlEncHeaders.writeFrameHeader(bw, image, fr)
        val dim = JxlEncoder.frameDim(picture.w, picture.h, false)
        val xb = dim.xsizeBlocks
        val yb = dim.ysizeBlocks
        val ctx = Ctx(xb, yb, toXyb(picture, xb * 8, yb * 8), xb * 8)
        cancel?.invoke()
        adaptiveQuant(ctx, distance)
        inverseGaborish(ctx)
        chooseTransforms(ctx, effort)
        cancel?.invoke()

        // The global scale: the median transform's quant field becomes about 32, which leaves the
        // adaptive quantization room on both sides, as long as the largest stays at 255 or below.
        val fields = ArrayList<Double>()
        for (i in ctx.qf.indices) if (ctx.kind[i] >= 0) fields.add(ctx.qf[i])
        fields.sort()
        val median = fields[fields.size / 2]
        val maxQf = fields[fields.size - 1]
        // A block's step is its table weight times 65536 / (globalScale * rawQuant), so rawQuant is qf * 65536 / globalScale.
        var scaleWanted = median * GLOBAL_SCALE_DENOM / 32.0
        if (maxQf * GLOBAL_SCALE_DENOM / scaleWanted > 255.0) scaleWanted = maxQf * GLOBAL_SCALE_DENOM / 255.0
        val globalScale = minOf(73727, maxOf(1, (scaleWanted + 0.5).toInt()))
        for (i in ctx.qf.indices) {
            if (ctx.kind[i] < 0) continue
            ctx.rawQuant[i] = (ctx.qf[i] * GLOBAL_SCALE_DENOM / globalScale + 0.5).toInt().coerceIn(1, 256)
        }
        val invGlobal = GLOBAL_SCALE_DENOM / globalScale
        // Y's LF step is invGlobal / quantLf / 512 in the decoder.
        val wantLf = Tuning.lfStep * JxlEncMath.pow(distance, Tuning.lfPower)
        val quantLf = (invGlobal / 512.0 / wantLf + 0.5).toInt().coerceIn(1, 1 shl 16)
        val lfStep = doubleArrayOf(invGlobal / quantLf / 4096.0, invGlobal / quantLf / 512.0, invGlobal / quantLf / 256.0)

        val matrices = JxlDequantMatrices.defaults()
        var used = 0
        for (k in ctx.kind) if (k >= 0) used = used or (1 shl k)
        matrices.ensureComputed(used)

        computeLf(ctx)
        quantizeLf(ctx, lfStep)
        val coeffs = transformAll(ctx)
        cancel?.invoke()
        chromaFromLuma(ctx, coeffs, matrices, invGlobal)

        val asm = JxlFrameAssembler(dim, effort)
        val global = JxlBitWriter()
        global.bool(true) // default LF quantization factors
        global.u32(globalScale, fo(11, 1), fo(11, 2049), fo(12, 4097), fo(16, 8193))
        global.u32(quantLf, fv(16), fo(5, 1), fo(8, 1), fo(16, 1))
        global.bool(true) // default block context map
        global.bool(true) // default colour correlation
        asm.global.add(global)
        asm.global.add(JxlFrameAssembler.TreeMarker)
        // The Modular part of the frame holds alpha alone.
        val extra = ArrayList<JxlChannel>()
        if (picture.alpha) {
            val ch = JxlChannel(picture.w, picture.h)
            picture.planes[picture.colors].copyInto(ch.data)
            extra.add(ch)
        }
        var codedEnd = extra.size
        for (i in extra.indices) if (extra[i].w > dim.groupDim || extra[i].h > dim.groupDim) {
            codedEnd = i
            break
        }
        asm.global.add(JxlModStream(0, extra, emptyList(), codedEnd))

        for (g in 0 until dim.numDcGroups) {
            val x0 = (g % dim.xsizeDcGroups) * dim.groupDim
            val y0 = (g / dim.xsizeDcGroups) * dim.groupDim
            val w = minOf(dim.groupDim, xb - x0)
            val h = minOf(dim.groupDim, yb - y0)
            val shift = JxlBitWriter(1)
            shift.write(2, 0)
            asm.lfGroup(g).add(shift)
            val lfChannels = ArrayList<JxlChannel>()
            for (c in intArrayOf(1, 0, 2)) {
                val ch = JxlChannel(w, h)
                for (y in 0 until h) for (x in 0 until w) ch.data[y * w + x] = ctx.qLf[c][(y0 + y) * xb + x0 + x]
                lfChannels.add(ch)
            }
            asm.lfGroup(g).add(JxlModStream(asm.lfCoefficientsStream(g), lfChannels))
            addHfMetadata(asm, ctx, g, x0, y0, w, h)
        }

        // The coefficients of every group, coded with one code.
        val groupTokens = Array(dim.numGroups) { g ->
            cancel?.invoke()
            groupTokens(ctx, coeffs, matrices, invGlobal, dim, g, distance)
        }
        val numContexts = JxlVarDct.DEFAULT_BLOCK_CTX_MAP.max().plus(1) * (JxlVarDct.NON_ZERO_BUCKETS + JxlVarDct.ZERO_DENSITY_CONTEXTS)
        val maxClusters = when (effort) {
            1 -> 16
            2 -> 64
            else -> 128
        }
        val code = JxlEntropyEncoder.build(groupTokens.toList(), numContexts, effort, maxClusters)
        val hf = JxlBitWriter()
        hf.bool(true) // default quantization tables
        hf.write(ceilLog2(dim.numGroups), 0) // one set of histograms
        hf.u32(0, fv(0x5F), fv(0x13), fv(0), fb(13)) // natural coefficient orders
        code.writeHeader(hf)
        asm.hfGlobal.add(hf)
        for (g in 0 until dim.numGroups) {
            val w = JxlBitWriter()
            code.write(w, groupTokens[g])
            asm.group(g).add(w)
        }
        if (codedEnd < extra.size) JxlEncoder.addGroupStreams(asm, dim, extra, codedEnd)
        JxlEncHeaders.writeSections(bw, asm.finish(cancel))
    }

    /** The picture in XYB, padded to whole blocks by repeating its last row and column. */
    private fun toXyb(p: JxlEncPicture, pw: Int, ph: Int): Array<DoubleArray> {
        val out = Array(3) { DoubleArray(pw * ph) }
        val lin = if (p.bits == 8) linear8 else linear16
        val r = p.planes[0]
        val g = p.planes[if (p.colors == 1) 0 else 1]
        val b = p.planes[if (p.colors == 1) 0 else 2]
        val m = OPSIN
        for (y in 0 until ph) {
            val sy = minOf(y, p.h - 1)
            for (x in 0 until pw) {
                val i = sy * p.w + minOf(x, p.w - 1)
                val lr = lin[r[i]]
                val lg = lin[g[i]]
                val lb = lin[b[i]]
                val l = JxlEncMath.cbrt(m[0] * lr + m[1] * lg + m[2] * lb + OPSIN_BIAS) - BIAS_ROOT
                val mm = JxlEncMath.cbrt(m[3] * lr + m[4] * lg + m[5] * lb + OPSIN_BIAS) - BIAS_ROOT
                val s = JxlEncMath.cbrt(m[6] * lr + m[7] * lg + m[8] * lb + OPSIN_BIAS) - BIAS_ROOT
                val o = y * pw + x
                out[0][o] = (l - mm) * 0.5
                out[1][o] = (l + mm) * 0.5
                out[2][o] = s
            }
        }
        return out
    }

    /**
     * The quant field of each block: the distance's field, finer on flat blocks and coarser on
     * busy ones, where the eye sees less of the error (a masking estimate from the mean gradient of Y).
     */
    private fun adaptiveQuant(ctx: Ctx, distance: Double) {
        val y = ctx.planes[1]
        val s = ctx.stride
        val act = DoubleArray(ctx.xb * ctx.yb)
        var sum = 0.0
        for (by in 0 until ctx.yb) for (bx in 0 until ctx.xb) {
            var a = 0.0
            for (yy in 0 until 8) {
                val row = (by * 8 + yy) * s + bx * 8
                for (xx in 0 until 8) {
                    val v = y[row + xx]
                    if (xx < 7) a += kotlin.math.abs(v - y[row + xx + 1])
                    if (by * 8 + yy + 1 < ctx.yb * 8) a += kotlin.math.abs(v - y[row + xx + s])
                }
            }
            a /= 112.0
            act[by * ctx.xb + bx] = a
            sum += a
        }
        val mean = sum / act.size
        val base = Tuning.acQuant / distance
        for (i in act.indices) {
            val ratio = (mean + Tuning.aqOffset) / (act[i] + Tuning.aqOffset)
            val mul = JxlEncMath.pow(ratio, Tuning.aqStrength).coerceIn(Tuning.aqMin, Tuning.aqMax)
            ctx.qf[i] = base * mul
        }
    }

    /**
     * Sharpens the picture by the inverse of the decoder's Gaborish smoothing, so that the two
     * cancel: three steps of x += target - G(x), each with the edges mirrored.
     */
    private fun inverseGaborish(ctx: Ctx) {
        val w1 = 1.1 * 0.104699568
        val w2 = 1.1 * 0.055680538
        val norm = 1.0 / (1.0 + 4.0 * (w1 + w2))
        val w = ctx.xb * 8
        val h = ctx.yb * 8
        for (c in 0 until 3) {
            val target = ctx.planes[c]
            val x = target.copyOf()
            val g = DoubleArray(w * h)
            for (iter in 0 until 3) {
                for (yy in 0 until h) {
                    val t = (if (yy > 0) yy - 1 else 0) * w
                    val m = yy * w
                    val u = (if (yy + 1 < h) yy + 1 else h - 1) * w
                    for (xx in 0 until w) {
                        val l = if (xx > 0) xx - 1 else 0
                        val r = if (xx + 1 < w) xx + 1 else w - 1
                        val side = x[m + l] + x[m + r] + x[t + xx] + x[u + xx]
                        val diag = x[t + l] + x[t + r] + x[u + l] + x[u + r]
                        g[m + xx] = (x[m + xx] + w1 * side + w2 * diag) * norm
                    }
                }
                for (i in 0 until w * h) x[i] += target[i] - g[i]
            }
            x.copyInto(target)
        }
    }

    private fun rows(kind: Int) = 8 * JxlAcStrategy.coveredBlocksY(kind)
    private fun cols(kind: Int) = 8 * JxlAcStrategy.coveredBlocksX(kind)

    /** Where the coefficient of frequencies (v, h) sits in a block's array, as the decoder lays it out. */
    private fun at(kind: Int, v: Int, h: Int): Int {
        val r = rows(kind)
        val cl = cols(kind)
        return if (r >= cl) h * r + v else v * cl + h
    }

    /** The DCT of the transform of [kind] whose top-left block is ([bx], [by]), of plane [c], into [out]. */
    private fun dct(ctx: Ctx, c: Int, kind: Int, bx: Int, by: Int, out: DoubleArray, tmp: DoubleArray) {
        val r = rows(kind)
        val cl = cols(kind)
        val p = ctx.planes[c]
        val s = ctx.stride
        val bc = BASIS[cl.countTrailingZeroBits()]
        val br = BASIS[r.countTrailingZeroBits()]
        // Along each row first: tmp[y * cl + h].
        for (y in 0 until r) {
            val row = (by * 8 + y) * s + bx * 8
            for (h in 0 until cl) {
                var sum = 0.0
                val b = h * cl
                for (x in 0 until cl) sum += bc[b + x] * p[row + x]
                tmp[y * cl + h] = sum
            }
        }
        for (h in 0 until cl) {
            for (v in 0 until r) {
                var sum = 0.0
                val b = v * r
                for (y in 0 until r) sum += br[b + y] * tmp[y * cl + h]
                out[at(kind, v, h)] = sum
            }
        }
    }

    /**
     * Picks a transform for every block. Effort 1 keeps 8 by 8; effort 2 tries 16 by 16, 16 by 8
     * and 8 by 16 on each aligned 2 by 2 blocks; effort 3 also 32 by 32 on aligned 4 by 4 blocks.
     * The cheapest by estimated bits plus [Tuning.lambda] times squared error wins.
     */
    private fun chooseTransforms(ctx: Ctx, effort: Int) {
        val xb = ctx.xb
        val yb = ctx.yb
        for (i in 0 until xb * yb) ctx.kind[i] = JxlAcStrategy.DCT
        if (effort <= 1) return
        val buf = DoubleArray(1024)
        val tmp = DoubleArray(1024)
        val matrices = JxlDequantMatrices.defaults()
        matrices.ensureComputed((1 shl JxlAcStrategy.DCT) or (1 shl JxlAcStrategy.DCT16X16) or (1 shl JxlAcStrategy.DCT16X8) or
            (1 shl JxlAcStrategy.DCT8X16) or (1 shl JxlAcStrategy.DCT32X32))

        fun meanQf(bx: Int, by: Int, cx: Int, cy: Int): Double {
            var s = 0.0
            for (y in 0 until cy) for (x in 0 until cx) s += ctx.qf[(by + y) * xb + bx + x]
            return s / (cx * cy)
        }

        /** The estimated cost of one transform: bits of its quantized coefficients plus lambda times their squared error. */
        fun cost(kind: Int, bx: Int, by: Int): Double {
            val cx = JxlAcStrategy.coveredBlocksX(kind)
            val cy = JxlAcStrategy.coveredBlocksY(kind)
            val n = 64 * cx * cy
            val q = meanQf(bx, by, cx, cy)
            var bits = 2.0 * 3
            var dist = 0.0
            for (c in 0 until 3) {
                dct(ctx, c, kind, bx, by, buf, tmp)
                val m = matrices.matrix(kind, c)
                val off = matrices.matrixOffset(kind, c)
                val mul = if (c == 0) 0.8 else 1.0
                val weight = if (c == 0) 12.0 else if (c == 1) 1.0 else 0.25
                for (k in 0 until n) {
                    // The lowest frequencies come from the LF image and are not coded here.
                    if (isLlf(kind, k)) continue
                    val step = m[off + k] * mul / q
                    val v = buf[k] / step
                    val qv = quantize(v, c)
                    val err = (v - recon(qv, c)) * step
                    dist += weight * err * err * n
                    if (qv != 0) bits += 2.5 + 2.0 * JxlEncMath.log2(1.0 + kotlin.math.abs(qv))
                }
            }
            return bits + Tuning.lambda * dist * q * q * 1e4
        }

        val by2 = yb / 2 * 2
        val bx2 = xb / 2 * 2
        for (by in 0 until by2 step 2) {
            for (bx in 0 until bx2 step 2) {
                var small = 0.0
                for (y in 0 until 2) for (x in 0 until 2) small += cost(JxlAcStrategy.DCT, bx + x, by + y)
                val big = cost(JxlAcStrategy.DCT16X16, bx, by)
                val tall = cost(JxlAcStrategy.DCT16X8, bx, by) + cost(JxlAcStrategy.DCT16X8, bx + 1, by)
                val wide = cost(JxlAcStrategy.DCT8X16, bx, by) + cost(JxlAcStrategy.DCT8X16, bx, by + 1)
                val best = minOf(minOf(small, big), minOf(tall, wide))
                when (best) {
                    small -> {}
                    big -> place(ctx, JxlAcStrategy.DCT16X16, bx, by)
                    tall -> {
                        place(ctx, JxlAcStrategy.DCT16X8, bx, by)
                        place(ctx, JxlAcStrategy.DCT16X8, bx + 1, by)
                    }
                    else -> {
                        place(ctx, JxlAcStrategy.DCT8X16, bx, by)
                        place(ctx, JxlAcStrategy.DCT8X16, bx, by + 1)
                    }
                }
            }
        }
        if (effort < 3) return
        for (by in 0 until yb / 4 * 4 step 4) {
            for (bx in 0 until xb / 4 * 4 step 4) {
                var current = 0.0
                for (y in 0 until 4) for (x in 0 until 4) {
                    val k = ctx.kind[(by + y) * xb + bx + x]
                    if (k >= 0) current += cost(k, bx + x, by + y)
                }
                if (cost(JxlAcStrategy.DCT32X32, bx, by) < current) {
                    for (y in 0 until 4) for (x in 0 until 4) {
                        ctx.kind[(by + y) * xb + bx + x] = JxlAcStrategy.DCT
                        ctx.covered[(by + y) * xb + bx + x] = false
                    }
                    place(ctx, JxlAcStrategy.DCT32X32, bx, by)
                }
            }
        }
    }

    private fun place(ctx: Ctx, kind: Int, bx: Int, by: Int) {
        val cx = JxlAcStrategy.coveredBlocksX(kind)
        val cy = JxlAcStrategy.coveredBlocksY(kind)
        for (y in 0 until cy) for (x in 0 until cx) {
            val i = (by + y) * ctx.xb + bx + x
            ctx.kind[i] = -1
            ctx.covered[i] = true
        }
        ctx.kind[by * ctx.xb + bx] = kind
        ctx.covered[by * ctx.xb + bx] = false
        // A transform takes the mean quant field of its blocks.
        var s = 0.0
        for (y in 0 until cy) for (x in 0 until cx) s += ctx.qf[(by + y) * ctx.xb + bx + x]
        ctx.qf[by * ctx.xb + bx] = s / (cx * cy)
    }

    /** Whether coefficient [k] of [kind] is one of the lowest frequencies, which the LF image gives. */
    private fun isLlf(kind: Int, k: Int): Boolean {
        val r = rows(kind)
        val cl = cols(kind)
        val cy = r / 8
        val cx = cl / 8
        if (cx * cy == 1) return k == 0
        val v: Int
        val h: Int
        if (r >= cl) {
            h = k / r
            v = k % r
        } else {
            v = k / cl
            h = k % cl
        }
        return v < cy && h < cx
    }

    /** A quantized coefficient's value as the decoder reads it back, for channel [c]. */
    private fun recon(q: Int, c: Int): Double = when (q) {
        0 -> 0.0
        1 -> QUANT_BIAS[c]
        -1 -> -QUANT_BIAS[c]
        else -> q - QUANT_BIAS[3] / q
    }

    /** The whole number nearest [v] in the decoder's reading, with a dead zone around zero. */
    private fun quantize(v: Double, c: Int): Int {
        val a = if (v < 0) -v else v
        if (a < Tuning.zeroBias) return 0
        var q = (a + 0.5).toInt()
        if (q < 1) q = 1
        // The decoder pulls values toward zero; the neighbour above may come back nearer.
        val up = q + 1
        if (kotlin.math.abs(recon(up, c) - a) < kotlin.math.abs(recon(q, c) - a)) q = up
        if (q > 1 && kotlin.math.abs(recon(q - 1, c) - a) < kotlin.math.abs(recon(q, c) - a)) q--
        return if (v < 0) -q else q
    }

    /** The coefficients of every transform, three channels of [JxlAcStrategy.coefficientCount] each, by top-left block. */
    private fun transformAll(ctx: Ctx): Array<Array<DoubleArray>?> {
        val tmp = DoubleArray(1024)
        return Array(ctx.xb * ctx.yb) { i ->
            val kind = ctx.kind[i]
            if (kind < 0) {
                null
            } else {
                val bx = i % ctx.xb
                val by = i / ctx.xb
                Array(3) { c -> DoubleArray(JxlAcStrategy.coefficientCount(kind)).also { dct(ctx, c, kind, bx, by, it, tmp) } }
            }
        }
    }

    /** The LF image: for each block, the mean the decoder rebuilds the lowest frequencies from. */
    private fun computeLf(ctx: Ctx) {
        val tmp = DoubleArray(1024)
        val buf = DoubleArray(1024)
        for (by in 0 until ctx.yb) for (bx in 0 until ctx.xb) {
            val kind = ctx.kind[by * ctx.xb + bx]
            if (kind < 0) continue
            val cx = JxlAcStrategy.coveredBlocksX(kind)
            val cy = JxlAcStrategy.coveredBlocksY(kind)
            for (c in 0 until 3) {
                if (cx * cy == 1) {
                    var s = 0.0
                    val p = ctx.planes[c]
                    for (y in 0 until 8) for (x in 0 until 8) s += p[(by * 8 + y) * ctx.stride + bx * 8 + x]
                    ctx.lf[c][by * ctx.xb + bx] = s / 64.0
                    continue
                }
                dct(ctx, c, kind, bx, by, buf, tmp)
                val lx = cx.countTrailingZeroBits()
                val ly = cy.countTrailingZeroBits()
                val sx = SYNTH[lx]
                val sy = SYNTH[ly]
                val rx = RESAMPLE[lx]
                val ry = RESAMPLE[ly]
                for (y in 0 until cy) for (x in 0 until cx) {
                    var s = 0.0
                    for (v in 0 until cy) for (h in 0 until cx) {
                        s += buf[at(kind, v, h)] * ry[v] * rx[h] * sy[v * cy + y] * sx[h * cx + x]
                    }
                    ctx.lf[c][(by + y) * ctx.xb + bx + x] = s
                }
            }
        }
    }

    /** Quantizes the LF image as the decoder reads it: Y alone, X as is, B against Y. */
    private fun quantizeLf(ctx: Ctx, step: DoubleArray) {
        for (i in 0 until ctx.xb * ctx.yb) {
            val qy = round(ctx.lf[1][i] / step[1])
            val y = qy * step[1]
            ctx.qLf[1][i] = qy
            ctx.qLf[0][i] = round(ctx.lf[0][i] / step[0])
            ctx.qLf[2][i] = round((ctx.lf[2][i] - y) / step[2])
        }
    }

    private fun round(v: Double): Int = if (v >= 0) (v + 0.5).toInt() else -((-v + 0.5).toInt())

    /**
     * The chroma from luma factor of each tile of 8 by 8 blocks: the least-squares multiple of
     * Y's coefficients that X and B are nearest to, in steps of 1/84 from 0 for X and 1 for B.
     */
    private fun chromaFromLuma(ctx: Ctx, coeffs: Array<Array<DoubleArray>?>, m: JxlDequantMatrices, invGlobal: Double) {
        val sxy = DoubleArray(ctx.yToX.size)
        val syy = DoubleArray(ctx.yToX.size)
        val sby = DoubleArray(ctx.yToX.size)
        for (i in coeffs.indices) {
            val c = coeffs[i] ?: continue
            val kind = ctx.kind[i]
            val tile = (i / ctx.xb / TILE_BLOCKS) * ctx.tilesX + (i % ctx.xb) / TILE_BLOCKS
            for (k in c[1].indices) {
                if (isLlf(kind, k)) continue
                val y = c[1][k]
                sxy[tile] += c[0][k] * y
                sby[tile] += c[2][k] * y
                syy[tile] += y * y
            }
        }
        for (t in sxy.indices) {
            if (syy[t] < 1e-12) continue
            ctx.yToX[t] = round(sxy[t] / syy[t] * COLOR_FACTOR).coerceIn(-128, 127)
            ctx.yToB[t] = round((sby[t] / syy[t] - 1.0) * COLOR_FACTOR).coerceIn(-128, 127)
        }
    }

    /** Adds the HF metadata of LF group [g] (18181-1 I.2.4): chroma from luma, transforms and quant fields, filter sharpness. */
    private fun addHfMetadata(asm: JxlFrameAssembler, ctx: Ctx, g: Int, x0: Int, y0: Int, w: Int, h: Int) {
        val xb = ctx.xb
        val kinds = ArrayList<Int>()
        val quants = ArrayList<Int>()
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            val k = ctx.kind[y * xb + x]
            if (k < 0) continue
            kinds.add(k)
            quants.add(ctx.rawQuant[y * xb + x] - 1)
        }
        val head = JxlBitWriter(4)
        head.write(ceilLog2(w * h), kinds.size - 1)
        asm.lfGroup(g).add(head)
        val tw = (w + 7) shr 3
        val th = (h + 7) shr 3
        val cfx = JxlChannel(tw, th, 3, 3)
        val cfb = JxlChannel(tw, th, 3, 3)
        for (ty in 0 until th) for (tx in 0 until tw) {
            val t = ((y0 shr 3) + ty) * ctx.tilesX + (x0 shr 3) + tx
            cfx.data[ty * tw + tx] = ctx.yToX[t]
            cfb.data[ty * tw + tx] = ctx.yToB[t]
        }
        val list = JxlChannel(kinds.size, 2)
        for (i in kinds.indices) {
            list.data[i] = kinds[i]
            list.data[kinds.size + i] = quants[i]
        }
        val sharp = JxlChannel(w, h)
        sharp.data.fill(SHARPNESS)
        asm.lfGroup(g).add(JxlModStream(asm.hfMetadataStream(g), listOf(cfx, cfb, list, sharp)))
    }

    /** The tokens of group [g]'s coefficients, in the order and with the contexts [JxlVarDct] reads them. */
    private fun groupTokens(
        ctx: Ctx,
        coeffs: Array<Array<DoubleArray>?>,
        m: JxlDequantMatrices,
        invGlobal: Double,
        dim: JxlFrameDim,
        g: Int,
        distance: Double,
    ): JxlTokens {
        val xb = ctx.xb
        val bx0 = (g % dim.xsizeGroups) * GROUP_BLOCKS
        val by0 = (g / dim.xsizeGroups) * GROUP_BLOCKS
        val xs = minOf(GROUP_BLOCKS, xb - bx0)
        val ys = minOf(GROUP_BLOCKS, ctx.yb - by0)
        val tokens = JxlTokens(xs * ys * 64)
        val nonZeros = Array(3) { IntArray(GROUP_BLOCKS * GROUP_BLOCKS) }
        val numBlockCtxs = JxlVarDct.DEFAULT_BLOCK_CTX_MAP.max() + 1
        val q = Array(3) { IntArray(1024) }
        for (by in 0 until ys) {
            for (bx in 0 until xs) {
                val ay = by0 + by
                val ax = bx0 + bx
                val kind = ctx.kind[ay * xb + ax]
                if (kind < 0) continue
                val cf = coeffs[ay * xb + ax]!!
                val cx = JxlAcStrategy.coveredBlocksX(kind)
                val cy = JxlAcStrategy.coveredBlocksY(kind)
                val log2 = JxlAcStrategy.log2CoveredBlocks(kind)
                val covered = 1 shl log2
                val size = 64 shl log2
                val scale = invGlobal / ctx.rawQuant[ay * xb + ax]
                val tile = (ay / TILE_BLOCKS) * ctx.tilesX + ax / TILE_BLOCKS
                val xCc = ctx.yToX[tile].toDouble() / COLOR_FACTOR
                val bCc = 1.0 + ctx.yToB[tile].toDouble() / COLOR_FACTOR
                val my = m.matrix(kind, 1)
                val oy = m.matrixOffset(kind, 1)
                val mx = m.matrix(kind, 0)
                val ox = m.matrixOffset(kind, 0)
                val mb = m.matrix(kind, 2)
                val ob = m.matrixOffset(kind, 2)
                for (k in 0 until size) {
                    if (isLlf(kind, k)) {
                        q[0][k] = 0
                        q[1][k] = 0
                        q[2][k] = 0
                        continue
                    }
                    val stepY = my[oy + k] * scale
                    val qy = quantize(cf[1][k] / stepY, 1)
                    q[1][k] = qy
                    val dy = recon(qy, 1) * stepY
                    val stepX = mx[ox + k] * scale * 0.8
                    q[0][k] = quantize((cf[0][k] - xCc * dy) / stepX, 0)
                    val stepB = mb[ob + k] * scale
                    q[2][k] = quantize((cf[2][k] - bCc * dy) / stepB, 2)
                }
                val order = JxlAcStrategy.naturalCoeffOrder(kind)
                val ord = JxlAcStrategy.STRATEGY_ORDER[kind]
                for (c in intArrayOf(1, 0, 2)) {
                    val nz = nonZeros[c]
                    val at = by * GROUP_BLOCKS + bx
                    val predicted = when {
                        bx == 0 -> if (by == 0) 32 else nz[at - GROUP_BLOCKS]
                        by == 0 -> nz[at - 1]
                        else -> (nz[at - GROUP_BLOCKS] + nz[at - 1] + 1) / 2
                    }
                    val blockCtx = JxlVarDct.DEFAULT_BLOCK_CTX_MAP[(if (c < 2) c xor 1 else 2) * JxlAcStrategy.NUM_ORDERS + ord]
                    val bucket = if (predicted >= 64) 36 else if (predicted < 8) predicted else 4 + predicted / 2
                    val qc = q[c]
                    var left = 0
                    for (k in covered until size) if (qc[order[k]] != 0) left++
                    tokens.add(bucket * numBlockCtxs + blockCtx, left)
                    val stored = (left + covered - 1) shr log2
                    for (y in 0 until cy) for (x in 0 until cx) nz[at + y * GROUP_BLOCKS + x] = stored
                    if (left == 0) continue
                    val histo = numBlockCtxs * JxlVarDct.NON_ZERO_BUCKETS + JxlVarDct.ZERO_DENSITY_CONTEXTS * blockCtx
                    var prev = if (left > size / 16) 0 else 1
                    var k = covered
                    while (k < size && left != 0) {
                        val v = qc[order[k]]
                        val context = histo + (JxlVarDct.NUM_NONZERO_CONTEXT[(left + covered - 1) shr log2] + JxlVarDct.FREQ_CONTEXT[k shr log2]) * 2 + prev
                        tokens.add(context, packSigned(v))
                        prev = if (v != 0) 1 else 0
                        left -= prev
                        k++
                    }
                }
            }
        }
        return tokens
    }
}
