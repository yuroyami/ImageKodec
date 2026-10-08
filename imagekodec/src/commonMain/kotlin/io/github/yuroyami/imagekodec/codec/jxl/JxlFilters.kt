// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/render_pipeline/stage_chroma_upsampling.cc,
// stage_gaborish.cc, stage_epf.cc, stage_upsampling.cc, simple_render_pipeline.cc, and
// lib/jxl/epf.h, image_ops.h, image_metadata.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import kotlin.math.abs

/**
 * The filters a frame's planes go through after they are decoded, in this order:
 * - Chroma upsampling brings a chroma plane coded at half size to the size of luma.
 * - Gaborish is a light 3x3 blur. It undoes the sharpening an encoder does before it codes a frame.
 * - The edge-preserving filter smooths the ringing and the block edges that coding leaves, and keeps real edges.
 * - Upsampling enlarges a frame coded at a half, a quarter or an eighth of its size.
 *
 * A filter reads a sample outside a plane as the mirrored sample inside it, the way
 * libjxl's render pipeline pads a plane.
 */
internal object JxlFilters {

    // The largest side JPEG XL allows. It keeps the index arithmetic below inside an Int.
    private const val MAX_SIDE = 1 shl 30

    // The edge-preserving filter works on strips this wide, so its working memory stays small.
    private const val EPF_STRIP = 1024

    // kMinSigma of epf.h, which libjxl holds as a 32-bit float. The edge-preserving filter
    // leaves a block alone when its stored sigma value is below it.
    private val MIN_SIGMA = f32(-3.90524291751269967465540850526868)

    /**
     * Doubles a chroma plane in one direction (render_pipeline/stage_chroma_upsampling.cc).
     * The result is [outSize] wide (or high), which is 2*size or 2*size-1.
     */
    fun upsampleChroma(plane: JxlPlane, horizontal: Boolean, outSize: Int): JxlPlane {
        checkPlane(plane)
        val w = plane.w
        val h = plane.h
        val size = if (horizontal) w else h
        if (outSize < 0 || outSize > 2L * size) jxlFail("chroma plane of $size samples upsampled to $outSize")
        val out = if (horizontal) newPlane(outSize, h) else newPlane(w, outSize)
        val src = plane.data
        val dst = out.data
        // The number of input samples that the output needs.
        val used = outSize / 2 + (outSize and 1)
        if (horizontal) {
            for (y in 0 until h) {
                val i = y * w
                val o = y * outSize
                for (x in 0 until used) {
                    // Each sample gives two: three quarters of itself and a quarter of the neighbour on that side.
                    val current = 0.75 * src[i + x].toDouble()
                    val prev = src[i + if (x > 0) x - 1 else 0].toDouble()
                    dst[o + 2 * x] = (0.25 * prev + current).toFloat()
                    if (2 * x + 1 < outSize) {
                        val next = src[i + if (x + 1 < w) x + 1 else w - 1].toDouble()
                        dst[o + 2 * x + 1] = (0.25 * next + current).toFloat()
                    }
                }
            }
        } else if (w > 0) {
            for (y in 0 until used) {
                val mid = y * w
                val top = (if (y > 0) y - 1 else 0) * w
                val bottom = (if (y + 1 < h) y + 1 else h - 1) * w
                val o = 2 * y * w
                val second = 2 * y + 1 < outSize
                for (x in 0 until w) {
                    val current = 0.75 * src[mid + x].toDouble()
                    dst[o + x] = (0.25 * src[top + x].toDouble() + current).toFloat()
                    if (second) dst[o + w + x] = (0.25 * src[bottom + x].toDouble() + current).toFloat()
                }
            }
        }
        return out
    }

    /**
     * Gaborish (render_pipeline/stage_gaborish.cc): the 3x3 smoothing of each of the three
     * planes, with the weights of [lf]. Returns new planes. Planes can differ in size from
     * each other.
     */
    fun gaborish(planes: Array<JxlPlane>, lf: JxlLoopFilter): Array<JxlPlane> {
        if (planes.size != 3) jxlFail("Gaborish given ${planes.size} planes")
        return Array(3) { c ->
            checkPlane(planes[c])
            // libjxl scales the kernel to a sum of 1 in 32-bit floats. Each step is rounded
            // the same way here, which gives the same weights.
            val w1 = f32(lf.gabWeights[2 * c])
            val w2 = f32(lf.gabWeights[2 * c + 1])
            val mul = f32(1.0 / f32(1.0 + 4.0 * f32(w1 + w2)))
            smooth(planes[c], mul, f32(w1 * mul), f32(w2 * mul))
        }
    }

