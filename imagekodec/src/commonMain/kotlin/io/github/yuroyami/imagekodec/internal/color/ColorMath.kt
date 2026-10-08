package io.github.yuroyami.imagekodec.internal.color

import kotlin.math.pow

/** 3 by 3 matrices row by row, CIE XYZ and L*a*b*, and the sRGB curve, in double precision. */
internal object ColorMath {
    /** The PCS illuminant, D50, as ICC.1 7.2.16 fixes it. */
    val D50 = doubleArrayOf(0.9642, 1.0, 0.8249)

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
    }

    fun apply(m: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { r ->
        m[3 * r] * v[0] + m[3 * r + 1] * v[1] + m[3 * r + 2] * v[2]
    }

    fun invert(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return null
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    fun diagonal(x: Double, y: Double, z: Double) = doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, z)

    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    /** The Bradford adaptation from white [from] to white [to], both XYZ, as lcms2's _cmsAdaptationMatrix builds it. */
    fun bradford(from: DoubleArray, to: DoubleArray): DoubleArray {
        val s = apply(BRADFORD, from)
        val d = apply(BRADFORD, to)
        return multiply(invert(BRADFORD)!!, multiply(diagonal(d[0] / s[0], d[1] / s[1], d[2] / s[2]), BRADFORD))
    }

    fun xyYToXyz(x: Double, y: Double, luminance: Double = 1.0) = doubleArrayOf(x * luminance / y, luminance, (1 - x - y) * luminance / y)

    /**
     * The matrix taking linear RGB of [primaries] (red, green and blue x and y) and white
     * [white] (x and y) to XYZ adapted to D50, as lcms2's cmsCreateRGBProfile builds its
     * colorants (_cmsBuildRGB2XYZtransferMatrix), or null for chromaticities that make none:
     * a y of zero, or primaries in a line.
     */
    fun rgbToD50(primaries: DoubleArray, white: DoubleArray): DoubleArray? {
        if (primaries.any { !it.isFinite() } || white.any { !it.isFinite() }) return null
        if (primaries[1] <= 0 || primaries[3] <= 0 || primaries[5] <= 0 || white[1] <= 0) return null
        val r = xyYToXyz(primaries[0], primaries[1])
        val g = xyYToXyz(primaries[2], primaries[3])
        val b = xyYToXyz(primaries[4], primaries[5])
        val p = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val w = xyYToXyz(white[0], white[1])
        val s = apply(invert(p) ?: return null, w)
        val m = multiply(p, diagonal(s[0], s[1], s[2]))
        val adapted = multiply(bradford(w, D50), m)
        return adapted.takeIf { a -> a.all { it.isFinite() } && invert(a) != null }
    }

    /** sRGB's linear RGB to D50 XYZ, as lcms2's built-in sRGB profile holds it, and back. */
    val SRGB_TO_D50: DoubleArray = rgbToD50(doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06), doubleArrayOf(0.3127, 0.3290))!!
    val D50_TO_SRGB: DoubleArray = invert(SRGB_TO_D50)!!

    /** The sRGB curve inverted: linear light to the encoded value, unclamped. */
    fun srgbEncode(v: Double): Double = if (v < 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

    fun srgbDecode(v: Double): Double = if (v < 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun labF(t: Double) = if (t > 216.0 / 24389.0) t.pow(1.0 / 3.0) else (24389.0 / 27.0 * t + 16.0) / 116.0
    private fun labFInverse(t: Double) = if (t > 6.0 / 29.0) t * t * t else (116.0 * t - 16.0) * 27.0 / 24389.0

    /** D50 XYZ to CIE L*a*b*, as cmsXYZ2Lab computes it. */
    fun xyzToLab(xyz: DoubleArray): DoubleArray {
        val fx = labF(xyz[0] / D50[0])
        val fy = labF(xyz[1] / D50[1])
        val fz = labF(xyz[2] / D50[2])
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** CIE L*a*b* to D50 XYZ, as cmsLab2XYZ computes it. */
    fun labToXyz(lab: DoubleArray): DoubleArray {
        val fy = (lab[0] + 16.0) / 116.0
        val fx = fy + 0.002 * lab[1]
        val fz = fy - 0.005 * lab[2]
        return doubleArrayOf(labFInverse(fx) * D50[0], labFInverse(fy) * D50[1], labFInverse(fz) * D50[2])
    }
}
