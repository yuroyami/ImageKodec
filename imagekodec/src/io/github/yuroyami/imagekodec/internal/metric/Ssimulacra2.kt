// Altered Kotlin port of libjxl's tools/ssimulacra2.cc, the recursive Gaussian of
// tools/gauss_blur.cc and the opsin absorbance of lib/jxl/enc_xyb.cc and
// lib/jxl/cms/opsin_params.h, at commit 1e812edf683fa48b540c193aba0a4b69ad44827f, with the
// alpha handling of tools/ssimulacra2_main.cc at tag v0.11.1.
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.internal.metric

import io.github.yuroyami.imagekodec.KiteBitmap
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SSIMULACRA 2, a port of `tools/ssimulacra2.cc` of libjxl (BSD-3-Clause, the JPEG XL Project
 * Authors), for #134: a perceptual score of a distorted image against its reference, where 100
 * means identical.
 *
 * As the reference does, both images go to linear sRGB, with alpha blended onto a gray, then to
 * XYB with libjxl's opsin absorbance and rescaled to about 0..1. At each of up to six scales,
 * downsampled 2:1 in linear light, it blurs the planes and their products with libjxl's recursive
 * Gaussian of sigma 1.5 (Charalampidis 2016, as `tools/gauss_blur.cc` builds it), takes
 * the 1- and 4-norm of an SSIM error map and of two edge maps, and weighs the 108 sums with the
 * reference's weights. As `tools/ssimulacra2_main.cc` does, a reference with alpha is scored
 * twice, blended onto 0.1 and onto 0.9, and the worse score counts; without alpha in the
 * reference, only the distorted image is blended, onto 0.5.
 *
 * The reference holds a dozen full-size float planes. This port streams rows through the
 * separable blur instead: the vertical pass keeps a ring of 2N + 1 blurred rows and its filter
 * state, and only the half-size linear image of the next scale is kept, so a 12-megapixel photo
 * takes about 70 MB rather than a gigabyte. The sums come out in the same order.
 */
internal object Ssimulacra2 {

    private const val C2 = 0.0009f
    private const val SCALES = 6

    // Opsin absorbance of lib/jxl/cms/opsin_params.h, at an intensity target of 255.
    private const val M00 = 0.30f
    private const val M02 = 0.078f
    private const val M01 = 1.0f - M02 - M00
    private const val M10 = 0.23f
    private const val M12 = 0.078f
    private const val M11 = 1.0f - M12 - M10
    private const val M20 = 0.24342268924547819f
    private const val M21 = 0.20476744424496821f
    private const val M22 = 1.0f - M20 - M21
    private const val BIAS = 0.0037930732552754493f
    private val NEG_BIAS_CBRT = -cbrt(BIAS)

    private val WEIGHTS = doubleArrayOf(
        0.0, 0.0007376606707406586, 0.0, 0.0, 0.0007793481682867309, 0.0, 0.0, 0.0004371155730107379, 0.0,
        1.1041726426657346, 0.00066284834129271, 0.00015231632783718752, 0.0, 0.0016406437456599754, 0.0,
        1.8422455520539298, 11.441172603757666, 0.0, 0.0007989109436015163, 0.000176816438078653, 0.0,
        1.8787594979546387, 10.94906990605142, 0.0, 0.0007289346991508072, 0.9677937080626833, 0.0,
        0.00014003424285435884, 0.9981766977854967, 0.00031949755934435053, 0.0004550992113792063, 0.0, 0.0,
        0.0013648766163243398, 0.0, 0.0, 0.0, 0.0, 0.0, 7.466890328078848, 0.0, 17.445833984131262,
        0.0006235601634041466, 0.0, 0.0, 6.683678146179332, 0.00037724407979611296, 1.027889937768264,
        225.20515300849274, 0.0, 0.0, 19.213238186143016, 0.0011401524586618361, 0.001237755635509985,
        176.39317598450694, 0.0, 0.0, 24.43300999870476, 0.28520802612117757, 0.0004485436923833408, 0.0, 0.0,
        0.0, 34.77906344483772, 44.835625328877896, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0008680556573291698, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0005313191874358747, 0.0, 0.00016533814161379112,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0004179171803251336, 0.0017290828234722833, 0.0, 0.0020827005846636437,
        0.0, 0.0, 8.826982764996862, 23.19243343998926, 0.0, 95.1080498811086, 0.9863978034400682,
        0.9834382792465353, 0.0012286405048278493, 171.2667255897307, 0.9807858872435379, 0.0, 0.0, 0.0,
        0.0005130064588990679, 0.0, 0.00010854057858411537,
    )

    /** The recursive Gaussian of `CreateRecursiveGaussian(1.5)`: its radius N and the n2 and d1 of its three filters. */
    private val radius: Int
    private val n2 = FloatArray(3)
    private val d1 = FloatArray(3)