    /**
     * Smooths [plane] with a 3x3 kernel: [w0] for the sample, [w1] for its four nearest
     * neighbours and [w2] for the four diagonal ones.
     */
    private fun smooth(plane: JxlPlane, w0: Double, w1: Double, w2: Double): JxlPlane {
        val w = plane.w
        val h = plane.h
        val out = newPlane(w, h)
        if (w == 0 || h == 0) return out
        val win = RowWindow(arrayOf(plane), 1, w)
        win.moveTo(0, w)
        val b = win.rows[0]
        val dst = out.data
        for (y in 0 until h) {
            val t = win.row(y - 1)
            val m = win.row(y)
            val u = win.row(y + 1)
            val o = y * w
            for (x in 0 until w) {
                val sum1 = (b[m + x - 1].toDouble() + b[m + x + 1].toDouble()) + (b[t + x].toDouble() + b[u + x].toDouble())
                val sum2 = (b[t + x - 1].toDouble() + b[t + x + 1].toDouble()) +
                    (b[u + x - 1].toDouble() + b[u + x + 1].toDouble())
                dst[o + x] = (sum2 * w2 + (sum1 * w1 + b[m + x].toDouble() * w0)).toFloat()
            }
        }
        return out
    }

    /**
     * The edge-preserving filter (render_pipeline/stage_epf.cc), all of the steps
     * lf.epfIters asks for, in libjxl's order. [sigma] has one value for each 8x8 block:
     * libjxl's stored 1/sigma value; it is ceil(w/8) by ceil(h/8). The three planes have
     * one size. Returns new planes.
     */
    fun epf(planes: Array<JxlPlane>, lf: JxlLoopFilter, sigma: JxlPlane): Array<JxlPlane> {
        if (planes.size != 3) jxlFail("edge-preserving filter given ${planes.size} planes")
        val w = planes[0].w
        val h = planes[0].h
        for (p in planes) {
            checkPlane(p)
            if (p.w != w || p.h != h) jxlFail("edge-preserving filter given planes of different sizes")
        }
        checkPlane(sigma)
        var cur = planes
        if (w > 0 && h > 0) {
            if (sigma.w == 0 || sigma.h == 0) jxlFail("edge-preserving filter given no sigma")
            // libjxl holds these factors as 32-bit floats, so they are rounded the same way here.
            val scale = 1.65
            // The widest step runs first, and only when the frame asks for all three.
            if (lf.epfIters >= 3) cur = epfStep(cur, lf, sigma, 2, true, f32(f32(lf.epfPass0SigmaScale) * scale))
            if (lf.epfIters >= 1) cur = epfStep(cur, lf, sigma, 1, true, f32(scale))
            if (lf.epfIters >= 2) cur = epfStep(cur, lf, sigma, 1, false, f32(f32(lf.epfPass2SigmaScale) * scale))
        }
        return if (cur === planes) Array(3) { JxlPlane(w, h, planes[it].data.copyOf(w * h)) } else cur
    }

