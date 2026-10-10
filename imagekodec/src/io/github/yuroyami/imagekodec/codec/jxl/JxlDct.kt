// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_transforms-inl.h, dct-inl.h,
// dct_scales.h, dct_block-inl.h and transpose-inl.h).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The inverse transforms of VarDCT, the lossy mode of JPEG XL: they turn the dequantized
 * coefficients of a block into samples. [JxlAcStrategy] names the transforms and describes
 * the layout of a block's coefficients.
 *
 * libjxl scales its DCT so that the first coefficient is the mean of the samples. The
 * inverse of `n` values is `sample(i) = sum over k of coefficient(k) * b(k, i)`, with
 * `b(0, i) = 1` and `b(k, i) = sqrt(2) * cos((i + 1/2) * k * pi / n)`. A block applies it
 * across and then down, with no other factor.
 *
 * libjxl computes in 32-bit floats. This code computes in doubles and rounds once, when it
 * stores a result, so that every target gives the same samples.
 */
internal object JxlDct {
    // How many columns one pass of the 1-D transform handles together. It bounds the working space.
    private const val LANES = 16

    /** The size [transformToPixels] and [lowestFrequenciesFromLf] need of their scratch array, for any strategy. */
    const val SCRATCH = JxlAcStrategy.MAX_COEFF_AREA + 3 * JxlAcStrategy.MAX_BLOCK_DIM * LANES

    /** The scratch size that is enough for the strategies in the bit set [usedStrategies], which can be far below [SCRATCH]. */
    fun scratchSize(usedStrategies: Int): Int {
        var size = 0
        for (s in 0 until JxlAcStrategy.COUNT) {
            if (usedStrategies and (1 shl s) == 0) continue
            val cx = JxlAcStrategy.coveredBlocksX(s)
            val cy = JxlAcStrategy.coveredBlocksY(s)
            size = maxOf(size, 64 * cx * cy + 3 * 8 * maxOf(cx, cy) * LANES)
        }
        return size
    }

    private val SQRT2 = sqrt(2.0)

    // libjxl WcMultipliers, by the base 2 logarithm of the size n: 1 / (2 * cos((i + 1/2) * pi / n)).
    private val WC = Array(9) { log ->
        val n = 1 shl log
        DoubleArray(n / 2) { i -> 0.5 / cos((i + 0.5) * PI / n) }
    }
    private val W4_0 = WC[2][0]
    private val W4_1 = WC[2][1]
    private val W8_0 = WC[3][0]
    private val W8_1 = WC[3][1]
    private val W8_2 = WC[3][2]
    private val W8_3 = WC[3][3]

    /**
     * The forward DCT of n LF samples that gives the n lowest frequencies of a DCT of 8 * n
     * samples, by the base 2 logarithm of n. Row k is libjxl's scaled DCT, `b(k, i) / n`, times
     * DCTTotalResampleScale<n, 8n>(k) of dct_scales.h. That factor undoes what the mean over 8
     * samples does to frequency k: `cos(k pi / 16n) * cos(k pi / 8n) * cos(k pi / 4n)`.
     */
    private val LLF_BASIS = Array(6) { log ->
        val n = 1 shl log
        DoubleArray(n * n) { at ->
            val k = at / n
            val i = at % n
            val big = 8.0 * n
            val resample = 1.0 / (cos(k * PI / (2 * big)) * cos(k * PI / big) * cos(k * PI / (big / 2)))
            val basis = if (k == 0) 1.0 else SQRT2 * cos((i + 0.5) * k * PI / n)
            resample * basis / n
        }
    }

