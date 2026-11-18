package io.github.yuroyami.imagekodec.heic

/**
 * The inter prediction samples of a prediction block, 8.5.3.3, written straight into the picture: the
 * fractional sample interpolation of 8.5.3.3.3 from each reference picture the block uses, then the
 * default or explicit weighted sample prediction of 8.5.3.3.4. Every sum stays in Int: a 16-bit sample
 * through two 8-tap passes stays below 2^27, and a weight of at most 255 keeps the products below 2^31.
 */
internal class InterPredictor(private val d: PictureDecoder) {
    private val sps = d.sps
    private val pred0 = IntArray(64 * 64)
    private val pred1 = IntArray(64 * 64)
    /** The rows of the first, horizontal pass of a two-dimensional interpolation. */
    private val tmp = IntArray((64 + 7) * 64)
    /** The reference samples a block reads, its edge padded, when the block reaches past the picture. */
    private val window = ShortArray((64 + 7) * (64 + 7))

    /**
     * Predicts the block at ([xPb], [yPb]) of [w] by [h] luma samples of [slice] from reference index
     * [r0] of list 0 with vector [m0] and [r1] of list 1 with [m1], each -1 when the list is not used.
     */
    fun predict(slice: SliceHeader, refs: RefLists, xPb: Int, yPb: Int, w: Int, h: Int, r0: Int, m0: Int, r1: Int, m1: Int) {
        val explicit = if (slice.sliceType == SliceType.P) d.pps.weightedPred else d.pps.weightedBipred
        val weights = if (explicit) slice.weights else null
        for (c in d.planes.indices) {
            val sw = if (c == 0) 0 else d.subWShift
            val sh = if (c == 0) 0 else d.subHShift
            val bw = w shr sw
            val bh = h shr sh
            val x = xPb shr sw
            val y = yPb shr sh
            val bitDepth = if (c == 0) sps.bitDepthY else sps.bitDepthC
            if (r0 >= 0) sample(refs.pictures[0][r0], c, x, y, bw, bh, m0, sw, sh, bitDepth, pred0)
            if (r1 >= 0) sample(refs.pictures[1][r1], c, x, y, bw, bh, m1, sw, sh, bitDepth, pred1)
            if (weights == null) defaultWeights(c, x, y, bw, bh, r0 >= 0, r1 >= 0, bitDepth)
            else explicitWeights(weights, c, x, y, bw, bh, r0, r1, bitDepth)
        }
    }

    /**
     * predSamplesLX of component [c] for the block at ([x], [y]) of [bw] by [bh] in that component's
     * samples, from [ref] displaced by the luma vector [mv] (8.5.3.3.3.1). A chroma vector is in units of
     * 1/8 of a chroma sample, mvCLX of 8.5.3.2.10: twice the luma vector, then divided by SubWidthC and
     * SubHeightC.
     */
    private fun sample(ref: DecodedPicture, c: Int, x: Int, y: Int, bw: Int, bh: Int, mv: Int, sw: Int, sh: Int, bitDepth: Int, out: IntArray) {
        val mvx = MotionField.x(mv)
        val mvy = MotionField.y(mv)
        // A decoder of one of three separate colour planes reads the same plane of the reference.
        val plane = ref.planes[c + d.colourPlane]
        val stride = ref.strides[c + d.colourPlane]
        val pw = sps.width shr sw
        val ph = sps.height shr sh
        if (c == 0) {
            interpolate(plane, stride, pw, ph, x + (mvx shr 2), y + (mvy shr 2), mvx and 3, mvy and 3, bw, bh, LUMA, 8, bitDepth, out)
        } else {
            val mvcx = (mvx * 2) shr sw
            val mvcy = (mvy * 2) shr sh
            interpolate(plane, stride, pw, ph, x + (mvcx shr 3), y + (mvcy shr 3), mvcx and 7, mvcy and 7, bw, bh, CHROMA, 4, bitDepth, out)
        }
    }