    /**
     * One step of the edge-preserving filter. A sample becomes the weighted mean of itself
     * and of its neighbours within [radius] steps. A neighbour weighs less the more the
     * samples around it differ from the samples around the sample: the five of a plus
     * shape when [plus] is set (libjxl's steps 0 and 1), else the one sample (step 2).
     * The block's sigma value and [sm] scale that difference, which libjxl calls a SAD
     * (sum of absolute differences).
     */
    private fun epfStep(
        src: Array<JxlPlane>, lf: JxlLoopFilter, sigma: JxlPlane, radius: Int, plus: Boolean, sm: Double,
    ): Array<JxlPlane> {
        val w = src[0].w
        val h = src[0].h
        // The neighbours row by row, which is the order of libjxl's sads_off.
        val dy = IntArray(12)
        val dx = IntArray(12)
        var n = 0
        for (j in -radius..radius) for (i in -radius..radius) {
            if (abs(j) + abs(i) in 1..radius) {
                dy[n] = j
                dx[n++] = i
            }
        }
        val scale0 = f32(lf.epfChannelScale[0])
        val scale1 = f32(lf.epfChannelScale[1])
        val scale2 = f32(lf.epfChannelScale[2])
        // A difference counts for less on the outer samples of an 8x8 block, where blocking shows.
        val bsm = f32(sm * f32(lf.epfBorderSadMul))

        val strip = minOf(w, EPF_STRIP)
        val win = RowWindow(src, if (plus) radius + 1 else radius, strip)
        val b0 = win.rows[0]
        val b1 = win.rows[1]
        val b2 = win.rows[2]
        // For each neighbour, three rows of how far a sample is from the sample at that
        // neighbour's offset, with one column more on both sides. Row yy is at slot (yy + 1) % 3.
        // A plus-shaped SAD is the sum of five of these, so each one is worked out once, not five times.
        val dStride = strip + 2
        val dist = DoubleArray(if (plus) n * 3 * dStride else 0)
        val near = IntArray(n)

        val out = Array(3) { newPlane(w, h) }
        val o0 = out[0].data
        val o1 = out[1].data
        val o2 = out[2].data
        val sig = sigma.data
        val lastBx = sigma.w - 1
        val lastBy = sigma.h - 1

        var x0 = 0
        while (x0 < w) {
            val width = minOf(strip, w - x0)
            win.moveTo(x0, width)
            for (y in 0 until h) {
                val centre = win.row(y)
                for (i in 0 until n) near[i] = win.row(y + dy[i]) + dx[i]
                if (plus) {
                    // Rows y - 1 and y are left from the rows above; only the first row has to make them.
                    for (yy in (if (y == 0) -1 else y + 1)..y + 1) {
                        val a = win.row(yy)
                        for (i in 0 until n) {
                            val b = win.row(yy + dy[i]) + dx[i]
                            val d = (i * 3 + (yy + 1) % 3) * dStride + 1
                            for (x in -1..width) {
                                dist[d + x] = scale0 * abs(b0[a + x].toDouble() - b0[b + x].toDouble()) +
                                    scale1 * abs(b1[a + x].toDouble() - b1[b + x].toDouble()) +
                                    scale2 * abs(b2[a + x].toDouble() - b2[b + x].toDouble())
                            }
                        }
                    }
                }
                val dUp = (y % 3) * dStride + 1
                val dMid = ((y + 1) % 3) * dStride + 1
                val dDown = ((y + 2) % 3) * dStride + 1
                val rowEdge = (y and 7) == 0 || (y and 7) == 7
                val sigmaRow = minOf(y shr 3, lastBy) * sigma.w
                val o = y * w + x0
                for (lx in 0 until width) {
                    val x = x0 + lx
                    val s = sig[sigmaRow + minOf(x shr 3, lastBx)].toDouble()
                    if (s < MIN_SIGMA) {
                        o0[o + lx] = b0[centre + lx]
                        o1[o + lx] = b1[centre + lx]
                        o2[o + lx] = b2[centre + lx]
                        continue
                    }
                    val c0 = b0[centre + lx].toDouble()
                    val c1 = b1[centre + lx].toDouble()
                    val c2 = b2[centre + lx].toDouble()
                    val invSigma = s * if (rowEdge || (x and 7) == 0 || (x and 7) == 7) bsm else sm
                    var sum0 = c0
                    var sum1 = c1
                    var sum2 = c2
                    var sumW = 1.0
                    for (i in 0 until n) {
                        val p = near[i] + lx
                        val n0 = b0[p].toDouble()
                        val n1 = b1[p].toDouble()
                        val n2 = b2[p].toDouble()
                        val sad = if (plus) {
                            val d = i * 3 * dStride + lx
                            dist[d + dMid] + dist[d + dUp] + dist[d + dMid - 1] + dist[d + dDown] + dist[d + dMid + 1]
                        } else {
                            scale0 * abs(n0 - c0) + scale1 * abs(n1 - c1) + scale2 * abs(n2 - c2)
                        }
                        // The stored sigma value is negative, so the weight falls from 1 as the SAD grows.
                        // libjxl 0.11.1 takes the zeroflush fields of the loop filter and does not use them.
                        var weight = sad * invSigma + 1.0
                        if (weight < 0.0) weight = 0.0
                        sumW += weight
                        sum0 += weight * n0
                        sum1 += weight * n1
                        sum2 += weight * n2
                    }
                    val inv = 1.0 / sumW
                    o0[o + lx] = (sum0 * inv).toFloat()
                    o1[o + lx] = (sum1 * inv).toFloat()
                    o2[o + lx] = (sum2 * inv).toFloat()
                }
            }
            x0 += width
        }
        return out
    }

