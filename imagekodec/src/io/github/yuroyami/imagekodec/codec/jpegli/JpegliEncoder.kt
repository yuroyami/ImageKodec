// Altered Kotlin port of the encoder of google/jpegli at commit
// 031a0077f5799a6041004267fc12b956c1f52a20: lib/jpegli/encode.cc, quant.cc,
// adaptive_quantization.cc, color_transform.cc, downsample.cc, dct-inl.h,
// encode_streaming.cc, bitstream.cc and bit_writer.cc, for the settings of
// jpegli_set_defaults followed by jpegli_set_quality.
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jpegli

import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.codec.JpegEncoder
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A JPEG encoder ported from Jpegli (#133), with the settings its library gives an RGB image
 * by default: `jpegli_set_defaults` and then `jpegli_set_quality`, which is what pyjpegli's
 * `encode` and libjpeg-style callers get. That is a baseline sequential YCbCr file with 4:2:0
 * chroma, Jpegli's quantization matrices scaled by the distance its quality maps to, adaptive
 * quantization (a per-block zero bias, from a masking field computed on the luma), and the
 * example Huffman tables of the JPEG standard. No JFIF header: Jpegli writes none by default.
 *
 * Alterations, each for the same output on every target: the pixel path runs in Double and
 * keeps its planes in FloatArray, where jpegli runs in float32 SIMD with fused multiply-adds,
 * so a coefficient can round the other way now and then; the quantization tables and the
 * values derived from them are computed in float32, step by step through [f32], so the DQT
 * segment is jpegli's to the bit. `FastLog2f` and `FastPow2f` keep jpegli's rational
 * approximations, with their range reduction done on Double bits.
 */
internal object JpegliEncoder {

    private const val MAX_SIDE = 65535

    /** float32 rounding of [x]; Kotlin/JS keeps a Float as a double, so this goes through the bits. */
    private fun f32(x: Double): Double = Float.fromBits(x.toFloat().toBits()).toDouble()

    /** A Float literal as float32; through the bits, since Kotlin/JS may keep a literal's decimal value. */
    private fun f(x: Float): Double = Float.fromBits(x.toBits()).toDouble()

    /** C's `std::round`: halves away from zero. */
    private fun roundAway(x: Double): Int = if (x < 0) -floor(-x + 0.5).toInt() else floor(x + 0.5).toInt()

    /** Highway's `Round`: halves to even, as kotlin.math.round does. */
    private fun roundEven(x: Double): Int = kotlin.math.round(x).toInt()

    // ---- quant.cc ----

    private val GLOBAL_SCALE_YCBCR = f(1.73966010f)
    private val GLOBAL_SCALE_420 = f(1.22f)
    private val DIST0 = f(1.5f)

    /** `jpegli_quality_to_distance`, in float32. */
    fun qualityToDistance(quality: Int): Double = when {
        quality >= 100 -> f(0.01f)
        quality >= 30 -> f32(f(0.1f) + f32((100 - quality) * f(0.09f)))
        else -> {
            val a = f32(f32(f32(53.0 / 3000.0) * quality) * quality)
            val b = f32(f32(23.0 / 20.0) * quality)
            f32(f32(a - b) + 25.0)
        }
    }

    /** float32 `std::pow(float, float)`: the libm result, correctly rounded in nearly all cases, rounded to float32. */
    private fun powf(x: Double, y: Double): Double = f32(x.pow(y))

    private fun distanceToScale(distance: Double, k: Int): Double {
        if (distance < DIST0) return distance
        val exp = JpegliTables.EXPONENT[k]
        val mul = f32(DIST0.pow(1.0 - exp))
        return max(f32(0.5 * distance), f32(mul * powf(distance, exp)))
    }

    private fun scaleToDistance(scale: Double, k: Int): Double {
        if (scale < DIST0) return scale
        val exp = f32(1.0 / JpegliTables.EXPONENT[k])
        val mul = f32(DIST0.pow(1.0 - exp))
        return min(f32(2.0 * scale), f32(mul * powf(scale, exp)))
    }

    /**
     * `SetQuantMatrices` for YCbCr 4:2:0 with `add_two_chroma_tables` false: table 0 for Y,
     * table 1, built from the Cr matrix, for both chroma components. Natural order.
     */
    fun quantTables(distance: Double): Array<IntArray> {
        val globalScale = f32(GLOBAL_SCALE_YCBCR * GLOBAL_SCALE_420)
        return Array(2) { table ->
            val base = if (table == 0) 0 else 2 * 64
            IntArray(64) { k ->
                var scale = f32(globalScale * distanceToScale(distance, k))
                if (table > 0) scale = f32(scale * JpegliTables.RESCALE_420[k])
                roundAway(f32(scale * JpegliTables.BASE_QUANT_MATRIX_YCBCR[base + k])).coerceIn(1, 255)
            }
        }
    }

    /** `QuantValsToDistance`: the distance the written tables stand for, which sets the zero bias. */
    private fun quantValsToDistance(tables: Array<IntArray>): Double {
        val distMax = 10000.0
        var lo = 0.0
        var hi = distMax
        for (c in 0 until 3) {
            val q = if (c == 0) 0 else 1
            for (k in 0 until 64) {
                var dmin = 0.0
                var dmax = distMax
                val invq = f32(f32(1.0 / JpegliTables.BASE_QUANT_MATRIX_YCBCR[q * 64 + k]) / GLOBAL_SCALE_YCBCR)
                val qval = tables[q][k]
                if (qval > 1) dmin = scaleToDistance(f32((qval - 0.5) * invq), k)
                if (qval < 255) dmax = scaleToDistance(f32((qval + 0.5) * invq), k)
                if (dmin <= hi) lo = max(dmin, lo)
                if (dmax >= lo) hi = min(hi, dmax)
            }
        }
        return when {
            lo == 0.0 -> hi
            hi == distMax -> lo
            else -> f32(0.5 * f32(lo + hi))
        }
    }

    /** `InitQuantizer` with adaptive quantization on: the zero-bias multipliers and offsets of each component, natural order. */
    private fun zeroBias(tables: Array<IntArray>): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val distance = quantValsToDistance(tables)
        val mix0 = f32((distance - 1.0) / 2.0).coerceIn(0.0, 1.0)
        val mix1 = f32(1.0 - mix0)
        val mul = Array(3) { c ->
            DoubleArray(64) { k ->
                f32(f32(mix0 * JpegliTables.ZERO_BIAS_MUL_LQ[c * 64 + k]) + f32(mix1 * JpegliTables.ZERO_BIAS_MUL_HQ[c * 64 + k]))
            }
        }
        val offset = Array(3) { c -> DoubleArray(64) { k -> if (k == 0) 0.0 else JpegliTables.ZERO_BIAS_OFFSET_AC[c] } }
        return mul to offset
    }

    // ---- color_transform.cc: full-range BT.601, JFIF clause 7 ----

    private val KR = f(0.299f)
    private val KG = f(0.587f)
    private val KB = f(0.114f)
    private val DIFF_R = f32(f(0.701f) + KR)
    private val DIFF_B = f32(f(0.886f) + KB)
    private val NORM_R = f32(1.0 / f32(f(0.701f) + f32(KG + KB)))
    private val NORM_B = f32(1.0 / f32(KR + f32(KG + f(0.886f))))

    // ---- adaptive_quantization.cc ----

    private val K_SG_MUL = f(226.0480446705883f)
    private val K_INV_LOG2E = f(0.6931471805599453f)
    private val K_SG_RET_MUL = f32(f32(f32(1.0 / f(73.377132366608819f)) * f(18.6580932135f)) * K_INV_LOG2E)
    private val K_SG_V_OFFSET = f(7.14672470003f)
    private val K_INPUT_SCALING_F = f32(1.0 / 255.0)
    private val K_NUM_OFFSET = f32(f32(f(1e-2f) / K_INPUT_SCALING_F) / K_INPUT_SCALING_F)
    private val K_NUM_MUL = f32(f32(K_SG_RET_MUL * 3) * K_SG_MUL)
    private val K_V_OFFSET = f32(f32(f32(K_SG_V_OFFSET * K_INV_LOG2E) + f(1e-2f)) / K_INPUT_SCALING_F)
    private val K_DEN_MUL = f32(f32(f32(K_INV_LOG2E * K_SG_MUL) * K_INPUT_SCALING_F) * K_INPUT_SCALING_F)
    private val MATCH_GAMMA_OFFSET = f32(0.019 / K_INPUT_SCALING_F)
    private val GAMMA_BIAS = f32(f(0.16f) / K_INPUT_SCALING_F)
    private val GAMMA_SCALE = f32(K_INPUT_SCALING_F / 64.0)
    private val K_GAMMA = f32(f(-0.15526878023684174f) * K_INV_LOG2E)
    private val SUM_COEFF = f32(f32(f(-2.0052193233688884f) * K_INPUT_SCALING_F) / 112.0)
    private val SQRT_MASK_MUL = f32(sqrt(f32(f(211.50759899638012f) * 1e8)))

    private fun ratioOfDerivatives(value: Double, invert: Boolean): Double {
        val v = max(value, 0.0)
        val v2 = v * v
        val num = K_NUM_MUL * v2 + K_NUM_OFFSET
        val den = (K_DEN_MUL * v) * v2 + K_V_OFFSET
        return if (invert) num / den else den / num
    }

    private val TWO_THIRDS_BITS = (2.0 / 3.0).toRawBits()

    /** `FastLog2f`: its 2,2 rational approximation of log1p(x) / ln 2 after range reduction to [2/3, 4/3). */
    private fun fastLog2(x: Double): Double {
        val bits = x.toRawBits()
        val e = (bits - TWO_THIRDS_BITS) shr 52
        val m = Double.fromBits(bits - (e shl 52)) - 1.0
        val p = (f(7.4245873327820566E-01f) * m + f(1.4287160470083755E+00f)) * m + f(-1.8503833400518310E-06f)
        val q = (f(1.7409343003366853E-01f) * m + f(1.0096718572241148E+00f)) * m + f(9.9032814277590719E-01f)
        return p / q + e.toDouble()
    }

    /** `FastPow2f`. */
    private fun fastPow2(x: Double): Double {
        val fl = floor(x)
        val scale = Double.fromBits((fl.toLong() + 1023) shl 52)
        val frac = x - fl
        var num = frac + f(1.01749063e+01f)
        num = num * frac + f(4.88687798e+01f)
        num = num * frac + f(9.85506591e+01f)
        num *= scale
        var den = frac * f(2.10242958e-01f) + f(-2.22328856e-02f)
        den = den * frac + f(-1.94414990e+01f)
        den = den * frac + f(9.85506633e+01f)
        return num / den
    }

    private fun computeMask(outVal: Double): Double {
        val kOffset3 = f(2.1925739705298404f)
        val v1 = max(outVal * f(0.74760422233706747f), f(1e-3f))
        val v2 = 1.0 / (v1 + f(305.04035728311436f))
        val v3 = 1.0 / (v1 * v1 + kOffset3)
        val v4 = 1.0 / (v1 * v1 + f32(0.25 * kOffset3))
        return f(-0.74174993f) + (f(3.2353257320940401f) * v4 + (f(12.906028311180409f) * v2 + f(5.0220313103171232f) * v3))
    }

    private fun maskingSqrt(v: Double): Double = 0.25 * sqrt(v * SQRT_MASK_MUL + 28.0)

    /**
     * The luma plane with a border of one sample on every side: [stride] samples a row, sample
     * (x, y) at `(y + 1) * stride + x + 1`, for x in -1..[padW] and y in -1..[padH].
     */
    private class Plane(val padW: Int, val padH: Int) {
        val stride = padW + 2
        val data = FloatArray(stride * (padH + 2))
        fun at(x: Int, y: Int): Int = (y + 1) * stride + x + 1
        fun get(x: Int, y: Int): Double = data[at(x, y)].toDouble()
    }

    /**
     * `ComputeAdaptiveQuantField` for every iMCU row at once: the per-block strength of the zero
     * bias, from the luma, for the [wib] by [hib] luma blocks. jpegli computes it a row of
     * MCUs at a time through ring buffers that never wrap onto a row still in use, so whole
     * planes give the same field.
     */
    private fun quantField(y: Plane, wib: Int, hib: Int, totalRows: Int, yQuant01: Int): Array<DoubleArray> {
        val xsize = wib * 8
        val peW = xsize / 4
        val peRows = y.padH / 4
        // pre_erosion, with a border column on each side and a border row above and below.
        val peStride = peW + 2
        val pre = FloatArray(peStride * (peRows + 2))
        fun peAt(x: Int, row: Int) = (row + 1) * peStride + x + 1
        val diff = FloatArray(xsize)
        for (r in 0 until totalRows) {
            var y0 = r * 16
            var ylen = 16
            if (y0 == 0) ylen += 4 else y0 += 4
            if (r + 1 == totalRows) ylen -= 4
            for (iy in 0 until ylen) {
                val yy = y0 + iy
                for (x in 0 until xsize) {
                    val v = y.get(x, yy)
                    val base = 0.25 * ((y.get(x + 1, yy) + y.get(x - 1, yy)) + (y.get(x, yy - 1) + y.get(x, yy + 1)))
                    val gammacv = ratioOfDerivatives(v + MATCH_GAMMA_OFFSET, invert = false)
                    var d = gammacv * (v - base)
                    d = min(d * d, f(0.2f))
                    d = maskingSqrt(d)
                    diff[x] = (if ((iy and 3) != 0) diff[x] + d else d).toFloat()
                }
                if (iy % 4 == 3) {
                    val row = y0 / 4 + iy / 4
                    for (x in 0 until peW) {
                        pre[peAt(x, row)] = ((diff[x * 4].toDouble() + diff[x * 4 + 1] + diff[x * 4 + 2] + diff[x * 4 + 3]) * 0.25).toFloat()
                    }
                    pre[peAt(-1, row)] = pre[peAt(0, row)]
                    pre[peAt(peW, row)] = pre[peAt(peW - 1, row)]
                }
            }
        }
        pre.copyInto(pre, peAt(-1, -1), peAt(-1, 0), peAt(-1, 0) + peStride)
        pre.copyInto(pre, peAt(-1, peRows), peAt(-1, peRows - 1), peAt(-1, peRows - 1) + peStride)

        val field = Array(totalRows * 2) { DoubleArray(wib) }
        val tmp = Array(2) { DoubleArray(peW) }
        val nine = DoubleArray(9)
        for (row in 0 until peRows) {
            val out = tmp[row and 1]
            for (x in 0 until peW) {
                var n = 0
                for (dy in -1..1) for (dx in -1..1) nine[n++] = pre[peAt(x + dx, row + dy)].toDouble()
                nine.sort()
                out[x] = (0.125 * nine[0] + 0.075 * nine[1]) + (0.06 * nine[2] + 0.05 * nine[3])
            }
            if (row % 2 == 1) {
                val prev = tmp[(row - 1) and 1]
                val dst = field[row / 2]
                for (bx in 0 until wib) dst[bx] = out[2 * bx] + out[2 * bx + 1] + prev[2 * bx] + prev[2 * bx + 1]
            }
        }

        val acQuant = f(0.841f)
        val baseLevel = f32(f(0.48f) * acQuant)
        var dampen = 1.0
        if (yQuant01 >= 9) dampen = max(0.0, f32(1.0 - f32(f32(yQuant01 - 9.0) / 56.0)))
        val mul = f32(acQuant * dampen)
        val add = f32(f32(1.0 - dampen) * baseLevel)
        for (by in 0 until field.size) {
            val dst = field[by]
            for (bx in 0 until wib) {
                var out = computeMask(dst[bx])
                out = hfModulation(y, bx * 8, by * 8, out)
                out = gammaModulation(y, bx * 8, by * 8, out)
                dst[bx] = fastPow2(out * f(1.442695041f)) * mul + add
            }
            for (bx in 0 until wib) dst[bx] = max(0.0, f(0.6f) / dst[bx] - 1.0)
        }
        return field
    }

    private fun hfModulation(y: Plane, x: Int, yy: Int, outVal: Double): Double {
        var sum = 0.0
        for (dy in 0 until 8) {
            val next = if (dy == 7) yy + dy else yy + dy + 1
            for (dx in 0 until 8) {
                val p = y.get(x + dx, yy + dy)
                if (dx < 7) sum += abs(p - y.get(x + dx + 1, yy + dy))
                sum += abs(p - y.get(x + dx, next))
            }
        }
        return sum * SUM_COEFF + outVal
    }

    private fun gammaModulation(y: Plane, x: Int, yy: Int, outVal: Double): Double {
        var ratio = 0.0
        for (dy in 0 until 8) for (dx in 0 until 8) ratio += ratioOfDerivatives(y.get(x + dx, yy + dy) + GAMMA_BIAS, invert = true)
        return K_GAMMA * fastLog2(ratio * GAMMA_SCALE) + outVal
    }

    // ---- dct-inl.h ----

    private val WC4 = doubleArrayOf(f(0.541196100146197f), f(1.3065629648763764f))
    private val WC8 = doubleArrayOf(f(0.5097955791041592f), f(0.6013448869350453f), f(0.8999762231364156f), f(2.5629154477415055f))
    private val SQRT2 = f(1.41421356237f)

    /** `DCT1DImpl<n>` on [n] values of [mem] from [at] apart by [step], unscaled. */
    private fun dct1d(mem: DoubleArray, at: Int, step: Int, n: Int, tmp: DoubleArray, tmpAt: Int) {
        if (n == 1) return
        if (n == 2) {
            val a = mem[at]
            val b = mem[at + step]
            mem[at] = a + b
            mem[at + step] = a - b
            return
        }
        val half = n / 2
        for (i in 0 until half) {
            val a = mem[at + i * step]
            val b = mem[at + (n - 1 - i) * step]
            tmp[tmpAt + i] = a + b
            tmp[tmpAt + half + i] = (a - b) * (if (n == 8) WC8[i] else WC4[i])
        }
        dct1d(tmp, tmpAt, 1, half, tmp, tmpAt + n)
        dct1d(tmp, tmpAt + half, 1, half, tmp, tmpAt + n)
        val odd = tmpAt + half
        tmp[odd] = tmp[odd] * SQRT2 + tmp[odd + 1]
        for (i in 1 until half - 1) tmp[odd + i] = tmp[odd + i] + tmp[odd + i + 1]
        for (i in 0 until half) {
            mem[at + 2 * i * step] = tmp[tmpAt + i]
            mem[at + (2 * i + 1) * step] = tmp[odd + i]
        }
    }

    /** `TransformFromPixels`: the 2D DCT of [block], natural order, each pass scaled by 1/8, so DC is the mean. */
    private fun dct(block: DoubleArray, tmp: DoubleArray) {
        for (x in 0 until 8) dct1d(block, x, 8, 8, tmp, 0)
        for (i in 0 until 64) block[i] *= 0.125
        for (y in 0 until 8) dct1d(block, y * 8, 1, 8, tmp, 0)
        for (i in 0 until 64) block[i] *= 0.125
    }

    // ---- bit_writer.cc ----

    private class BitWriter(val out: ByteArrayBuilder) {
        private var buffer = 0L
        private var count = 0

        fun write(nbits: Int, bits: Int) {
            if (nbits == 0) return
            buffer = (buffer shl nbits) or (bits.toLong() and ((1L shl nbits) - 1))
            count += nbits
            while (count >= 8) {
                count -= 8
                val c = ((buffer ushr count) and 0xFF).toInt()
                out.append(c.toByte())
                if (c == 0xFF) out.append(0)
            }
            buffer = buffer and ((1L shl count) - 1)
        }

        /** `JumpToByteBoundary`: pad with one bits. */
        fun flush() {
            if (count > 0) write(8 - count, (1 shl (8 - count)) - 1)
        }
    }

    private class Huffman(counts: IntArray, values: IntArray) {
        val code = IntArray(256)
        val size = IntArray(256)

        init {
            var c = 0
            var k = 0
            for (len in 1..16) {
                repeat(counts[len]) {
                    code[values[k]] = c
                    size[values[k]] = len
                    c++
                    k++
                }
                c = c shl 1
            }
        }
    }

    private fun category(v: Int): Int {
        var a = if (v < 0) -v else v
        var n = 0
        while (a != 0) { a = a shr 1; n++ }
        return n
    }

    private fun writeBlock(bw: BitWriter, zz: IntArray, dcDiff: Int, dc: Huffman, ac: Huffman) {
        val dn = category(dcDiff)
        bw.write(dc.size[dn], dc.code[dn])
        bw.write(dn, if (dcDiff < 0) dcDiff - 1 else dcDiff)
        var run = 0
        for (k in 1 until 64) {
            val v = zz[k]
            if (v == 0) { run++; continue }
            while (run >= 16) {
                bw.write(ac.size[0xF0], ac.code[0xF0])
                run -= 16
            }
            val n = category(v)
            val sym = (run shl 4) or n
            bw.write(ac.size[sym], ac.code[sym])
            bw.write(n, if (v < 0) v - 1 else v)
            run = 0
        }
        if (run > 0) bw.write(ac.size[0], ac.code[0])
    }

    // ---- encode.cc, encode_streaming.cc ----

    /** jpegli's encode of [bitmap] at [quality] 1..100, with the raw APPn [segments] after SOI. */
    fun encode(bitmap: KiteBitmap, quality: Int, segments: ByteArray?): ByteArray {
        require(quality in 1..100) { "JPEG quality must be 1..100, was $quality" }
        val width = bitmap.width
        val height = bitmap.height
        require(width <= MAX_SIDE && height <= MAX_SIDE) {
            "JPEG stores each side in 16 bits, so ${width}x$height is larger than $MAX_SIDE"
        }
        val tables = quantTables(qualityToDistance(quality))
        val (zbMul, zbOffset) = zeroBias(tables)
        val quantMul = Array(3) { c -> DoubleArray(64) { k -> f32(8.0 / tables[if (c == 0) 0 else 1][k]) } }

        // Y at 2x2, Cb and Cr at 1x1: the iMCU is 16 by 16, and the planes are padded to whole iMCUs.
        val mcusX = (width + 15) / 16
        val mcusY = (height + 15) / 16
        val padW = mcusX * 16
        val padH = mcusY * 16
        val lumaWib = (width + 7) / 8
        val lumaHib = (height + 7) / 8
        val cw = padW / 2
        val ch = padH / 2
        val y = Plane(padW, padH)
        val cb = FloatArray(cw * ch)
        val cr = FloatArray(cw * ch)

        // ReadInputRow, the color transform and PadInputBuffer: a row is padded to the iMCU width
        // and one sample past it with its last sample, and the rows past the image repeat its last.
        val argb = bitmap.argb
        val rowCb = Array(2) { FloatArray(padW + 1) }
        val rowCr = Array(2) { FloatArray(padW + 1) }
        for (row in 0 until padH) {
            val src = min(row, height - 1)
            val pair = row and 1
            val base = src * width
            for (x in 0..padW) {
                val p = argb[base + min(x, width - 1)]
                val r = ((p ushr 16) and 0xFF).toDouble()
                val g = ((p ushr 8) and 0xFF).toDouble()
                val b = (p and 0xFF).toDouble()
                val yv = r * KR + (g * KG + b * KB)
                y.data[y.at(x, row)] = yv.toFloat()
                rowCb[pair][x] = ((b * DIFF_B - yv) * NORM_B + 128.0).toFloat()
                rowCr[pair][x] = ((r * DIFF_R - yv) * NORM_R + 128.0).toFloat()
            }
            y.data[y.at(-1, row)] = y.data[y.at(0, row)]
            if (pair == 1) {
                // Downsample2x2, on the float32 samples the planes hold.
                val out = (row / 2) * cw
                for (x in 0 until cw) {
                    cb[out + x] = box(rowCb, x)
                    cr[out + x] = box(rowCr, x)
                }
            }
        }
        y.data.copyInto(y.data, y.at(-1, -1), y.at(-1, 0), y.at(-1, 0) + y.stride)
        y.data.copyInto(y.data, y.at(-1, padH), y.at(-1, padH - 1), y.at(-1, padH - 1) + y.stride)

        val field = quantField(y, lumaWib, lumaHib, mcusY, tables[0][1])

        val out = ByteArrayBuilder(width * height / 4 + 1024)
        out.append(0xFF.toByte()); out.append(0xD8.toByte())
        if (segments != null) out.append(segments)
        writeHeaders(out, width, height, tables)

        val dcY = Huffman(JpegEncoder.STD_DC_LUM_NRCODES, JpegEncoder.STD_DC_LUM_VALUES)
        val acY = Huffman(JpegEncoder.STD_AC_LUM_NRCODES, JpegEncoder.STD_AC_LUM_VALUES)
        val dcC = Huffman(JpegEncoder.STD_DC_CHR_NRCODES, JpegEncoder.STD_DC_CHR_VALUES)
        val acC = Huffman(JpegEncoder.STD_AC_CHR_NRCODES, JpegEncoder.STD_AC_CHR_VALUES)
        val bw = BitWriter(out)
        val lastDc = IntArray(3)
        val block = DoubleArray(64)
        val tmp = DoubleArray(32)
        val coeffs = IntArray(64)
        val zz = IntArray(64)

        fun codeBlock(c: Int, aq: Double, load: (DoubleArray) -> Unit) {
            load(block)
            dct(block, tmp)
            val qmc = quantMul[c]
            val zbm = zbMul[c]
            val zbo = zbOffset[c]
            for (k in 0 until 64) {
                val q = block[k] * qmc[k]
                coeffs[k] = if (abs(q) >= zbo[k] + zbm[k] * aq) roundEven(q) else 0
            }
            val dc = (block[0] - 128.0) * qmc[0]
            coeffs[0] = if (abs(dc - lastDc[c]) < zbo[0] + aq * zbm[0]) lastDc[c] else roundAway(dc)
            for (k in 0 until 64) zz[k] = coeffs[ZIGZAG_NATURAL[k]]
            val diff = coeffs[0] - lastDc[c]
            lastDc[c] = coeffs[0]
            if (c == 0) writeBlock(bw, zz, diff, dcY, acY) else writeBlock(bw, zz, diff, dcC, acC)
        }

        for (my in 0 until mcusY) {
            for (mx in 0 until mcusX) {
                for (iy in 0 until 2) {
                    for (ix in 0 until 2) {
                        val bx = mx * 2 + ix
                        val by = my * 2 + iy
                        if (bx >= lumaWib || by >= lumaHib) {
                            // A block past the component's edge: a zero DC difference and an end of block.
                            bw.write(dcY.size[0], dcY.code[0])
                            bw.write(acY.size[0], acY.code[0])
                            continue
                        }
                        codeBlock(0, field[by][bx]) { b ->
                            for (r in 0 until 8) for (x in 0 until 8) b[r * 8 + x] = y.get(bx * 8 + x, by * 8 + r)
                        }
                    }
                }
                val aq = field[my * 2][mx * 2]
                for (c in 1..2) {
                    val plane = if (c == 1) cb else cr
                    codeBlock(c, aq) { b ->
                        for (r in 0 until 8) for (x in 0 until 8) b[r * 8 + x] = plane[(my * 8 + r) * cw + mx * 8 + x].toDouble()
                    }
                }
            }
        }
        bw.flush()
        out.append(0xFF.toByte()); out.append(0xD9.toByte())
        return out.toByteArray()
    }

    /** `Downsample2x2` of column pair [x] of the two rows in [rows]. */
    private fun box(rows: Array<FloatArray>, x: Int): Float =
        (0.25 * ((rows[0][2 * x].toDouble() + rows[0][2 * x + 1]) + (rows[1][2 * x].toDouble() + rows[1][2 * x + 1]))).toFloat()

    /** natural index of each zigzag position: `kJPEGNaturalOrder`. */
    private val ZIGZAG_NATURAL = IntArray(64).also { order ->
        var i = 0
        for (s in 0 until 15) {
            val range = if (s % 2 == 0) (min(s, 7) downTo max(0, s - 7)) else (max(0, s - 7)..min(s, 7))
            for (row in range) order[i++] = row * 8 + (s - row)
        }
    }

    /** `EncodeDQT`, `EncodeSOF` and `EncodeDHT` and the scan header, as jpegli lays them out for this frame. */
    private fun writeHeaders(out: ByteArrayBuilder, width: Int, height: Int, tables: Array<IntArray>) {
        fun b(vararg v: Int) { for (x in v) out.append(x.toByte()) }
        b(0xFF, 0xDB, 0, 2 + 2 * 65)
        for (t in 0 until 2) {
            b(t)
            for (j in 0 until 64) b(tables[t][ZIGZAG_NATURAL[j]])
        }
        b(0xFF, 0xC0, 0, 17, 8, height ushr 8, height, width ushr 8, width, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        val dht = listOf(
            Triple(0x00, JpegEncoder.STD_DC_LUM_NRCODES, JpegEncoder.STD_DC_LUM_VALUES),
            Triple(0x10, JpegEncoder.STD_AC_LUM_NRCODES, JpegEncoder.STD_AC_LUM_VALUES),
            Triple(0x01, JpegEncoder.STD_DC_CHR_NRCODES, JpegEncoder.STD_DC_CHR_VALUES),
            Triple(0x11, JpegEncoder.STD_AC_CHR_NRCODES, JpegEncoder.STD_AC_CHR_VALUES),
        )
        val length = 2 + dht.sumOf { 17 + it.third.size }
        b(0xFF, 0xC4, length ushr 8, length)
        for ((id, counts, values) in dht) {
            b(id)
            for (i in 1..16) b(counts[i])
            for (v in values) b(v)
        }
        b(0xFF, 0xDA, 0, 12, 3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0)
    }
}