    init {
        val sigma = 1.5
        val r = round(3.2795 * sigma + 0.2546) // (57), "N"
        val piDiv2r = kotlin.math.PI / (2.0 * r)
        val omega = doubleArrayOf(piDiv2r, 3.0 * piDiv2r, 5.0 * piDiv2r)
        val p1 = +1.0 / tan(0.5 * omega[0])
        val p3 = -1.0 / tan(0.5 * omega[1])
        val p5 = +1.0 / tan(0.5 * omega[2])
        val r1 = +p1 * p1 / sin(omega[0])
        val r3 = -p3 * p3 / sin(omega[1])
        val r5 = +p5 * p5 / sin(omega[2])
        val negHalfSigma2 = -0.5 * sigma * sigma
        val rho = DoubleArray(3) { exp(negHalfSigma2 * omega[it] * omega[it]) / r }
        val d13 = p1 * r3 - r1 * p3
        val d35 = p3 * r5 - r3 * p5
        val d51 = p5 * r1 - r5 * p1
        val zeta15 = d35 / d13
        val zeta35 = d51 / d13
        val a = inverse(doubleArrayOf(p1, p3, p5, r1, r3, r5, zeta15, zeta35, 1.0)) // (56)
        val gamma = doubleArrayOf(1.0, r * r - sigma * sigma, zeta15 * rho[0] + zeta35 * rho[1] + rho[2]) // (55)
        val beta = DoubleArray(3) { a[it * 3] * gamma[0] + a[it * 3 + 1] * gamma[1] + a[it * 3 + 2] * gamma[2] } // (53)
        radius = r.toInt()
        for (i in 0 until 3) {
            n2[i] = (-beta[i] * cos(omega[i] * (r + 1.0))).toFloat() // (33)
            d1[i] = (-2.0 * cos(omega[i])).toFloat()
        }
    }