    /**
     * Upsampling by [factor] 2, 4 or 8 (render_pipeline/stage_upsampling.cc) with the
     * image's custom kernel or the default one. The result is cropped to [outW] by [outH].
     */
    fun upsample(plane: JxlPlane, factor: Int, image: JxlImageHeader, outW: Int, outH: Int): JxlPlane {
        checkPlane(plane)
        val w = plane.w
        val h = plane.h
        val kernel = when (factor) {
            2 -> upsamplingKernel(image.upsampling2Weights, DEFAULT_WEIGHTS_2, 2)
            4 -> upsamplingKernel(image.upsampling4Weights, DEFAULT_WEIGHTS_4, 4)
            8 -> upsamplingKernel(image.upsampling8Weights, DEFAULT_WEIGHTS_8, 8)
            else -> jxlFail("upsampling by $factor")
        }
        if (outW < 0 || outH < 0 || outW > w.toLong() * factor || outH > h.toLong() * factor) {
            jxlFail("plane of $w by $h upsampled $factor times to $outW by $outH")
        }
        val out = newPlane(outW, outH)
        if (outW == 0 || outH == 0) return out
        val win = RowWindow(arrayOf(plane), 2, w)
        win.moveTo(0, w)
        val b = win.rows[0]
        val dst = out.data
        val rows = IntArray(5)
        val v = DoubleArray(25)
        // The input samples that the cropped output needs.
        val usedW = (outW - 1) / factor + 1
        val usedH = (outH - 1) / factor + 1
        for (y in 0 until usedH) {
            for (k in 0 until 5) rows[k] = win.row(y + k - 2)
            for (x in 0 until usedW) {
                var min = b[rows[2] + x].toDouble()
                var max = min
                var t = 0
                for (k in 0 until 5) {
                    val r = rows[k] + x - 2
                    for (i in 0 until 5) {
                        val s = b[r + i].toDouble()
                        v[t++] = s
                        if (s < min) min = s
                        if (s > max) max = s
                    }
                }
                for (oy in 0 until factor) {
                    val yy = y * factor + oy
                    if (yy >= outH) break
                    for (ox in 0 until factor) {
                        val xx = x * factor + ox
                        if (xx >= outW) break
                        val k = (oy * factor + ox) * 25
                        var sum = 0.0
                        for (i in 0 until 25) sum += kernel[k + i] * v[i]
                        // libjxl keeps the result between the smallest and the largest of the 25 samples.
                        if (sum < min) sum = min
                        if (sum > max) sum = max
                        dst[yy * outW + xx] = sum.toFloat()
                    }
                }
            }
        }
        return out
    }

    /**
     * The 5x5 kernel of every output position of one input sample, row by row, for
     * upsampling by [factor]. The weights hold the kernels of one corner of the sample as
     * one square, which is symmetric, so only its upper triangle is stored. The other
     * corners use the same kernels mirrored.
     */
    private fun upsamplingKernel(custom: DoubleArray?, default: FloatArray, factor: Int): DoubleArray {
        val weights = custom ?: DoubleArray(default.size) { default[it].toDouble() }
        val half = factor / 2
        val side = 5 * half
        if (weights.size < side * (side + 1) / 2) jxlFail("upsampling kernel of ${weights.size} weights")
        val kernel = DoubleArray(factor * factor * 25)
        for (oy in 0 until factor) for (ox in 0 until factor) for (iy in 0 until 5) for (ix in 0 until 5) {
            val j = if (oy < half) oy * 5 + iy else (factor - 1 - oy) * 5 + 4 - iy
            val i = if (ox < half) ox * 5 + ix else (factor - 1 - ox) * 5 + 4 - ix
            val lo = minOf(i, j)
            val hi = maxOf(i, j)
            kernel[((oy * factor + ox) * 5 + iy) * 5 + ix] = weights[side * lo - lo * (lo - 1) / 2 + hi - lo]
        }
        return kernel
    }

    /** The index inside 0 until [size] that libjxl's `Mirror` reads for [x]. The edge sample is repeated once. */
    private fun mirror(x: Int, size: Int): Int {
        var v = x
        while (v < 0 || v >= size) v = if (v < 0) -v - 1 else size - 1 - (v - size)
        return v
    }

    /** Rounds [x] to a 32-bit float, which is how libjxl holds the constants of its filters. */
    private fun f32(x: Double): Double {
        // A value stored in a FloatArray is rounded to 32 bits on every target.
        val a = FloatArray(1)
        a[0] = x.toFloat()
        return a[0].toDouble()
    }