    /**
     * libjxl TransformToPixels: the inverse transform of one block. [coefficients] holds the block's
     * dequantized coefficients in libjxl's layout from [coeffOffset], 64 for each block the strategy
     * covers, and is not changed. The samples go to [pixels] from [pixelOffset], rows [pixelStride]
     * apart. [scratch] is working space of [SCRATCH] values, or of [scratchSize]. [strategy] must be valid.
     */
    fun transformToPixels(
        strategy: Int,
        coefficients: FloatArray,
        coeffOffset: Int,
        pixels: FloatArray,
        pixelOffset: Int,
        pixelStride: Int,
        scratch: DoubleArray,
    ) {
        when (strategy) {
            JxlAcStrategy.DCT -> idct8x8(coefficients, coeffOffset, pixels, pixelOffset, pixelStride, scratch)
            JxlAcStrategy.IDENTITY -> identity(coefficients, coeffOffset, pixels, pixelOffset, pixelStride)
            JxlAcStrategy.DCT2X2 -> dct2x2(coefficients, coeffOffset, pixels, pixelOffset, pixelStride, scratch)
            JxlAcStrategy.DCT4X4 -> dct4x4(coefficients, coeffOffset, pixels, pixelOffset, pixelStride, scratch)
            JxlAcStrategy.DCT4X8 -> dct4x8(coefficients, coeffOffset, pixels, pixelOffset, pixelStride, scratch)
            JxlAcStrategy.DCT8X4 -> dct8x4(coefficients, coeffOffset, pixels, pixelOffset, pixelStride, scratch)
            JxlAcStrategy.AFV0, JxlAcStrategy.AFV1, JxlAcStrategy.AFV2, JxlAcStrategy.AFV3 ->
                afv(strategy - JxlAcStrategy.AFV0, coefficients, coeffOffset, pixels, pixelOffset, pixelStride, scratch)
            else -> idctLarge(
                8 * JxlAcStrategy.coveredBlocksY(strategy), 8 * JxlAcStrategy.coveredBlocksX(strategy),
                coefficients, coeffOffset, pixels, pixelOffset, pixelStride, scratch,
            )
        }
    }

    /**
     * libjxl LowestFrequenciesFromDC: makes the lowest frequencies of a block (LLF) from the LF
     * image, which libjxl calls DC and which holds the mean of every 8 by 8 block. It reads one
     * LF sample for each block the strategy covers from [lf] at [lfOffset], rows [lfStride]
     * apart, and writes the LLF corner of [coefficients]. After the inverse transform, the mean
     * of each 8 by 8 block is its LF sample again when every other coefficient is zero.
     */
    fun lowestFrequenciesFromLf(
        strategy: Int,
        lf: FloatArray,
        lfOffset: Int,
        lfStride: Int,
        coefficients: FloatArray,
        coeffOffset: Int,
        scratch: DoubleArray,
    ) {
        val cx = JxlAcStrategy.coveredBlocksX(strategy)
        val cy = JxlAcStrategy.coveredBlocksY(strategy)
        if (cx * cy == 1) {
            coefficients[coeffOffset] = lf[lfOffset]
            return
        }
        val across = LLF_BASIS[cx.countTrailingZeroBits()]
        val down = LLF_BASIS[cy.countTrailingZeroBits()]
        for (y in 0 until cy) {
            val row = lfOffset + y * lfStride
            for (h in 0 until cx) {
                var sum = 0.0
                for (x in 0 until cx) sum += across[h * cx + x] * lf[row + x]
                scratch[y * cx + h] = sum
            }
        }
        // The long side of the transform runs along the rows of the coefficient array.
        val wide = cx > cy
        val stride = 8 * maxOf(cx, cy)
        for (v in 0 until cy) {
            for (h in 0 until cx) {
                var sum = 0.0
                for (y in 0 until cy) sum += down[v * cy + y] * scratch[y * cx + h]
                coefficients[coeffOffset + if (wide) v * stride + h else h * stride + v] = sum.toFloat()
            }
        }
    }