    /**
     * 8.5.3.3.3.2 and 8.5.3.3.3.3 for a whole block: [taps] samples from [taps] / 2 - 1 before each
     * position, with the coefficients [coef] of each fraction. The reference positions are clipped to
     * the picture (8-222, 8-223, 8-239 and 8-240), which the window does once for a block at the edge.
     */
    private fun interpolate(
        src: ShortArray, stride: Int, pw: Int, ph: Int, xInt: Int, yInt: Int, xFrac: Int, yFrac: Int,
        bw: Int, bh: Int, coef: Array<IntArray>, taps: Int, bitDepth: Int, out: IntArray,
    ) {
        val before = taps / 2 - 1
        var s = src
        var st = stride
        var base: Int
        val x0 = xInt - before
        val y0 = yInt - before
        val ww = bw + taps - 1
        val wh = bh + taps - 1
        if (x0 >= 0 && y0 >= 0 && x0 + ww <= pw && y0 + wh <= ph) {
            base = y0 * stride + x0
        } else {
            for (j in 0 until wh) {
                val yy = clip(0, ph - 1, y0 + j) * stride
                val row = j * ww
                for (i in 0 until ww) window[row + i] = src[yy + clip(0, pw - 1, x0 + i)]
            }
            s = window
            st = ww
            base = 0
        }
        val shift1 = minOf(4, bitDepth - 8)
        val shift3 = maxOf(2, 14 - bitDepth)
        // Position ( 0, 0 ) of the block in the window or the plane.
        val origin = base + before * st + before
        if (xFrac == 0 && yFrac == 0) {
            for (j in 0 until bh) {
                val row = origin + j * st
                for (i in 0 until bw) out[j * bw + i] = (s[row + i].toInt() and 0xFFFF) shl shift3
            }
            return
        }
        if (yFrac == 0) {
            filter(s, origin - before, st, 1, coef[xFrac], taps, bw, bh, shift1, out, bw)
            return
        }
        if (xFrac == 0) {
            filter(s, origin - before * st, st, st, coef[yFrac], taps, bw, bh, shift1, out, bw)
            return
        }
        // The horizontal pass over the taps - 1 extra rows, then the vertical pass with shift2 = 6.
        filter(s, origin - before * st - before, st, 1, coef[xFrac], taps, bw, wh, shift1, tmp, bw)
        val cv = coef[yFrac]
        for (j in 0 until bh) {
            for (i in 0 until bw) {
                var sum = 0
                var p = j * bw + i
                for (k in 0 until taps) {
                    sum += cv[k] * tmp[p]
                    p += bw
                }
                out[j * bw + i] = sum shr 6
            }
        }
    }

    /** One filter pass: [rows] rows of [bw] samples, each the sum of [taps] samples [step] apart from [first]. */
    private fun filter(s: ShortArray, first: Int, st: Int, step: Int, c: IntArray, taps: Int, bw: Int, rows: Int, shift: Int, out: IntArray, outStride: Int) {
        if (taps == 8) {
            val c0 = c[0]; val c1 = c[1]; val c2 = c[2]; val c3 = c[3]; val c4 = c[4]; val c5 = c[5]; val c6 = c[6]; val c7 = c[7]
            for (j in 0 until rows) {
                var p = first + j * st
                val o = j * outStride
                for (i in 0 until bw) {
                    val sum = c0 * (s[p].toInt() and 0xFFFF) + c1 * (s[p + step].toInt() and 0xFFFF) +
                        c2 * (s[p + 2 * step].toInt() and 0xFFFF) + c3 * (s[p + 3 * step].toInt() and 0xFFFF) +
                        c4 * (s[p + 4 * step].toInt() and 0xFFFF) + c5 * (s[p + 5 * step].toInt() and 0xFFFF) +
                        c6 * (s[p + 6 * step].toInt() and 0xFFFF) + c7 * (s[p + 7 * step].toInt() and 0xFFFF)
                    out[o + i] = sum shr shift
                    p++
                }
            }
        } else {
            val c0 = c[0]; val c1 = c[1]; val c2 = c[2]; val c3 = c[3]
            for (j in 0 until rows) {
                var p = first + j * st
                val o = j * outStride
                for (i in 0 until bw) {
                    val sum = c0 * (s[p].toInt() and 0xFFFF) + c1 * (s[p + step].toInt() and 0xFFFF) +
                        c2 * (s[p + 2 * step].toInt() and 0xFFFF) + c3 * (s[p + 3 * step].toInt() and 0xFFFF)
                    out[o + i] = sum shr shift
                    p++
                }
            }
        }
    }