    /** Fails for a plane that the loops here cannot index safely: a wrong size, or an array shorter than the size. */
    private fun checkPlane(p: JxlPlane) {
        if (p.w < 0 || p.h < 0 || p.w > MAX_SIDE || p.h > MAX_SIDE || p.w.toLong() * p.h > p.data.size) {
            jxlFail("plane of ${p.w} by ${p.h} holds ${p.data.size} samples")
        }
    }

    /** A plane of zeros. Fails when the sample count does not fit an array. */
    private fun newPlane(w: Int, h: Int): JxlPlane {
        if (w.toLong() * h > Int.MAX_VALUE) jxlFail("plane of $w by $h samples is too large")
        return JxlPlane(w, h)
    }

    /**
     * A few rows of planes of one size, each row with [border] more samples on both sides.
     * A filter that moves down a plane reads rows y - border to y + border, so the window
     * holds that many rows and copies each row of a plane once. The window can cover a
     * strip of the columns, so that its memory does not grow with the plane's width.
     */
    private class RowWindow(private val planes: Array<JxlPlane>, private val border: Int, maxWidth: Int) {
        private val w = planes[0].w
        private val h = planes[0].h

        // A plane with fewer rows than the window needs only its own rows.
        private val slots = minOf(2 * border + 1, h)
        private val stride = maxWidth + 2 * border
        private val held = IntArray(slots) { -1 }
        private var x0 = 0
        private var width = 0

        /** The rows of each plane, one array for each plane. [row] gives the place of a row in them. */
        val rows: Array<FloatArray>

        init {
            val size = slots.toLong() * stride
            if (size > Int.MAX_VALUE) jxlFail("plane of $w by $h samples is too large to filter")
            rows = Array(planes.size) { FloatArray(size.toInt()) }
        }

        /** Moves the window to the [width] columns that start at [x0], and drops the rows it holds. Call it before [row]. */
        fun moveTo(x0: Int, width: Int) {
            this.x0 = x0
            this.width = width
            held.fill(-1)
        }

        /**
         * The index, in each array of [rows], of the window's first column in row [y].
         * [y] can be up to [border] rows outside the plane. The rows of one step of the
         * filter are all within 2 * border + 1 rows, so they never share a slot.
         */
        fun row(y: Int): Int {
            val r = mirror(y, h)
            val slot = r % slots
            val base = slot * stride + border
            if (held[slot] != r) {
                held[slot] = r
                val from = r * w
                for (c in planes.indices) {
                    val src = planes[c].data
                    val dst = rows[c]
                    src.copyInto(dst, base, from + x0, from + x0 + width)
                    for (i in 1..border) {
                        dst[base - i] = src[from + mirror(x0 - i, w)]
                        dst[base + width - 1 + i] = src[from + mirror(x0 + width - 1 + i, w)]
                    }
                }
            }
            return base
        }
    }