    /**
     * libjxl IDCT1DImpl for [m] columns at once: the inverse DCT of [n] values, by the recursion
     * of Perera and Liu that libjxl uses. Input i of column j is at `src + i * srcRow + j`, and
     * output i of column j goes to `dst + i * dstRow + j * dstLane`; the two may be the same
     * place. Sizes above 8 use [t] from [tmp]: fewer than `2 * n * m` values.
     */
    private fun idct1d(n: Int, m: Int, t: DoubleArray, src: Int, srcRow: Int, dst: Int, dstRow: Int, dstLane: Int, tmp: Int) {
        when (n) {
            4 -> {
                val w0 = W4_0
                val w1 = W4_1
                for (j in 0 until m) {
                    val p = src + j
                    val i0 = t[p]
                    val i1 = t[p + srcRow]
                    val i2 = t[p + 2 * srcRow]
                    val i3 = t[p + 3 * srcRow]
                    val a0 = i0 + i2
                    val a1 = i0 - i2
                    val b0 = i1 * SQRT2
                    val b1 = i1 + i3
                    val c0 = (b0 + b1) * w0
                    val c1 = (b0 - b1) * w1
                    val q = dst + j * dstLane
                    t[q] = a0 + c0
                    t[q + dstRow] = a1 + c1
                    t[q + 2 * dstRow] = a1 - c1
                    t[q + 3 * dstRow] = a0 - c0
                }
            }
            8 -> {
                val w40 = W4_0
                val w41 = W4_1
                val w80 = W8_0
                val w81 = W8_1
                val w82 = W8_2
                val w83 = W8_3
                for (j in 0 until m) {
                    val p = src + j
                    val i0 = t[p]
                    val i1 = t[p + srcRow]
                    val i2 = t[p + 2 * srcRow]
                    val i3 = t[p + 3 * srcRow]
                    val i4 = t[p + 4 * srcRow]
                    val i5 = t[p + 5 * srcRow]
                    val i6 = t[p + 6 * srcRow]
                    val i7 = t[p + 7 * srcRow]
                    // The even inputs: an inverse of size 4.
                    val a0 = i0 + i4
                    val a1 = i0 - i4
                    val b0 = i2 * SQRT2
                    val b1 = i2 + i6
                    val c0 = (b0 + b1) * w40
                    val c1 = (b0 - b1) * w41
                    val e0 = a0 + c0
                    val e1 = a1 + c1
                    val e2 = a1 - c1
                    val e3 = a0 - c0
                    // The odd inputs: sums of neighbours (libjxl BTranspose), then an inverse of size 4.
                    val q0 = i1 * SQRT2
                    val q1 = i3 + i1
                    val q2 = i5 + i3
                    val q3 = i7 + i5
                    val f0 = q0 + q2
                    val f1 = q0 - q2
                    val g0 = q1 * SQRT2
                    val g1 = q1 + q3
                    val h0 = (g0 + g1) * w40
                    val h1 = (g0 - g1) * w41
                    val o0 = (f0 + h0) * w80
                    val o1 = (f1 + h1) * w81
                    val o2 = (f1 - h1) * w82
                    val o3 = (f0 - h0) * w83
                    val q = dst + j * dstLane
                    t[q] = e0 + o0
                    t[q + dstRow] = e1 + o1
                    t[q + 2 * dstRow] = e2 + o2
                    t[q + 3 * dstRow] = e3 + o3
                    t[q + 4 * dstRow] = e3 - o3
                    t[q + 5 * dstRow] = e2 - o2
                    t[q + 6 * dstRow] = e1 - o1
                    t[q + 7 * dstRow] = e0 - o0
                }
            }
            else -> {
                val half = n ushr 1
                val odd = tmp + half * m
                val next = tmp + n * m
                // The even inputs: an inverse of half the size, read where they are.
                idct1d(half, m, t, src, 2 * srcRow, tmp, m, 1, next)
                // The odd inputs: each but the first takes the one before it (libjxl BTranspose), then the same inverse.
                for (j in 0 until m) t[odd + j] = t[src + srcRow + j] * SQRT2
                for (i in 1 until half) {
                    val from = src + (2 * i + 1) * srcRow
                    val to = odd + i * m
                    for (j in 0 until m) t[to + j] = t[from + j] + t[from - 2 * srcRow + j]
                }
                idct1d(half, m, t, odd, m, odd, m, 1, next)
                val w = WC[n.countTrailingZeroBits()]
                for (i in 0 until half) {
                    val wi = w[i]
                    val even = tmp + i * m
                    val oddRow = odd + i * m
                    var lo = dst + i * dstRow
                    var hi = dst + (n - 1 - i) * dstRow
                    for (j in 0 until m) {
                        val a = t[even + j]
                        val b = wi * t[oddRow + j]
                        t[lo] = a + b
                        t[hi] = a - b
                        lo += dstLane
                        hi += dstLane
                    }
                }
            }
        }
    }