    /** 8.5.3.3.4.2. */
    private fun defaultWeights(c: Int, x: Int, y: Int, bw: Int, bh: Int, l0: Boolean, l1: Boolean, bitDepth: Int) {
        val plane = d.planes[c]
        val stride = d.strides[c]
        val max = (1 shl bitDepth) - 1
        if (l0 && l1) {
            val shift2 = maxOf(3, 15 - bitDepth)
            val offset2 = 1 shl (shift2 - 1)
            for (j in 0 until bh) {
                val row = (y + j) * stride + x
                for (i in 0 until bw) {
                    val v = (pred0[j * bw + i] + pred1[j * bw + i] + offset2) shr shift2
                    plane[row + i] = (if (v < 0) 0 else if (v > max) max else v).toShort()
                }
            }
        } else {
            val p = if (l0) pred0 else pred1
            val shift1 = maxOf(2, 14 - bitDepth)
            val offset1 = 1 shl (shift1 - 1)
            for (j in 0 until bh) {
                val row = (y + j) * stride + x
                for (i in 0 until bw) {
                    val v = (p[j * bw + i] + offset1) shr shift1
                    plane[row + i] = (if (v < 0) 0 else if (v > max) max else v).toShort()
                }
            }
        }
    }

    /** 8.5.3.3.4.3, with the weights and offsets of pred_weight_table for reference indices [r0] and [r1]. */
    private fun explicitWeights(wt: PredWeights, c: Int, x: Int, y: Int, bw: Int, bh: Int, r0: Int, r1: Int, bitDepth: Int) {
        val plane = d.planes[c]
        val stride = d.strides[c]
        val max = (1 shl bitDepth) - 1
        val shift1 = maxOf(2, 14 - bitDepth)
        // WpOffsetBdShiftY and WpOffsetBdShiftC, 7-31 and 7-32.
        val offsetShift = if (sps.highPrecisionOffsets) 0 else bitDepth - 8
        val log2Wd = (if (c == 0) wt.lumaLog2Denom else wt.chromaLog2Denom) + shift1
        fun weight(list: Int, r: Int) = if (c == 0) wt.lumaWeight[list][r] else wt.chromaWeight[list][2 * r + c - 1]
        fun offset(list: Int, r: Int) = (if (c == 0) wt.lumaOffset[list][r] else wt.chromaOffset[list][2 * r + c - 1]) shl offsetShift
        if (r0 >= 0 && r1 >= 0) {
            val w0 = weight(0, r0)
            val w1 = weight(1, r1)
            val o = (offset(0, r0) + offset(1, r1) + 1) shl log2Wd
            for (j in 0 until bh) {
                val row = (y + j) * stride + x
                for (i in 0 until bw) {
                    val v = (pred0[j * bw + i] * w0 + pred1[j * bw + i] * w1 + o) shr (log2Wd + 1)
                    plane[row + i] = (if (v < 0) 0 else if (v > max) max else v).toShort()
                }
            }
        } else {
            val list = if (r0 >= 0) 0 else 1
            val r = if (r0 >= 0) r0 else r1
            val p = if (r0 >= 0) pred0 else pred1
            val w = weight(list, r)
            val o = offset(list, r)
            val round = 1 shl (log2Wd - 1)
            for (j in 0 until bh) {
                val row = (y + j) * stride + x
                for (i in 0 until bw) {
                    val v = ((p[j * bw + i] * w + round) shr log2Wd) + o
                    plane[row + i] = (if (v < 0) 0 else if (v > max) max else v).toShort()
                }
            }
        }
    }

    private companion object {
        /** The luma filter of 8-224 to 8-226 by xFracL (or yFracL), over A−3 to A4; fraction 0 is the sample itself. */
        val LUMA = arrayOf(
            intArrayOf(0, 0, 0, 64, 0, 0, 0, 0),
            intArrayOf(-1, 4, -10, 58, 17, -5, 1, 0),
            intArrayOf(-1, 4, -11, 40, 40, -11, 4, -1),
            intArrayOf(0, 1, -5, 17, 58, -10, 4, -1),
        )

        /** The chroma filter of 8-241 to 8-247 by xFracC (or yFracC), over B−1 to B2. */
        val CHROMA = arrayOf(
            intArrayOf(0, 64, 0, 0),
            intArrayOf(-2, 58, 10, -2),
            intArrayOf(-4, 54, 16, -2),
            intArrayOf(-6, 46, 28, -4),
            intArrayOf(-4, 36, 36, -4),
            intArrayOf(-4, 28, 46, -6),
            intArrayOf(-2, 16, 54, -4),
            intArrayOf(-2, 10, 58, -2),
        )

        fun clip(lo: Int, hi: Int, v: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    }
}
