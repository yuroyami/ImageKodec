package io.github.yuroyami.imagekodec.internal.color

/**
 * Linear sRGB into the samples of an RGB or gray ICC profile, the way back from the profile
 * connection space that a colour management system takes when it converts to the profile: D50 XYZ
 * through the profile's PCS-to-device table, B2A0 (ICC.1 10.10, 10.11 and 10.13, read as
 * [IccLut.fromXyz] reads them), or, for a profile without one, through the inverse of its
 * colorants and curves (ICC.1 F.3, three TRC tags, or the one of a gray profile). A JPEG XL file
 * whose lossy picture comes with a profile of its own is written in that profile, as libjxl
 * writes it through a colour engine (#189).
 *
 * B2A0 is the perceptual table, the one skcms, libjxl's colour engine, reads; lcms2 reads it for
 * relative colorimetric too when the profile has no B2A1. No black point compensation is applied.
 *
 * A curve is inverted by a table of [STEPS] samples of it, which a search reads with linear
 * interpolation between them. A curve that does not rise everywhere is inverted where it rises.
 */
internal class IccInverse private constructor(
    /** 1 for a gray profile, 3 for RGB. */
    private val channels: Int,
    /** D50 XYZ to the profile's samples, from 0 to 1 but unclamped. */
    private val fromXyz: (DoubleArray) -> DoubleArray,
) {
    /** True for a gray profile, which takes the luminance alone. */
    val gray: Boolean get() = channels == 1

    /** [rgb], linear sRGB, as the profile's samples, in place: three alike for gray, three for RGB. */
    fun encode(rgb: DoubleArray) {
        val device = fromXyz(ColorMath.apply(ColorMath.SRGB_TO_D50, rgb))
        if (channels == 1) {
            rgb[0] = device[0]; rgb[1] = device[0]; rgb[2] = device[0]
        } else {
            rgb[0] = device[0]; rgb[1] = device[1]; rgb[2] = device[2]
        }
    }

    companion object {
        private const val STEPS = 4096

        private fun invert(table: DoubleArray, y: Double): Double {
            if (!(y > table[0])) return 0.0
            if (y >= table[STEPS]) return 1.0
            var lo = 0
            var hi = STEPS
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (table[mid] <= y) lo = mid else hi = mid
            }
            val span = table[hi] - table[lo]
            val t = if (span > 0) (y - table[lo]) / span else 0.0
            return (lo + t) / STEPS
        }

        private fun table(curve: IccCurve): DoubleArray {
            val t = DoubleArray(STEPS + 1) { curve.eval(it.toDouble() / STEPS) }
            // A flat or falling stretch keeps the value before it, so the search finds the first rise.
            for (i in 1..STEPS) if (t[i] < t[i - 1]) t[i] = t[i - 1]
            return t
        }

        /**
         * The inverse of [icc], or null when it is not an RGB or gray profile with a way back from the
         * PCS this reads: a B2A0 table, or matrix and curves.
         */
        fun of(icc: ByteArray): IccInverse? {
            val p = IccProfile.parse(icc) ?: return null
            if (p.pcs != "XYZ " && p.pcs != "Lab ") return null
            val channels = when (p.colorSpace) {
                "GRAY" -> 1
                "RGB " -> 3
                else -> return null
            }
            // lcms2's _cmsReadOutputLUT: the table when there is one, else the matrix and curves.
            if (p.has("B2A0")) {
                val lut = IccLut.readOutput(p, "B2A0") ?: return null
                return IccInverse(channels, lut::fromXyz)
            }
            return if (channels == 1) {
                val k = table(p.curve("kTRC") ?: return null)
                if (p.pcs == "Lab ") {
                    // A gray curve with a Lab PCS gives L* over 100 (BuildGrayOutputPipeline).
                    IccInverse(1) { xyz -> doubleArrayOf(invert(k, ColorMath.xyzToLab(xyz)[0] / 100.0)) }
                } else {
                    IccInverse(1) { xyz -> doubleArrayOf(invert(k, xyz[1])) }
                }
            } else {
                if (p.pcs != "XYZ ") return null
                val r = p.xyz("rXYZ") ?: return null
                val g = p.xyz("gXYZ") ?: return null
                val b = p.xyz("bXYZ") ?: return null
                val curves = arrayOf(p.curve("rTRC") ?: return null, p.curve("gTRC") ?: return null, p.curve("bTRC") ?: return null)
                val fromPcs = ColorMath.invert(doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])) ?: return null
                val tables = Array(3) { table(curves[it]) }
                IccInverse(3) { xyz ->
                    val linear = ColorMath.apply(fromPcs, xyz)
                    DoubleArray(3) { invert(tables[it], linear[it]) }
                }
            }
        }
    }
}