    /**
     * libjxl ComputeScaledIDCT for the transforms above 8 by 8. It first undoes the DCT across the
     * block, for a few vertical frequencies at a time, and leaves `t[v * cols + x]`.
     */
    private fun idctLarge(rows: Int, cols: Int, c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val area = rows * cols
        var first = 0
        while (first < rows) {
            val m = minOf(LANES, rows - first)
            // The coefficient of frequencies (v, h) goes to t[area + h * m + (v - first)].
            if (rows >= cols) {
                for (h in 0 until cols) {
                    val from = co + h * rows + first
                    val to = area + h * m
                    for (j in 0 until m) t[to + j] = c[from + j].toDouble()
                }
            } else {
                for (j in 0 until m) {
                    val from = co + (first + j) * cols
                    for (h in 0 until cols) t[area + h * m + j] = c[from + h].toDouble()
                }
            }
            idct1d(cols, m, t, area, m, first * cols, 1, cols, area + cols * m)
            first += m
        }
        idctDown(rows, cols, p, po, ps, t)
    }

    /** The second half of a block's inverse: undoes the DCT down the columns of `t[v * cols + x]` and stores the samples. */
    private fun idctDown(rows: Int, cols: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val area = rows * cols
        var first = 0
        while (first < cols) {
            val m = minOf(LANES, cols - first)
            idct1d(rows, m, t, first, cols, first, cols, 1, area)
            first += m
        }
        for (y in 0 until rows) {
            val from = y * cols
            val to = po + y * ps
            for (x in 0 until cols) p[to + x] = t[from + x].toFloat()
        }
    }

    /**
     * libjxl ComputeScaledIDCT for a part of an 8 by 8 block: 4 by 4, 4 by 8 or 8 by 4. The caller
     * puts the coefficient of frequencies (v, h) at `t[rows * cols + h * rows + v]`.
     */
    private fun idctSmall(rows: Int, cols: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val area = rows * cols
        idct1d(cols, rows, t, area, rows, 0, 1, cols, 2 * area)
        idctDown(rows, cols, p, po, ps, t)
    }

