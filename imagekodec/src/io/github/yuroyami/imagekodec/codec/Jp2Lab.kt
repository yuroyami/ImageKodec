package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.internal.color.ColorMath

/**
 * The CIELab colour space of a JP2 header (enumerated 14, which JPX, ITU-T T.801, defines) to
 * sRGB. Each component maps to its axis by a range and an offset: L* = RL (s - OL) / (2^n - 1),
 * and the same for a* and b*. Without parameters in the box the ranges are 100, 170 and 200 and
 * the offsets 0, 2^(n - 1) and 2^(n - 2) + 2^(n - 3), the illuminant D50.
 *
 * The conversion is relative colorimetric, as lcms2 converts a Lab profile to its sRGB: L*a*b*
 * to XYZ against D50, then sRGB's linear values, clipped to its gamut. The illuminant's white
 * becomes sRGB's white, so the illuminant does not change the result.
 *
 * Only additions, multiplications, divisions and square roots run, which IEEE 754 rounds the same
 * on every target, so a sample converts to the same output everywhere.
 */
internal class Jp2Lab(parameters: LongArray?, precision: IntArray) {
    private val range = DoubleArray(3)
    private val offset = DoubleArray(3)
    private val max = DoubleArray(3) { ((1L shl precision[it]) - 1).toDouble() }

    init {
        if (parameters != null) {
            for (k in 0 until 3) {
                range[k] = parameters[2 * k].toDouble()
                offset[k] = parameters[2 * k + 1].toDouble()
            }
        } else {
            range[0] = 100.0; range[1] = 170.0; range[2] = 200.0
            offset[0] = 0.0
            offset[1] = (1L shl (precision[1] - 1)).toDouble()
            offset[2] = if (precision[2] >= 3) ((1L shl (precision[2] - 2)) + (1L shl (precision[2] - 3))).toDouble() else 0.0
        }
    }

    private val lab = DoubleArray(3)

    /** sRGB's linear red, green and blue of the samples [s], each clipped to 0 to 1. */
    fun linearSrgb(s: IntArray): DoubleArray {
        for (k in 0 until 3) lab[k] = range[k] * (s[k] - offset[k]) / max[k]
        val rgb = ColorMath.apply(ColorMath.D50_TO_SRGB, ColorMath.labToXyz(lab))
        for (k in 0 until 3) rgb[k] = rgb[k].coerceIn(0.0, 1.0)
        return rgb
    }

    companion object {
        /**
         * The sRGB curve of [v], 0 to 1: 12.92 v near black, else 1.055 v^(1/2.4) - 0.055. The power is
         * v^(5/12), the fourth root of v^(5/3), and v^(5/3) is v times the square of v's cube root,
         * which Newton's method finds from 1 in a fixed number of steps.
         */
        fun encode(v: Double): Double {
            if (v <= 0.0031308) return 12.92 * v
            var c = 1.0
            repeat(CUBE_ROOT_STEPS) { c -= (c * c * c - v) / (3.0 * c * c) }
            return 1.055 * kotlin.math.sqrt(kotlin.math.sqrt(v * c * c)) - 0.055
        }

        /** Newton's steps from 1 to the cube root of 0.0031308, the smallest it takes, to the last bit. */
        private const val CUBE_ROOT_STEPS = 12
    }
}