    // The default upsampling weights: kWeights2, kWeights4 and kWeights8 of
    // CustomTransformData::VisitFields in image_metadata.cc.
    private val DEFAULT_WEIGHTS_2 = floatArrayOf(
        -0.01716200f, -0.03452303f, -0.04022174f, -0.02921014f, -0.00624645f,
        0.14111091f, 0.28896755f, 0.00278718f, -0.01610267f, 0.56661550f,
        0.03777607f, -0.01986694f, -0.03144731f, -0.01185068f, -0.00213539f,
    )
    private val DEFAULT_WEIGHTS_4 = floatArrayOf(
        -0.02419067f, -0.03491987f, -0.03693351f, -0.03094285f, -0.00529785f,
        -0.01663432f, -0.03556863f, -0.03888905f, -0.03516850f, -0.00989469f,
        0.23651958f, 0.33392945f, -0.01073543f, -0.01313181f, -0.03556694f,
        0.13048175f, 0.40103025f, 0.03951150f, -0.02077584f, 0.46914198f,
        -0.00209270f, -0.01484589f, -0.04064806f, 0.18942530f, 0.56279892f,
        0.06674400f, -0.02335494f, -0.03551682f, -0.00754830f, -0.02267919f,
        -0.02363578f, 0.00315804f, -0.03399098f, -0.01359519f, -0.00091653f,
        -0.00335467f, -0.01163294f, -0.01610294f, -0.00974088f, -0.00191622f,
        -0.01095446f, -0.03198464f, -0.04455121f, -0.02799790f, -0.00645912f,
        0.06390599f, 0.22963888f, 0.00630981f, -0.01897349f, 0.67537268f,
        0.08483369f, -0.02534994f, -0.02205197f, -0.01667999f, -0.00384443f,
    )
    private val DEFAULT_WEIGHTS_8 = floatArrayOf(
        -0.02928613f, -0.03706353f, -0.03783812f, -0.03324558f, -0.00447632f,
        -0.02519406f, -0.03752601f, -0.03901508f, -0.03663285f, -0.00646649f,
        -0.02066407f, -0.03838633f, -0.04002101f, -0.03900035f, -0.00901973f,
        -0.01626393f, -0.03954148f, -0.04046620f, -0.03979621f, -0.01224485f,
        0.29895328f, 0.35757708f, -0.02447552f, -0.01081748f, -0.04314594f,
        0.23903219f, 0.41119301f, -0.00573046f, -0.01450239f, -0.04246845f,
        0.17567618f, 0.45220643f, 0.02287757f, -0.01936783f, -0.03583255f,
        0.11572472f, 0.47416733f, 0.06284440f, -0.02685066f, 0.42720050f,
        -0.02248939f, -0.01155273f, -0.04562755f, 0.28689496f, 0.49093869f,
        -0.00007891f, -0.01545926f, -0.04562659f, 0.21238920f, 0.53980934f,
        0.03369474f, -0.02070211f, -0.03866988f, 0.14229550f, 0.56593398f,
        0.08045181f, -0.02888298f, -0.03680918f, -0.00542229f, -0.02920477f,
        -0.02788574f, -0.02118180f, -0.03942402f, -0.00775547f, -0.02433614f,
        -0.03193943f, -0.02030828f, -0.04044014f, -0.01074016f, -0.01930822f,
        -0.03620399f, -0.01974125f, -0.03919545f, -0.01456093f, -0.00045072f,
        -0.00360110f, -0.01020207f, -0.01231907f, -0.00638988f, -0.00071592f,
        -0.00279122f, -0.00957115f, -0.01288327f, -0.00730937f, -0.00107783f,
        -0.00210156f, -0.00890705f, -0.01317668f, -0.00813895f, -0.00153491f,
        -0.02128481f, -0.04173044f, -0.04831487f, -0.03293190f, -0.00525260f,
        -0.01720322f, -0.04052736f, -0.05045706f, -0.03607317f, -0.00738030f,
        -0.01341764f, -0.03965629f, -0.05151616f, -0.03814886f, -0.01005819f,
        0.18968273f, 0.33063684f, -0.01300105f, -0.01372950f, -0.04017465f,
        0.13727832f, 0.36402234f, 0.01027890f, -0.01832107f, -0.03365072f,
        0.08734506f, 0.38194295f, 0.04338228f, -0.02525993f, 0.56408126f,
        0.00458352f, -0.01648227f, -0.04887868f, 0.24585519f, 0.62026135f,
        0.04314807f, -0.02213737f, -0.04158014f, 0.16637289f, 0.65027023f,
        0.09621636f, -0.03101388f, -0.04082742f, -0.00904519f, -0.02790922f,
        -0.02117818f, 0.00798662f, -0.03995711f, -0.01243427f, -0.02231705f,
        -0.02946266f, 0.00992055f, -0.03600283f, -0.01684920f, -0.00111684f,
        -0.00411204f, -0.01297130f, -0.01723725f, -0.01022545f, -0.00165306f,
        -0.00313110f, -0.01218016f, -0.01763266f, -0.01125620f, -0.00231663f,
        -0.01374149f, -0.03797620f, -0.05142937f, -0.03117307f, -0.00581914f,
        -0.01064003f, -0.03608089f, -0.05272168f, -0.03375670f, -0.00795586f,
        0.09628104f, 0.27129991f, -0.00353779f, -0.01734151f, -0.03153981f,
        0.05686230f, 0.28500998f, 0.02230594f, -0.02374955f, 0.68214326f,
        0.05018048f, -0.02320852f, -0.04383616f, 0.18459474f, 0.71517975f,
        0.10805613f, -0.03263677f, -0.03637639f, -0.01394373f, -0.02511203f,
        -0.01728636f, 0.05407331f, -0.02867568f, -0.01893131f, -0.00240854f,
        -0.00446511f, -0.01636187f, -0.02377053f, -0.01522848f, -0.00333334f,
        -0.00819975f, -0.02964169f, -0.04499287f, -0.02745350f, -0.00612408f,
        0.02727416f, 0.19446600f, 0.00159832f, -0.02232473f, 0.74982506f,
        0.11452620f, -0.03348048f, -0.01605681f, -0.02070339f, -0.00458223f,
    )
}