    /**
     * The 8 by 8 DCT, which most blocks use: the size 8 case of [idct1d] written out for both
     * directions, so that nothing is copied. The coefficient of frequencies (v, h) is at `h * 8 + v`.
     */
    private fun idct8x8(c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val w40 = W4_0
        val w41 = W4_1
        val w80 = W8_0
        val w81 = W8_1
        val w82 = W8_2
        val w83 = W8_3
        // Across, for each vertical frequency v: leaves t[v * 8 + x].
        for (v in 0 until 8) {
            val k = co + v
            val i0 = c[k].toDouble()
            val i1 = c[k + 8].toDouble()
            val i2 = c[k + 16].toDouble()
            val i3 = c[k + 24].toDouble()
            val i4 = c[k + 32].toDouble()
            val i5 = c[k + 40].toDouble()
            val i6 = c[k + 48].toDouble()
            val i7 = c[k + 56].toDouble()
            val a0 = i0 + i4
            val a1 = i0 - i4
            val b0 = i2 * SQRT2
            val b1 = i2 + i6
            val c0 = (b0 + b1) * w40
            val c1 = (b0 - b1) * w41
            val e0 = a0 + c0
            val e1 = a1 + c1
            val e2 = a1 - c1
            val e3 = a0 - c0
            val q0 = i1 * SQRT2
            val q1 = i3 + i1
            val q2 = i5 + i3
            val q3 = i7 + i5
            val f0 = q0 + q2
            val f1 = q0 - q2
            val g0 = q1 * SQRT2
            val g1 = q1 + q3
            val h0 = (g0 + g1) * w40
            val h1 = (g0 - g1) * w41
            val o0 = (f0 + h0) * w80
            val o1 = (f1 + h1) * w81
            val o2 = (f1 - h1) * w82
            val o3 = (f0 - h0) * w83
            val q = v * 8
            t[q] = e0 + o0
            t[q + 1] = e1 + o1
            t[q + 2] = e2 + o2
            t[q + 3] = e3 + o3
            t[q + 4] = e3 - o3
            t[q + 5] = e2 - o2
            t[q + 6] = e1 - o1
            t[q + 7] = e0 - o0
        }
        // Down, for each column x of samples.
        for (x in 0 until 8) {
            val i0 = t[x]
            val i1 = t[x + 8]
            val i2 = t[x + 16]
            val i3 = t[x + 24]
            val i4 = t[x + 32]
            val i5 = t[x + 40]
            val i6 = t[x + 48]
            val i7 = t[x + 56]
            val a0 = i0 + i4
            val a1 = i0 - i4
            val b0 = i2 * SQRT2
            val b1 = i2 + i6
            val c0 = (b0 + b1) * w40
            val c1 = (b0 - b1) * w41
            val e0 = a0 + c0
            val e1 = a1 + c1
            val e2 = a1 - c1
            val e3 = a0 - c0
            val q0 = i1 * SQRT2
            val q1 = i3 + i1
            val q2 = i5 + i3
            val q3 = i7 + i5
            val f0 = q0 + q2
            val f1 = q0 - q2
            val g0 = q1 * SQRT2
            val g1 = q1 + q3
            val h0 = (g0 + g1) * w40
            val h1 = (g0 - g1) * w41
            val o0 = (f0 + h0) * w80
            val o1 = (f1 + h1) * w81
            val o2 = (f1 - h1) * w82
            val o3 = (f0 - h0) * w83
            var q = po + x
            p[q] = (e0 + o0).toFloat()
            q += ps
            p[q] = (e1 + o1).toFloat()
            q += ps
            p[q] = (e2 + o2).toFloat()
            q += ps
            p[q] = (e3 + o3).toFloat()
            q += ps
            p[q] = (e3 - o3).toFloat()
            q += ps
            p[q] = (e2 - o2).toFloat()
            q += ps
            p[q] = (e1 - o1).toFloat()
            q += ps
            p[q] = (e0 - o0).toFloat()
        }
    }