    private fun inverse(m: DoubleArray): DoubleArray {
        val c00 = m[4] * m[8] - m[5] * m[7]
        val c01 = m[5] * m[6] - m[3] * m[8]
        val c02 = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * c00 + m[1] * c01 + m[2] * c02
        return doubleArrayOf(
            c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }

    /** The sRGB transfer function for each 8-bit value, as the CMS of the reference gives it. */
    private val linear = FloatArray(256) {
        val v = it / 255.0
        (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    /** Linear rows of the two images of a scale. */
    private fun interface Rows {
        /** Fills [out] with the linear red, green and blue of row [y] of image [image], 0 or 1. */
        fun row(image: Int, y: Int, out: Array<FloatArray>)
    }

    private class Planes(val width: Int, val height: Int) {
        val data = Array(2) { Array(3) { FloatArray(width * height) } }
    }

    fun score(reference: KiteBitmap, distorted: KiteBitmap): Double {
        require(reference.width == distorted.width && reference.height == distorted.height) {
            "SSIMULACRA 2 compares images of one size: ${reference.width}x${reference.height} and ${distorted.width}x${distorted.height}"
        }
        // The tool asks whether the original has an alpha channel. An opaque one blends to itself
        // on any background, so asking whether a pixel is not opaque gives the same score.
        if (reference.argb.all { (it ushr 24) == 0xFF }) return score(reference, distorted, 0.5f)
        return minOf(score(reference, distorted, 0.1f), score(reference, distorted, 0.9f))
    }

    private fun score(reference: KiteBitmap, distorted: KiteBitmap, background: Float): Double {
        val images = arrayOf(reference, distorted)
        var rows = Rows { image, y, out ->
            val bitmap = images[image]
            val argb = bitmap.argb
            val w = bitmap.width
            for (x in 0 until w) {
                val p = argb[y * w + x]
                val alpha = (p ushr 24) and 0xFF
                if (alpha == 255) {
                    out[0][x] = linear[(p ushr 16) and 0xFF]
                    out[1][x] = linear[(p ushr 8) and 0xFF]
                    out[2][x] = linear[p and 0xFF]
                } else {
                    // AlphaBlend in the encoded values, before the transfer to linear.
                    val a = alpha / 255f
                    for (c in 0 until 3) {
                        val v = ((p ushr (16 - 8 * c)) and 0xFF) / 255f
                        out[c][x] = toLinear(a * v + (1f - a) * background)
                    }
                }
            }
        }
        var width = reference.width
        var height = reference.height
        val sums = ArrayList<DoubleArray>()
        var pending: Planes? = null
        for (scale in 0 until SCALES) {
            if (width < 8 || height < 8) break
            if (scale > 0) {
                // The previous pass left the half-size linear image of this scale.
                val half = pending!!
                rows = Rows { image, y, out -> for (c in 0 until 3) half.data[image][c].copyInto(out[c], 0, y * half.width, (y + 1) * half.width) }
                width = half.width
                height = half.height
            }
            val keepNext = scale + 1 < SCALES && width >= 8 && height >= 8
            val (averages, next) = scale(width, height, rows, keepNext)
            sums += averages
            pending = next
        }
        return combine(sums)
    }

    private fun toLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055) / 1.055).pow(2.4).toFloat()

    /**
     * One scale: the 6 SSIM and the 12 edge averages of `SSIMMap` and `EdgeDiffMap`, channel by
     * channel. With [keepNext], it also returns the image downsampled 2:1 in linear light, as
     * `Downsample(in, 2, 2)` makes it, for the next scale.
     */
    private fun scale(width: Int, height: Int, rows: Rows, keepNext: Boolean): Pair<DoubleArray, Planes?> {
        val n = radius
        val ring = 2 * n + 1
        val next = if (keepNext) Planes((width + 1) / 2, (height + 1) / 2) else null
        val linearRow = Array(2) { Array(3) { FloatArray(width) } }
        // XYB rows of both images, and the horizontally blurred rows of 15 quantities: for each
        // channel the two planes, their squares and their product.
        val xyb = Array(2) { Array(3) { Array(ring) { FloatArray(width) } } }
        val blurred = Array(15) { Array(ring) { FloatArray(width) } }
        val product = FloatArray(width)
        // The vertical filter state: the last two outputs of each of the three filters, per column.
        val prev = Array(15) { Array(3) { FloatArray(width) } }
        val prev2 = Array(15) { Array(3) { FloatArray(width) } }
        val mu = Array(15) { FloatArray(width) }
        val ssim = DoubleArray(6)
        val edge = DoubleArray(12)

        fun readRow(y: Int) {
            val slot = y % ring
            for (image in 0 until 2) {
                rows.row(image, y, linearRow[image])
                if (next != null) downsample(linearRow[image], next.data[image], width, height, next.width, y)
                toXyb(linearRow[image], width)
                for (c in 0 until 3) linearRow[image][c].copyInto(xyb[image][c][slot])
            }
            for (c in 0 until 3) {
                val a = xyb[0][c][slot]
                val b = xyb[1][c][slot]
                horizontal(a, blurred[c * 5][slot], width)
                horizontal(b, blurred[c * 5 + 1][slot], width)
                for (x in 0 until width) product[x] = a[x] * a[x]
                horizontal(product, blurred[c * 5 + 2][slot], width)
                for (x in 0 until width) product[x] = b[x] * b[x]
                horizontal(product, blurred[c * 5 + 3][slot], width)
                for (x in 0 until width) product[x] = a[x] * b[x]
                horizontal(product, blurred[c * 5 + 4][slot], width)
            }
        }

        for (out in -n + 1 until height) {
            val bottom = out + n - 1
            val top = out - n - 1
            if (bottom < height) readRow(bottom)
            for (q in 0 until 15) {
                val b = if (bottom < height) blurred[q][bottom % ring] else null
                val t = if (top >= 0) blurred[q][top % ring] else null
                val result = mu[q]
                for (x in 0 until width) {
                    val sum = (t?.get(x) ?: 0f) + (b?.get(x) ?: 0f)
                    var total = 0f
                    for (f in 0 until 3) {
                        val y = n2[f] * sum - d1[f] * prev[q][f][x] - prev2[q][f][x]
                        prev2[q][f][x] = prev[q][f][x]
                        prev[q][f][x] = y
                        total += y
                    }
                    result[x] = total
                }
            }
            if (out < 0) continue
            val slot = out % ring
            for (c in 0 until 3) {
                val row1 = xyb[0][c][slot]
                val row2 = xyb[1][c][slot]
                val m1 = mu[c * 5]
                val m2 = mu[c * 5 + 1]
                val s11 = mu[c * 5 + 2]
                val s22 = mu[c * 5 + 3]
                val s12 = mu[c * 5 + 4]
                for (x in 0 until width) {
                    val mu1 = m1[x]
                    val mu2 = m2[x]
                    val numM = (1.0 - (mu1 - mu2) * (mu1 - mu2)).toFloat()
                    val numS = 2 * (s12[x] - mu1 * mu2) + C2
                    val denomS = (s11[x] - mu1 * mu1) + (s22[x] - mu2 * mu2) + C2
                    val d = maxOf(1.0 - (numM * numS / denomS), 0.0)
                    ssim[c * 2] += d
                    ssim[c * 2 + 1] += quartic(d)

                    val d1 = (1.0 + abs(row2[x] - mu2)) / (1.0 + abs(row1[x] - mu1)) - 1.0
                    val artifact = maxOf(d1, 0.0)
                    edge[c * 4] += artifact
                    edge[c * 4 + 1] += quartic(artifact)
                    val detailLost = maxOf(-d1, 0.0)
                    edge[c * 4 + 2] += detailLost
                    edge[c * 4 + 3] += quartic(detailLost)
                }
            }
        }
        if (next != null) for (planes in next.data) for (plane in planes) for (i in plane.indices) plane[i] *= 0.25f

        val onePerPixels = 1.0 / (height.toDouble() * width)
        val result = DoubleArray(18)
        for (c in 0 until 3) {
            result[c * 2] = onePerPixels * ssim[c * 2]
            result[c * 2 + 1] = sqrt(sqrt(onePerPixels * ssim[c * 2 + 1]))
            result[6 + c * 4] = onePerPixels * edge[c * 4]
            result[6 + c * 4 + 1] = sqrt(sqrt(onePerPixels * edge[c * 4 + 1]))
            result[6 + c * 4 + 2] = onePerPixels * edge[c * 4 + 2]
            result[6 + c * 4 + 3] = sqrt(sqrt(onePerPixels * edge[c * 4 + 3]))
        }
        return result to next
    }

    private fun quartic(v: Double): Double {
        val s = v * v
        return s * s
    }

    /** Adds row [y] to the 2:1 sums, as `Downsample` adds the four pixels of each output, the last row and column repeated. */
    private fun downsample(row: Array<FloatArray>, into: Array<FloatArray>, width: Int, height: Int, nextWidth: Int, y: Int) {
        val times = if (y == height - 1 && height % 2 == 1) 2 else 1
        val base = (y / 2) * nextWidth
        for (c in 0 until 3) {
            val src = row[c]
            val dst = into[c]
            repeat(times) {
                for (ox in 0 until nextWidth) {
                    var sum = dst[base + ox]
                    sum += src[2 * ox]
                    sum += src[minOf(2 * ox + 1, width - 1)]
                    dst[base + ox] = sum
                }
            }
        }
    }

    /** Linear sRGB to XYB in place, as `LinearRGBRowToXYB`, then `MakePositiveXYB`. */
    private fun toXyb(row: Array<FloatArray>, width: Int) {
        val r = row[0]
        val g = row[1]
        val b = row[2]
        for (x in 0 until width) {
            val m0 = maxOf(M00 * r[x] + (M01 * g[x] + (M02 * b[x] + BIAS)), 0f)
            val m1 = maxOf(M10 * r[x] + (M11 * g[x] + (M12 * b[x] + BIAS)), 0f)
            val m2 = maxOf(M20 * r[x] + (M21 * g[x] + (M22 * b[x] + BIAS)), 0f)
            val l = cbrt(m0) + NEG_BIAS_CBRT
            val m = cbrt(m1) + NEG_BIAS_CBRT
            val s = cbrt(m2) + NEG_BIAS_CBRT
            val xv = 0.5f * (l - m)
            val yv = 0.5f * (l + m)
            r[x] = xv * 14f + 0.42f
            g[x] = yv + 0.01f
            b[x] = (s - yv) + 0.55f
        }
    }

    /** `FastGaussian1D`: the three recursive filters over one row, zero outside it. */
    private fun horizontal(input: FloatArray, output: FloatArray, width: Int) {
        val n = radius
        var p1 = 0f; var p3 = 0f; var p5 = 0f
        var q1 = 0f; var q3 = 0f; var q5 = 0f
        for (i in -n + 1 until width) {
            val left = i - n - 1
            val right = i + n - 1
            val sum = (if (left >= 0) input[left] else 0f) + (if (right < width) input[right] else 0f)
            val o1 = n2[0] * sum - q1 - d1[0] * p1
            val o3 = n2[1] * sum - q3 - d1[1] * p3
            val o5 = n2[2] * sum - q5 - d1[2] * p5
            q1 = p1; q3 = p3; q5 = p5
            p1 = o1; p3 = o3; p5 = o5
            if (i >= 0) output[i] = o1 + (o3 + o5)
        }
    }

    /** `Msssim::Score`: the weighted sum over the scales that ran, then the reference's curve to 0..100. */
    private fun combine(scales: List<DoubleArray>): Double {
        var ssim = 0.0
        var i = 0
        for (c in 0 until 3) {
            for (s in scales) {
                for (n in 0 until 2) {
                    ssim += WEIGHTS[i++] * abs(s[c * 2 + n])
                    ssim += WEIGHTS[i++] * abs(s[6 + c * 4 + n])
                    ssim += WEIGHTS[i++] * abs(s[6 + c * 4 + n + 2])
                }
            }
        }
        ssim *= 0.9562382616834844
        ssim = 2.326765642916932 * ssim - 0.020884521182843837 * ssim * ssim + 6.248496625763138e-05 * ssim * ssim * ssim
        return if (ssim > 0) 100.0 - 10.0 * ssim.pow(0.6276336467831387) else 100.0
    }
}
