// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/splines.cc, splines.h,
// base/fast_math-inl.h and render_pipeline/stage_splines.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Splines: smooth curves through control points, with a colour and a thickness that change
 * along the curve. They are added to the three colour planes, for thin lines that the
 * transform coding draws badly.
 */
internal class JxlSplines private constructor(
    private val count: Int,
    /** The first control point of each spline. */
    private val startX: IntArray,
    private val startY: IntArray,
    /** Makes the quantization of every coefficient finer when positive, coarser when negative. */
    private val quantAdjust: Int,
    /** Where the later control points of each spline start in [deltas], with one entry past the last spline. */
    private val pointStart: IntArray,
    /** The later control points, x then y, each as the change of the step from the point before. */
    private val deltas: IntArray,
    /** For each spline 4 times 32 quantized DCT coefficients: of X, Y, B, then of the thickness. */
    private val dct: IntArray,
) {
    /**
     * Adds the splines to [color] in place. [yToX] and [yToB] are the frame's base colour
     * correlations, [xsizeUpsampled] and [ysizeUpsampled] the frame's size after upsampling,
     * which sets how much area the splines may cover.
     *
     * libjxl draws up to 1024 times the frame's area and 2^32 samples more, which a small
     * file can ask for and which takes minutes. This stops at [WORK_AREA_FACTOR] times the
     * frame's area and 2^27 samples more.
     */
    fun apply(
        color: Array<JxlPlane>,
        yToX: Double,
        yToB: Double,
        xsizeUpsampled: Int,
        ysizeUpsampled: Int,
    ) {
        if (color.size != 3) jxlFail("splines need three colour planes")
        val w = color[0].w
        val h = color[0].h
        if (color[1].w != w || color[1].h != h || color[2].w != w || color[2].h != h) {
            jxlFail("splines need colour planes of one size")
        }
        val imageSize = xsizeUpsampled.toLong() * ysizeUpsampled
        val areaLimit = if (imageSize >= 1L shl 32) 1L shl 42 else minOf(1024 * imageSize + (1L shl 32), 1L shl 42)
        val ytox = f32(yToX)
        val ytob = f32(yToB)

        var most = 0
        for (s in 0 until count) most = maxOf(most, pointStart[s + 1] - pointStart[s])
        val curve = Curve(most + 1)

        // libjxl checks every spline before it draws the first one.
        var area = 0L
        for (s in 0 until count) {
            area += dequantize(s, curve, ytox, ytob, areaLimit)
            if (area > areaLimit) jxlFail("splines cover too large an area")
            if (curve.repeated) jxlFail("spline with the same control point twice in a row")
        }
        if (area > minOf(WORK_AREA_FACTOR * imageSize + (1L shl 27), 1L shl 31)) {
            jxlFail("splines cover too large an area")
        }

        val counter = Counter()
        val painter = Painter(color[0].data, color[1].data, color[2].data, w, h)
        for (s in 0 until count) {
            dequantize(s, curve, ytox, ytob, areaLimit)
            // A spline of one point has no length, and libjxl draws nothing for it.
            if (curve.n < 2) continue
            // The colour at a point depends on the length of the whole curve, so the curve is
            // walked twice: once to measure it, once to draw. This holds no list of points.
            counter.count = 0
            walk(curve, counter)
            val arcLength = f32(f32((counter.count - 2).toDouble()) + counter.last)
            if (!(arcLength > 0.0)) continue
            painter.start(curve.coef, arcLength)
            walk(curve, painter)
        }
    }

    /**
     * Undoes the quantization of spline [s] into [into], and gives the area libjxl estimates
     * for it. libjxl's QuantizedSpline::Dequantize, with each step rounded to single
     * precision as there.
     */
    private fun dequantize(s: Int, into: Curve, yToX: Double, yToB: Double, areaLimit: Long): Long {
        var x = startX[s]
        var y = startY[s]
        // Slot 0 is kept for the point the curve is extended by.
        into.x[1] = x.toDouble()
        into.y[1] = y.toDouble()
        var n = 1
        var dx = 0
        var dy = 0
        var manhattan = 0L
        var repeated = false
        for (p in pointStart[s] until pointStart[s + 1]) {
            // A step is under 2^23 and its change under 2^30, so the sums fit an Int.
            dx += deltas[2 * p]
            dy += deltas[2 * p + 1]
            manhattan += abs(dx).toLong() + abs(dy)
            if (manhattan > areaLimit) jxlFail("spline is too long")
            if (outOfRange(dx.toLong(), dy.toLong())) jxlFail("spline point out of bounds")
            x += dx
            y += dy
            if (outOfRange(x.toLong(), y.toLong())) jxlFail("spline point out of bounds")
            if (dx == 0 && dy == 0) repeated = true
            n++
            into.x[n] = x.toDouble()
            into.y[n] = y.toDouble()
        }
        into.n = n
        into.repeated = repeated

        val invQuant = if (quantAdjust >= 0) {
            f32(1.0 / f32(1.0 + f32(0.125 * f32(quantAdjust.toDouble()))))
        } else {
            f32(1.0 - f32(0.125 * f32(quantAdjust.toDouble())))
        }
        val coef = into.coef
        val base = s * COEFS
        for (c in 0 until 4) {
            for (i in 0 until 32) {
                val q = f32(dct[base + c * 32 + i].toDouble())
                val scaled = if (i == 0) f32(q * SQRT_HALF) else q
                coef[c * 32 + i] = f32(f32(scaled * CHANNEL_WEIGHT[c]) * invQuant)
            }
        }
        // X and B are coded as what is left after the part that follows Y is taken out.
        for (i in 0 until 32) {
            coef[i] = f32(coef[i] + f32(yToX * coef[32 + i]))
            coef[64 + i] = f32(coef[64 + i] + f32(yToB * coef[32 + i]))
        }

        // The estimate of the area. libjxl sums in unsigned 64 bits, which a Long does bit for bit.
        var c0 = 0L
        var c1 = 0L
        var c2 = 0L
        for (i in 0 until 32) {
            c0 += ceil(f32(invQuant * f32(abs(dct[base + i]).toDouble()))).toLong()
            c1 += ceil(f32(invQuant * f32(abs(dct[base + 32 + i]).toDouble()))).toLong()
            c2 += ceil(f32(invQuant * f32(abs(dct[base + 64 + i]).toDouble()))).toLong()
        }
        c0 += ceil(abs(yToX)).toLong() * c1
        c2 += ceil(abs(yToB)).toLong() * c1
        val maxColor = maxOf(c1.toULong(), c0.toULong(), c2.toULong()) + 1uL
        val exact = (maxColor and (maxColor - 1uL)) == 0uL
        val log = if (maxColor == 0uL) 0 else 63 - maxColor.countLeadingZeroBits() + (if (exact) 0 else 1)
        val logColor = maxOf(1, log).toLong()

        val perLength = f32(f32(areaLimit.toDouble()) / logColor.toDouble())
        val weightLimit = ceil(f32(sqrt(f32(perLength / f32(maxOf(1L, manhattan).toDouble())))))
        var width = 0L
        for (i in 0 until 32) {
            val weightF = ceil(f32(invQuant * f32(abs(dct[base + 96 + i]).toDouble())))
            val weight = minOf(weightLimit, maxOf(1.0, weightF)).toLong()
            width += weight * weight * logColor
        }
        return width * manhattan
    }

    /**
     * Gives [sink] the points of [curve] one pixel of length apart. libjxl's
     * DrawCentripetalCatmullRomSpline and ForEachEquallySpacedPoint, joined so that no point
     * is stored.
     */
    private fun walk(curve: Curve, sink: Sink) {
        val px = curve.x
        val py = curve.y
        val n = curve.n
        // One more point at each end, as far out as the nearest control point is in.
        px[0] = f32(px[1] + f32(px[1] - px[2]))
        py[0] = f32(py[1] + f32(py[1] - py[2]))
        px[n + 1] = f32(px[n] + f32(px[n] - px[n - 1]))
        py[n + 1] = f32(py[n] + f32(py[n] - py[n - 1]))

        // The state of ForEachEquallySpacedPoint: the last point passed, and the length walked since the last point given.
        var prevX = px[1]
        var prevY = py[1]
        var walked = 0.0
        sink.point(prevX, prevY, 1.0)

        // Walks from the last point to (nx, ny), and gives a point at each whole pixel of length.
        fun feed(nx: Double, ny: Double) {
            var guard = -1L
            while (true) {
                val vx = f32(nx - prevX)
                val vy = f32(ny - prevY)
                val toNext = f32(sqrt(f32(f32(vx * vx) + f32(vy * vy))))
                if (!(f32(walked + toNext) >= 1.0)) {
                    walked = f32(walked + toNext)
                    prevX = nx
                    prevY = ny
                    return
                }
                // Each turn moves one pixel nearer. Single precision cannot move one pixel at
                // very large coordinates, where libjxl would never end.
                if (guard < 0) guard = if (toNext < 1e9) 4 * toNext.toLong() + 16 else 1
                if (--guard == 0L) jxlFail("spline too far out to be drawn")
                val k = f32(f32(1.0 - walked) / toNext)
                prevX = f32(prevX + f32(k * vx))
                prevY = f32(prevY + f32(k * vy))
                walked = 0.0
                sink.point(prevX, prevY, 1.0)
            }
        }

        feed(prevX, prevY)
        // Each turn draws the curve between points 1 and 2 of four control points in a row.
        for (start in 0 until n - 1) {
            if (start > 0) feed(px[start + 1], py[start + 1])
            val d0 = knot(px, py, start)
            val d1 = knot(px, py, start + 1)
            val d2 = knot(px, py, start + 2)
            val t1 = d0
            val t2 = f32(t1 + d1)
            for (i in 1 until POINTS_PER_SEGMENT) {
                val tt = f32(d0 + f32(i / POINTS_PER_SEGMENT.toDouble() * d1))
                val k0 = f32(tt / d0)
                val k1 = f32(f32(tt - t1) / d1)
                val k2 = f32(f32(tt - t2) / d2)
                val a0x = mix(px[start], px[start + 1], k0)
                val a0y = mix(py[start], py[start + 1], k0)
                val a1x = mix(px[start + 1], px[start + 2], k1)
                val a1y = mix(py[start + 1], py[start + 2], k1)
                val a2x = mix(px[start + 2], px[start + 3], k2)
                val a2y = mix(py[start + 2], py[start + 3], k2)
                val j0 = f32(tt / f32(d0 + d1))
                val j1 = f32(f32(tt - t1) / f32(d1 + d2))
                val b0x = mix(a0x, a1x, j0)
                val b0y = mix(a0y, a1y, j0)
                val b1x = mix(a1x, a2x, j1)
                val b1y = mix(a1y, a2y, j1)
                feed(mix(b0x, b1x, k1), mix(b0y, b1y, k1))
            }
        }
        feed(px[n], py[n])
        sink.point(prevX, prevY, walked)
    }

    /** Receives the points of a curve, each with the length of curve it stands for. */
    private fun interface Sink {
        fun point(x: Double, y: Double, length: Double)
    }

    private class Counter : Sink {
        var count = 0L
        var last = 0.0
        override fun point(x: Double, y: Double, length: Double) {
            count++
            last = length
        }
    }

    /** One spline with its quantization undone. libjxl's Spline. */
    private class Curve(points: Int) {
        /** The control points in slots 1 to [n], with room for one more at each end. */
        val x = DoubleArray(points + 2)
        val y = DoubleArray(points + 2)
        var n = 0
        var repeated = false
        val coef = DoubleArray(COEFS)
    }

    /** Draws each point of a curve as a round blur. libjxl's SegmentsFromPoints, ComputeSegments and DrawSegment. */
    private class Painter(
        private val planeX: FloatArray,
        private val planeY: FloatArray,
        private val planeB: FloatArray,
        private val w: Int,
        private val h: Int,
    ) : Sink {
        private var coef = DoubleArray(0)
        private var invArcLength = 0.0
        private var k = 0L

        fun start(coef: DoubleArray, arcLength: Double) {
            this.coef = coef
            invArcLength = f32(1.0 / arcLength)
            k = 0
        }

        override fun point(x: Double, y: Double, length: Double) {
            val along = f32(f32(k.toDouble()) * invArcLength)
            k++
            val t = f32(31.0 * (if (along < 1.0) along else 1.0))
            val sigma = continuousIdct(coef, 96, t)
            // libjxl skips a point whose thickness or strength is not a usable number.
            if (!sigma.isFinite() || sigma == 0.0 || !length.isFinite()) return
            val invSigma = f32(1.0 / sigma)
            if (!invSigma.isFinite()) return
            val cx = continuousIdct(coef, 0, t)
            val cy = continuousIdct(coef, 32, t)
            val cb = continuousIdct(coef, 64, t)

            // The distance at which the blur falls under 10^-5. The colour counts as at least 0.01.
            var maxColor = MIN_COLOR
            val ax = abs(f32(cx * length))
            val ay = abs(f32(cy * length))
            val ab = abs(f32(cb * length))
            if (maxColor < ax) maxColor = ax
            if (maxColor < ay) maxColor = ay
            if (maxColor < ab) maxColor = ab
            val reach = f32(sqrt(f32(f32(-2.0 * sigma) * sigma) * (LN_DISTANCE - f32(ln(maxColor)))))
            if (reach.isNaN()) return

            val y0 = maxOf(0L, roundHalfAway(f32(y - reach)))
            val y1 = minOf(h - 1L, roundHalfAway(f32(y + reach)))
            val x0 = maxOf(0L, roundHalfAway(f32(x - reach)))
            val x1 = minOf(w - 1L, roundHalfAway(f32(x + reach)))
            if (y0 > y1 || x0 > x1) return

            val strength = f32(f32(0.25 * sigma) * length)
            for (row in y0.toInt()..y1.toInt()) {
                val dy = f32(row - y)
                val dy2 = dy * dy
                var at = row * w + x0.toInt()
                for (col in x0.toInt()..x1.toInt()) {
                    val dx = col - x
                    val half = sqrt(dx * dx + dy2) * 0.5
                    val f = fastErf((half + ONE_OVER_2S2) * invSigma) - fastErf((half - ONE_OVER_2S2) * invSigma)
                    val local = strength * f * f
                    planeX[at] = (planeX[at] + cx * local).toFloat()
                    planeY[at] = (planeY[at] + cy * local).toFloat()
                    planeB[at] = (planeB[at] + cb * local).toFloat()
                    at++
                }
            }
        }
    }

    companion object {
        // The contexts of the splines' entropy code.
        private const val CTX_QUANT_ADJUST = 0
        private const val CTX_START = 1
        private const val CTX_NUM_SPLINES = 2
        private const val CTX_NUM_POINTS = 3
        private const val CTX_POINTS = 4
        private const val CTX_DCT = 5
        private const val NUM_CONTEXTS = 6

        private const val MAX_CONTROL_POINTS = 1 shl 20
        private const val POSITION_LIMIT = 1L shl 23
        private const val DELTA_LIMIT = 1 shl 30
        private const val COEFS = 4 * 32
        private const val POINTS_PER_SEGMENT = 16

        /** How many times the frame's area the splines may cover, by libjxl's estimate. */
        private const val WORK_AREA_FACTOR = 64L

        /** [x] rounded to single precision, as each step of libjxl's float arithmetic is. */
        private fun f32(x: Double): Double = x.toFloat().toDouble()

        // libjxl's constants, which are single precision.
        private val SQRT2 = f32(1.41421356237)
        private val SQRT_HALF = f32(0.70710678118)
        private val CHANNEL_WEIGHT = doubleArrayOf(f32(0.0042), f32(0.075), f32(0.07), f32(0.3333))
        private val MIN_COLOR = f32(0.01)
        private val ONE_OVER_2S2 = f32(0.353553391)
        private val LN_DISTANCE = ln(0.1) * 5.0

        private val PI2 = f32(PI * 2.0)
        private val PI2_INV = f32(0.5 / PI)
        private val PI_HALF = f32(PI / 2.0)
        private val PI_F = f32(PI)
        private val COS_C0 = f32(1.68179268)
        private val COS_C2 = f32(-0.84087373)
        private val COS_C4 = f32(0.06960438)
        private val COS_SQRT2 = f32(1.414213562)
        private val IDCT_ANGLE = DoubleArray(32) { f32(PI / 32 * it) }

        private val ERF_A = f32(7.77394369e-02)
        private val ERF_B = f32(2.05260015e-04)
        private val ERF_C = f32(2.32120216e-01)
        private val ERF_D = f32(2.77820801e-01)

        private fun outOfRange(x: Long, y: Long): Boolean =
            x >= POSITION_LIMIT || x <= -POSITION_LIMIT || y >= POSITION_LIMIT || y <= -POSITION_LIMIT

        /** The square root of the distance from control point [k] to the next, which spaces a centripetal curve. */
        private fun knot(px: DoubleArray, py: DoubleArray, k: Int): Double {
            val dx = f32(px[k + 1] - px[k])
            val dy = f32(py[k + 1] - py[k])
            return f32(sqrt(f32(sqrt(dx * dx + dy * dy))))
        }

        /** The point [k] of the way from [a] to [b]; [k] may lie outside 0 to 1. */
        private fun mix(a: Double, b: Double, k: Double): Double = f32(a + f32(k * f32(b - a)))

        /** Rounds to the nearest whole number, a half away from zero, as C's llround. Too large a value gives the nearest Long. */
        private fun roundHalfAway(v: Double): Long = (if (v >= 0) floor(v + 0.5) else ceil(v - 0.5)).toLong()

        /** libjxl's FastCosf: the cosine, to about 7e-5, with the steps and rounding of libjxl. */
        private fun fastCos(x: Double): Double {
            val turns = f32(floor(f32(x * PI2_INV)) * PI2)
            val inTurn = f32(x - turns)
            val folded = minOf(inTurn, f32(PI2 - inTurn))
            val above = folded >= PI_HALF
            val quarter = if (above) f32(PI_F - folded) else folded
            val xs = quarter * 0.25
            val x2 = f32(xs * xs)
            val x4 = f32(x2 * x2)
            // A short series for the cosine of a quarter of the angle, scaled so that doubling the angle twice is cheap.
            val scaled = f32(x4 * COS_C4 + f32(x2 * COS_C2 + COS_C0))
            val doubled = f32(scaled * scaled - COS_SQRT2)
            val cos = f32(doubled * doubled - 1.0)
            return if (above) -cos else cos
        }

        /** libjxl's FastErff: the error function, to about 7e-4. */
        private fun fastErf(x: Double): Double {
            val a = abs(x)
            val d = (((a * ERF_A + ERF_B) * a + ERF_C) * a + ERF_D) * a + 1.0
            // 1 - 1 / d^4
            val inv = 1.0 / (d * d)
            val result = 1.0 - inv * inv
            return if (x <= 0.0) -result else result
        }

        /**
         * The value at position [t], 0 to 31, of the 32 samples that the DCT coefficients at
         * [at] in [coef] stand for, with the samples joined by cosines. libjxl's ContinuousIDCT.
         */
        private fun continuousIdct(coef: DoubleArray, at: Int, t: Double): Double {
            val tAndHalf = f32(t + 0.5)
            var result = 0.0
            for (i in 0 until 32) {
                val cos = fastCos(f32(IDCT_ANGLE[i] * tAndHalf))
                result = f32(SQRT2 * f32(coef[at + i] * cos) + result)
            }
            return result
        }

        /** Reads the splines of a frame of [numPixels] pixels. */
        fun read(br: JxlBitReader, numPixels: Long): JxlSplines {
            val code = JxlCode.read(br, NUM_CONTEXTS)
            val reader = JxlSymbolReader(code, br)
            val maxPoints = minOf(MAX_CONTROL_POINTS.toLong(), numPixels / 2)
            val coded = reader.read(CTX_NUM_SPLINES).toLong() and 0xFFFFFFFFL
            if (coded + 1 > maxPoints) jxlFail("too many splines")
            val count = (coded + 1).toInt()

            val startX = IntArray(count)
            val startY = IntArray(count)
            var lastX = 0L
            var lastY = 0L
            for (i in 0 until count) {
                var x = reader.read(CTX_START).toLong() and 0xFFFFFFFFL
                var y = reader.read(CTX_START).toLong() and 0xFFFFFFFFL
                if (i != 0) {
                    x = unpackSigned(x.toInt()) + lastX
                    y = unpackSigned(y.toInt()) + lastY
                }
                if (outOfRange(x, y)) jxlFail("spline point out of bounds")
                startX[i] = x.toInt()
                startY[i] = y.toInt()
                lastX = x
                lastY = y
            }
            val quantAdjust = unpackSigned(reader.read(CTX_QUANT_ADJUST))

            val pointStart = IntArray(count + 1)
            var deltas = IntArray(64)
            var dct = IntArray(minOf(count, 16) * COEFS)
            // The first point of each spline counts toward the limit too.
            var totalPoints = count.toLong()
            var used = 0
            for (s in 0 until count) {
                val points = reader.read(CTX_NUM_POINTS).toLong() and 0xFFFFFFFFL
                if (points > maxPoints) jxlFail("too many spline control points")
                totalPoints += points
                if (totalPoints > maxPoints) jxlFail("too many spline control points")
                pointStart[s] = used
                val end = used + points.toInt()
                if (end * 2 > deltas.size) deltas = deltas.copyOf(maxOf(end * 2, deltas.size * 2))
                while (used < end) {
                    val dx = unpackSigned(reader.read(CTX_POINTS))
                    val dy = unpackSigned(reader.read(CTX_POINTS))
                    if (dx >= DELTA_LIMIT || dx <= -DELTA_LIMIT || dy >= DELTA_LIMIT || dy <= -DELTA_LIMIT) {
                        jxlFail("spline control point too far from the one before")
                    }
                    deltas[2 * used] = dx
                    deltas[2 * used + 1] = dy
                    used++
                }
                if ((s + 1) * COEFS > dct.size) dct = dct.copyOf(minOf(dct.size * 2L, count.toLong() * COEFS).toInt())
                for (i in 0 until COEFS) {
                    val v = unpackSigned(reader.read(CTX_DCT))
                    if (v == Int.MIN_VALUE) jxlFail("spline coefficient out of range")
                    dct[s * COEFS + i] = v
                }
            }
            pointStart[count] = used
            reader.checkFinalState()
            return JxlSplines(count, startX, startY, quantAdjust, pointStart, deltas, dct)
        }
    }
}