    /**
     * IDENTITY keeps the samples of each 4 by 4 quarter as differences from the sample at (1, 1)
     * of the quarter. The quarter's mean is where that sample's difference would be.
     */
    private fun identity(c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int) {
        val b00 = c[co].toDouble()
        val b01 = c[co + 1].toDouble()
        val b10 = c[co + 8].toDouble()
        val b11 = c[co + 9].toDouble()
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                val mean = when (y * 2 + x) {
                    0 -> b00 + b01 + b10 + b11
                    1 -> b00 + b01 - b10 - b11
                    2 -> b00 - b01 + b10 - b11
                    else -> b00 - b01 - b10 + b11
                }
                val from = co + y * 8 + x
                var residual = 0.0
                for (iy in 0 until 4) {
                    for (ix in 0 until 4) {
                        if (ix != 0 || iy != 0) residual += c[from + iy * 16 + ix * 2]
                    }
                }
                val anchor = mean - residual * (1.0 / 16)
                val to = po + y * 4 * ps + x * 4
                for (iy in 0 until 4) {
                    for (ix in 0 until 4) p[to + iy * ps + ix] = (c[from + iy * 16 + ix * 2] + anchor).toFloat()
                }
                // The place of the first sample holds the quarter's mean, so its difference is kept at (1, 1).
                p[to] = (c[from + 16 + 2] + anchor).toFloat()
                p[to + ps + 1] = anchor.toFloat()
            }
        }
    }

    /** DCT2X2 is three rounds of 2 by 2 sums and differences (a Hadamard transform): on 1, then 2, then 4 values a side. */
    private fun dct2x2(c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        for (i in 0 until 64) t[i] = c[co + i].toDouble()
        idct2TopBlock(2, t)
        idct2TopBlock(4, t)
        idct2TopBlock(8, t)
        for (y in 0 until 8) {
            for (x in 0 until 8) p[po + y * ps + x] = t[y * 8 + x].toFloat()
        }
    }

    /** libjxl IDCT2TopBlock on the corner of [size] by [size] values of the 8 by 8 block at the start of [t]. */
    private fun idct2TopBlock(size: Int, t: DoubleArray) {
        val num = size / 2
        for (y in 0 until num) {
            for (x in 0 until num) {
                val c00 = t[y * 8 + x]
                val c01 = t[y * 8 + num + x]
                val c10 = t[(y + num) * 8 + x]
                val c11 = t[(y + num) * 8 + num + x]
                val to = 64 + y * 16 + x * 2
                t[to] = c00 + c01 + c10 + c11
                t[to + 1] = c00 + c01 - c10 - c11
                t[to + 8] = c00 - c01 + c10 - c11
                t[to + 9] = c00 - c01 - c10 + c11
            }
        }
        for (y in 0 until size) t.copyInto(t, y * 8, 64 + y * 8, 64 + y * 8 + size)
    }

    /** DCT4X4: four 4 by 4 DCTs, with their coefficients interleaved. Their four means are a 2 by 2 sum and difference. */
    private fun dct4x4(c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val b00 = c[co].toDouble()
        val b01 = c[co + 1].toDouble()
        val b10 = c[co + 8].toDouble()
        val b11 = c[co + 9].toDouble()
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                val from = co + y * 8 + x
                for (iy in 0 until 4) {
                    for (ix in 0 until 4) t[16 + iy * 4 + ix] = c[from + iy * 16 + ix * 2].toDouble()
                }
                t[16] = when (y * 2 + x) {
                    0 -> b00 + b01 + b10 + b11
                    1 -> b00 + b01 - b10 - b11
                    2 -> b00 - b01 + b10 - b11
                    else -> b00 - b01 - b10 + b11
                }
                idctSmall(4, 4, p, po + y * 4 * ps + x * 4, ps, t)
            }
        }
    }

    /** DCT4X8: an upper and a lower DCT of 4 rows by 8 columns, with their coefficients on alternate rows. */
    private fun dct4x8(c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val b0 = c[co].toDouble()
        val b1 = c[co + 8].toDouble()
        for (y in 0 until 2) {
            for (iy in 0 until 4) {
                for (ix in 0 until 8) t[32 + ix * 4 + iy] = c[co + (y + iy * 2) * 8 + ix].toDouble()
            }
            t[32] = if (y == 0) b0 + b1 else b0 - b1
            idctSmall(4, 8, p, po + y * 4 * ps, ps, t)
        }
    }

    /** DCT8X4: a left and a right DCT of 8 rows by 4 columns, with their coefficients on alternate rows. */
    private fun dct8x4(c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val b0 = c[co].toDouble()
        val b1 = c[co + 8].toDouble()
        for (x in 0 until 2) {
            for (iy in 0 until 4) {
                for (ix in 0 until 8) t[32 + iy * 8 + ix] = c[co + (x + iy * 2) * 8 + ix].toDouble()
            }
            t[32] = if (x == 0) b0 + b1 else b0 - b1
            idctSmall(8, 4, p, po + x * 4, ps, t)
        }
    }

    /**
     * AFV: one 4 by 4 corner uses a transform made for a block with a corner cut off, the
     * quarter beside it a 4 by 4 DCT, and the other half a 4 by 8 DCT. [kind] picks the corner:
     * bit 0 is set for the right side and bit 1 for the bottom.
     */
    private fun afv(kind: Int, c: FloatArray, co: Int, p: FloatArray, po: Int, ps: Int, t: DoubleArray) {
        val right = kind and 1
        val bottom = kind ushr 1
        val b00 = c[co].toDouble()
        val b01 = c[co + 1].toDouble()
        val b10 = c[co + 8].toDouble()
        // The corner's coefficients are on the even rows and even columns.
        for (iy in 0 until 4) {
            for (ix in 0 until 4) t[iy * 4 + ix] = c[co + iy * 16 + ix * 2].toDouble()
        }
        t[0] = (b00 + b10 + b01) * 4.0
        val basis = AFV_BASIS
        for (iy in 0 until 4) {
            for (ix in 0 until 4) {
                // The basis is that of the top left corner; the other corners mirror it.
                val at = (if (bottom == 1) 3 - iy else iy) * 4 + (if (right == 1) 3 - ix else ix)
                var sum = 0.0
                for (k in 0 until 16) sum += t[k] * basis[k * 16 + at]
                p[po + (iy + bottom * 4) * ps + right * 4 + ix] = sum.toFloat()
            }
        }
        // The 4 by 4 DCT beside the corner: even rows, odd columns.
        for (iy in 0 until 4) {
            for (ix in 0 until 4) t[16 + iy * 4 + ix] = c[co + iy * 16 + ix * 2 + 1].toDouble()
        }
        t[16] = b00 + b10 - b01
        idctSmall(4, 4, p, po + bottom * 4 * ps + (if (right == 1) 0 else 4), ps, t)
        // The 4 by 8 DCT of the other half: odd rows.
        for (iy in 0 until 4) {
            for (ix in 0 until 8) t[32 + ix * 4 + iy] = c[co + (1 + iy * 2) * 8 + ix].toDouble()
        }
        t[32] = b00 - b10
        idctSmall(4, 8, p, po + (if (bottom == 1) 0 else 4) * ps, ps, t)
    }

    // libjxl k4x4AFVBasis, dec_transforms-inl.h: row k is the 4 by 4 samples that coefficient k of the AFV corner adds.
    private val AFV_BASIS = doubleArrayOf(
        0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25,
        0.876902929799142, 0.2206518106944235, -0.10140050393753763, -0.1014005039375375, 0.2206518106944236,
        -0.10140050393753777, -0.10140050393753772, -0.10140050393753763, -0.10140050393753758, -0.10140050393753769,
        -0.1014005039375375, -0.10140050393753768, -0.10140050393753768, -0.10140050393753759, -0.10140050393753763,
        -0.10140050393753741,
        0.0, 0.0, 0.40670075830260755, 0.44444816619734445, 0.0, 0.0, 0.19574399372042936, 0.2929100136981264,
        -0.40670075830260716, -0.19574399372042872, 0.0, 0.11379074460448091, -0.44444816619734384,
        -0.29291001369812636, -0.1137907446044814, 0.0,
        0.0, 0.0, -0.21255748058288748, 0.3085497062849767, 0.0, 0.4706702258572536, -0.1621205195722993, 0.0,
        -0.21255748058287047, -0.16212051957228327, -0.47067022585725277, -0.1464291867126764, 0.3085497062849487,
        0.0, -0.14642918671266536, 0.4251149611657548,
        0.0, -0.7071067811865474, 0.0, 0.0, 0.7071067811865476, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        -0.4105377591765233, 0.6235485373547691, -0.06435071657946274, -0.06435071657946266, 0.6235485373547694,
        -0.06435071657946284, -0.0643507165794628, -0.06435071657946274, -0.06435071657946272, -0.06435071657946279,
        -0.06435071657946266, -0.06435071657946277, -0.06435071657946277, -0.06435071657946273, -0.06435071657946274,
        -0.0643507165794626,
        0.0, 0.0, -0.4517556589999482, 0.15854503551840063, 0.0, -0.04038515160822202, 0.0074182263792423875,
        0.39351034269210167, -0.45175565899994635, 0.007418226379244351, 0.1107416575309343, 0.08298163094882051,
        0.15854503551839705, 0.3935103426921022, 0.0829816309488214, -0.45175565899994796,
        0.0, 0.0, -0.304684750724869, 0.5112616136591823, 0.0, 0.0, -0.290480129728998, -0.06578701549142804,
        0.304684750724884, 0.2904801297290076, 0.0, -0.23889773523344604, -0.5112616136592012, 0.06578701549142545,
        0.23889773523345467, 0.0,
        0.0, 0.0, 0.3017929516615495, 0.25792362796341184, 0.0, 0.16272340142866204, 0.09520022653475037, 0.0,
        0.3017929516615503, 0.09520022653475055, -0.16272340142866173, -0.35312385449816297, 0.25792362796341295, 0.0,
        -0.3531238544981624, -0.6035859033230976,
        0.0, 0.0, 0.40824829046386274, 0.0, 0.0, 0.0, 0.0, -0.4082482904638628, -0.4082482904638635, 0.0, 0.0,
        -0.40824829046386296, 0.0, 0.4082482904638634, 0.408248290463863, 0.0,
        0.0, 0.0, 0.1747866975480809, 0.0812611176717539, 0.0, 0.0, -0.3675398009862027, -0.307882213957909,
        -0.17478669754808135, 0.3675398009862011, 0.0, 0.4826689115059883, -0.08126111767175039, 0.30788221395790305,
        -0.48266891150598584, 0.0,
        0.0, 0.0, -0.21105601049335784, 0.18567180916109802, 0.0, 0.0, 0.49215859013738733, -0.38525013709251915,
        0.21105601049335806, -0.49215859013738905, 0.0, 0.17419412659916217, -0.18567180916109904, 0.3852501370925211,
        -0.1741941265991621, 0.0,
        0.0, 0.0, -0.14266084808807264, -0.3416446842253372, 0.0, 0.7367497537172237, 0.24627107722075148,
        -0.08574019035519306, -0.14266084808807344, 0.24627107722075137, 0.14883399227113567, -0.04768680350229251,
        -0.3416446842253373, -0.08574019035519267, -0.047686803502292804, -0.14266084808807242,
        0.0, 0.0, -0.13813540350758585, 0.3302282550303788, 0.0, 0.08755115000587084, -0.07946706605909573,
        -0.4613374887461511, -0.13813540350758294, -0.07946706605910261, 0.49724647109535086, 0.12538059448563663,
        0.3302282550303805, -0.4613374887461554, 0.12538059448564315, -0.13813540350758452,
        0.0, 0.0, -0.17437602599651067, 0.0702790691196284, 0.0, -0.2921026642334881, 0.3623817333531167, 0.0,
        -0.1743760259965108, 0.36238173335311646, 0.29210266423348785, -0.4326608024727445, 0.07027906911962818, 0.0,
        -0.4326608024727457, 0.34875205199302267,
        0.0, 0.0, 0.11354987314994337, -0.07417504595810355, 0.0, 0.19402893032594343, -0.435190496523228,
        0.21918684838857466, 0.11354987314994257, -0.4351904965232251, 0.5550443808910661, -0.25468277124066463,
        -0.07417504595810233, 0.2191868483885728, -0.25468277124066413, 0.1135498731499429,
    )
}
