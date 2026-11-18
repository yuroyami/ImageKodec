package io.github.yuroyami.imagekodec.internal.color

/**
 * Linear sRGB into the samples of an ICC profile of matrix and curves (ICC.1 F.3, the RGB or gray
 * profile of three or one TRC tag), the way back from the profile connection space that a colour
 * management system takes when it converts to such a profile: D50 XYZ through the inverse of the
 * profile's colorants, then each channel through its curve inverted. A JPEG XL file whose lossy
 * picture comes with a profile of its own is written in that profile, as libjxl writes it (#189).
 *
 * The curves are inverted by a table of [STEPS] samples of each, which a search reads with linear
 * interpolation between them. A curve that does not rise everywhere is inverted where it rises.
 */
internal class IccInverse private constructor(
    private val fromSrgb: DoubleArray?,
    private val curves: Array<DoubleArray>,
) {
    /** True for a gray profile, which takes the luminance alone. */
    val gray: Boolean get() = fromSrgb == null

    /** [rgb], linear sRGB, as the profile's samples from 0 to 1, in place: one for gray, three for RGB. */
    fun encode(rgb: DoubleArray) {
        if (fromSrgb == null) {
            // D50 Y of sRGB's linear light, which a gray profile's curve takes.
            val y = ColorMath.SRGB_TO_D50[3] * rgb[0] + ColorMath.SRGB_TO_D50[4] * rgb[1] + ColorMath.SRGB_TO_D50[5] * rgb[2]
            val v = invert(curves[0], y)
            rgb[0] = v; rgb[1] = v; rgb[2] = v
            return
        }
        val device = ColorMath.apply(fromSrgb, rgb)
        for (c in 0 until 3) rgb[c] = invert(curves[c], device[c])
    }

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

    companion object {
        private const val STEPS = 4096

        /** The inverse of [icc], or null when it is not an RGB or gray profile of matrix and curves. */
        fun of(icc: ByteArray): IccInverse? {
            val p = IccProfile.parse(icc) ?: return null
            // A profile with tables converts through them, which this does not read.
            if (p.pcs != "XYZ " || p.has("A2B0") || p.has("B2A0")) return null
            fun table(curve: IccCurve): DoubleArray {
                val t = DoubleArray(STEPS + 1) { curve.eval(it.toDouble() / STEPS) }
                // A flat or falling stretch keeps the value before it, so the search finds the first rise.
                for (i in 1..STEPS) if (t[i] < t[i - 1]) t[i] = t[i - 1]
                return t
            }
            return when (p.colorSpace) {
                "GRAY" -> {
                    val k = p.curve("kTRC") ?: return null
                    IccInverse(null, arrayOf(table(k)))
                }
                "RGB " -> {
                    val r = p.xyz("rXYZ") ?: return null
                    val g = p.xyz("gXYZ") ?: return null
                    val b = p.xyz("bXYZ") ?: return null
                    val curves = arrayOf(p.curve("rTRC") ?: return null, p.curve("gTRC") ?: return null, p.curve("bTRC") ?: return null)
                    val toPcs = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
                    val fromPcs = ColorMath.invert(toPcs) ?: return null
                    IccInverse(ColorMath.multiply(fromPcs, ColorMath.SRGB_TO_D50), Array(3) { table(curves[it]) })
                }
                else -> null
            }
        }
    }
}
