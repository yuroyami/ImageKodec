package io.github.yuroyami.imagekodec.internal.color

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colour of ITU-T H.273's code points, as PNG's `cICP` and an AVIF's `nclx` declare it:
 * primaries and white from table 2, transfer functions from table 3, and the HDR ones turned
 * into SDR the way mpv's renderer, libplacebo, does by default.
 */
internal object Cicp {

    /**
     * Red, green, blue and white x and y of primaries [code] (table 2), or null for one not
     * defined, and for 10, CIE XYZ itself, which only comes with SMPTE ST 428's transfer.
     */
    fun primaries(code: Int): DoubleArray? = when (code) {
        1 -> doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06, 0.3127, 0.3290)
        4 -> doubleArrayOf(0.67, 0.33, 0.21, 0.71, 0.14, 0.08, 0.310, 0.316)
        5 -> doubleArrayOf(0.64, 0.33, 0.29, 0.60, 0.15, 0.06, 0.3127, 0.3290)
        6, 7 -> doubleArrayOf(0.630, 0.340, 0.310, 0.595, 0.155, 0.070, 0.3127, 0.3290)
        8 -> doubleArrayOf(0.681, 0.319, 0.243, 0.692, 0.145, 0.049, 0.310, 0.316)
        9 -> doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290)
        11 -> doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.314, 0.351)
        12 -> doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290)
        22 -> doubleArrayOf(0.630, 0.340, 0.295, 0.605, 0.155, 0.077, 0.3127, 0.3290)
        else -> null
    }

    /** What a transfer function code point makes of a sample. */
    sealed class Transfer {
        /** A display-referred SDR curve from the coded value to relative light, 1 at white. */
        class Sdr(val curve: IccCurve) : Transfer()

        /** SMPTE ST 2084, coded value to absolute light. */
        object Pq : Transfer()

        /** ARIB STD-B67, BT.2100's hybrid log-gamma, coded value to scene light. */
        object Hlg : Transfer()
    }

    /**
     * Transfer function [code] (table 3) as a display shows it, or null for one this does not
     * read (the logarithmic ones, SMPTE ST 428 and those not defined). The video curves of
     * BT.709, BT.601, BT.2020 and the like are camera curves, so a display reads them through
     * BT.1886's gamma of 2.4, as mpv and libplacebo do; sRGB (13) is sRGB's own curve.
     */
    fun transfer(code: Int): Transfer? = when (code) {
        1, 6, 7, 11, 12, 14, 15 -> Transfer.Sdr(IccCurve.Gamma(2.4))
        4 -> Transfer.Sdr(IccCurve.Gamma(2.2))
        5 -> Transfer.Sdr(IccCurve.Gamma(2.8))
        8 -> Transfer.Sdr(IccCurve.Identity)
        13 -> Transfer.Sdr(SRGB_CURVE)
        16 -> Transfer.Pq
        18 -> Transfer.Hlg
        else -> null
    }

    /** The sRGB curve as ICC.1's parametric type 4 holds it. */
    val SRGB_CURVE: IccCurve = IccCurve.Parametric(4, doubleArrayOf(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 0.0, 0.0))

    // --- SMPTE ST 2084 (BT.2100 table 4) ---------------------------------------------

    private const val M1 = 2610.0 / 16384
    private const val M2 = 2523.0 / 4096 * 128
    private const val C1 = 3424.0 / 4096
    private const val C2 = 2413.0 / 4096 * 32
    private const val C3 = 2392.0 / 4096 * 32

    /** The PQ EOTF: coded value [e] from 0 to 1 to light in cd/m², 0 to 10000. */
    fun pqToNits(e: Double): Double {
        val p = e.coerceIn(0.0, 1.0).pow(1 / M2)
        return 10000.0 * (max(p - C1, 0.0) / (C2 - C3 * p)).pow(1 / M1)
    }

    /** The PQ inverse EOTF: light in cd/m² to a coded value from 0 to 1. */
    fun nitsToPq(nits: Double): Double {
        val y = (nits / 10000.0).coerceIn(0.0, 1.0).pow(M1)
        return ((C1 + C2 * y) / (1 + C3 * y)).pow(M2)
    }

    // --- ARIB STD-B67 (BT.2100 table 5) ----------------------------------------------

    private const val HLG_A = 0.17883277
    private const val HLG_B = 1 - 4 * HLG_A
    private val HLG_C = 0.5 - HLG_A * ln(4 * HLG_A)

    /** The HLG inverse OETF: coded value [e] from 0 to 1 to scene light from 0 to 1. */
    fun hlgToScene(e: Double): Double {
        val v = e.coerceIn(0.0, 1.0)
        return if (v <= 0.5) v * v / 3 else (exp((v - HLG_C) / HLG_A) + HLG_B) / 12
    }

    /** BT.2408's diffuse white, which SDR white stands for. */
    const val SDR_WHITE_NITS = 203.0

    /** The display HLG is shown on before its tone mapping, BT.2100's reference: 1000 cd/m², system gamma 1.2. */
    const val HLG_PEAK_NITS = 1000.0

    /** The peak an HDR image without metadata is taken to reach, as libplacebo takes it. */
    const val DEFAULT_PEAK_NITS = 1000.0

    /**
     * BT.2390's EETF, which rolls light above a knee off so that [peak] lands on [target]: light
     * in cd/m² in and out, worked in PQ's coded values with black at 0 for both displays.
     */
    fun eetf(nits: Double, peak: Double, target: Double): Double {
        if (peak <= target) return min(nits, target)
        val pqPeak = nitsToPq(peak)
        val e1 = nitsToPq(nits) / pqPeak
        val maxLum = nitsToPq(target) / pqPeak
        val ks = 1.5 * maxLum - 0.5
        val e2 = if (e1 < ks) {
            e1
        } else {
            val t = ((e1 - ks) / (1 - ks)).coerceAtMost(1.0)
            val t2 = t * t
            val t3 = t2 * t
            (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * maxLum
        }
        return pqToNits(e2 * pqPeak)
    }

    /**
     * The light, in cd/m² a channel, that a display shows for coded RGB from 0 to 1 with
     * [primaries] (red, green, blue and white x and y) and an HDR [transfer]: PQ's EOTF, or HLG's
     * inverse OETF and BT.2100's OOTF for a [HLG_PEAK_NITS] display, FD = 1000 · Ys^0.2 · E with
     * Ys the scene luminance by [primaries]' own weights.
     */
    fun light(primaries: DoubleArray, transfer: Transfer): (DoubleArray) -> DoubleArray {
        val toXyz = rgbToXyz(primaries)
        val ky = doubleArrayOf(toXyz[3], toXyz[4], toXyz[5])
        return { rgb ->
            val light = DoubleArray(3)
            when (transfer) {
                Transfer.Pq -> for (c in 0 until 3) light[c] = pqToNits(rgb[c])
                Transfer.Hlg -> {
                    for (c in 0 until 3) light[c] = hlgToScene(rgb[c])
                    val ys = ky[0] * light[0] + ky[1] * light[1] + ky[2] * light[2]
                    val gain = if (ys > 0) HLG_PEAK_NITS * ys.pow(0.2) else 0.0
                    for (c in 0 until 3) light[c] *= gain
                }
                is Transfer.Sdr -> error("an SDR transfer is relative, not light")
            }
            light
        }
    }

    /**
     * Linear sRGB of a picture coded with [primaries] and an HDR [transfer], 1 at SDR white,
     * from its coded RGB from 0 to 1. The [light] of each pixel has its luminance go through
     * [eetf] from the content's [peak] (HLG's display peak for HLG) to BT.2408's SDR white,
     * with every channel scaled alike so hue and saturation hold, and a colour sRGB cannot
     * show moves toward the grey of its own luminance until it fits, rather than each channel
     * clipping on its own. Null for primaries that make no matrix.
     */
    fun hdrToLinearSrgb(primaries: DoubleArray, transfer: Transfer, peak: Double): ((DoubleArray) -> DoubleArray)? {
        val adapted = ColorMath.rgbToD50(primaries.copyOf(6), primaries.copyOfRange(6, 8)) ?: return null
        val toXyz = rgbToXyz(primaries)
        val ky = doubleArrayOf(toXyz[3], toXyz[4], toXyz[5])
        val toSrgb = ColorMath.multiply(ColorMath.D50_TO_SRGB, adapted)
        val contentPeak = if (transfer == Transfer.Hlg) HLG_PEAK_NITS else peak
        val light = light(primaries, transfer)
        return { rgb ->
            val l = light(rgb)
            val y = ky[0] * l[0] + ky[1] * l[1] + ky[2] * l[2]
            val scale = if (y > 0) eetf(y, contentPeak, SDR_WHITE_NITS) / y / SDR_WHITE_NITS else 0.0
            for (c in 0 until 3) l[c] *= scale
            gamutMapped(ColorMath.apply(toSrgb, l))
        }
    }

    /** [rgb], linear sRGB, moved toward the grey of its own luminance just far enough to lie in the cube. */
    fun gamutMapped(rgb: DoubleArray): DoubleArray {
        val y = (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]).coerceIn(0.0, 1.0)
        var t = 1.0
        for (c in 0 until 3) {
            val v = rgb[c]
            if (v > 1 && v - y > 1e-12) t = min(t, (1 - y) / (v - y))
            if (v < 0 && y - v > 1e-12) t = min(t, y / (y - v))
        }
        if (t >= 1) return rgb
        return DoubleArray(3) { y + t * (rgb[it] - y) }
    }

    /** The matrix from linear RGB of [p] to XYZ under its own white, white's Y at 1. */
    private fun rgbToXyz(p: DoubleArray): DoubleArray {
        val r = ColorMath.xyYToXyz(p[0], p[1])
        val g = ColorMath.xyYToXyz(p[2], p[3])
        val b = ColorMath.xyYToXyz(p[4], p[5])
        val m = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val s = ColorMath.apply(ColorMath.invert(m)!!, ColorMath.xyYToXyz(p[6], p[7]))
        return ColorMath.multiply(m, ColorMath.diagonal(s[0], s[1], s[2]))
    }
}
