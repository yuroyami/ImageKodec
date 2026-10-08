package io.github.yuroyami.imagekodec.internal.color

import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.RenderingIntent

/**
 * A conversion from a source colour space to sRGB, in the stages lcms2 links an input profile
 * to its built-in sRGB with: the source's device values to D50 XYZ ([toPcs]), the intent's
 * scaling there ([pcsMatrix] and [pcsOffset]: absolute colorimetric's white point ratio, or
 * black point compensation), then sRGB's matrix and curve inverted.
 *
 * [channels] is 1 for gray sources, read from a bitmap's red channel, or 3. A matrix/TRC source
 * also keeps its [curves] and [matrix] apart, so an 8-bit conversion runs as three table
 * lookups, one matrix and one table lookup a sample.
 */
internal class SrgbConverter private constructor(
    private val channels: Int,
    private val curves: Array<IccCurve>?,
    private val matrix: DoubleArray?,
    private val toPcs: ((DoubleArray) -> DoubleArray)?,
    private val pcsMatrix: DoubleArray,
    private val pcsOffset: DoubleArray,
) {
    /** D50 XYZ to linear sRGB, after the intent's scaling. */
    private val toLinear = ColorMath.multiply(ColorMath.D50_TO_SRGB, pcsMatrix)
    private val offset = ColorMath.apply(ColorMath.D50_TO_SRGB, pcsOffset)

    /** [device], 0 to 1 a channel, as linear sRGB, unclamped. */
    fun linear(device: DoubleArray): DoubleArray {
        val xyz = if (toPcs != null) {
            toPcs.invoke(device)
        } else {
            val c = curves!!
            if (channels == 1) {
                val y = c[0].eval(device[0])
                doubleArrayOf(ColorMath.D50[0] * y, ColorMath.D50[1] * y, ColorMath.D50[2] * y)
            } else {
                ColorMath.apply(matrix!!, doubleArrayOf(c[0].eval(device[0]), c[1].eval(device[1]), c[2].eval(device[2])))
            }
        }
        val l = ColorMath.apply(toLinear, xyz)
        return doubleArrayOf(l[0] + offset[0], l[1] + offset[1], l[2] + offset[2])
    }

    fun convert(bitmap: KiteBitmap): KiteBitmap {
        val out = IntArray(bitmap.argb.size)
        if (toPcs == null) {
            // Per-channel tables of the 256 inputs, one matrix, and the sRGB curve as a table.
            val c = curves!!
            val lin = Array(channels) { k -> DoubleArray(256) { c[k].eval(it / 255.0) } }
            val m = if (channels == 1) {
                val d = ColorMath.D50
                ColorMath.multiply(toLinear, doubleArrayOf(d[0], 0.0, 0.0, d[1], 0.0, 0.0, d[2], 0.0, 0.0))
            } else {
                ColorMath.multiply(toLinear, matrix!!)
            }
            for (i in out.indices) {
                val p = bitmap.argb[i]
                val r: Double
                val g: Double
                val b: Double
                if (channels == 1) {
                    r = lin[0][(p ushr 16) and 0xFF]; g = 0.0; b = 0.0
                } else {
                    r = lin[0][(p ushr 16) and 0xFF]; g = lin[1][(p ushr 8) and 0xFF]; b = lin[2][p and 0xFF]
                }
                out[i] = (p and 0xFF000000.toInt()) or
                    (Encode.to8(m[0] * r + m[1] * g + m[2] * b + offset[0]) shl 16) or
                    (Encode.to8(m[3] * r + m[4] * g + m[5] * b + offset[1]) shl 8) or
                    Encode.to8(m[6] * r + m[7] * g + m[8] * b + offset[2])
            }
        } else {
            val device = DoubleArray(channels)
            for (i in out.indices) {
                val p = bitmap.argb[i]
                device[0] = ((p ushr 16) and 0xFF) / 255.0
                if (channels == 3) {
                    device[1] = ((p ushr 8) and 0xFF) / 255.0
                    device[2] = (p and 0xFF) / 255.0
                }
                val l = linear(device)
                out[i] = (p and 0xFF000000.toInt()) or (Encode.to8(l[0]) shl 16) or (Encode.to8(l[1]) shl 8) or Encode.to8(l[2])
            }
        }
        return KiteBitmap(bitmap.width, bitmap.height, out)
    }

    /**
     * [bitmap] converted at full precision, with alpha kept: RGB or RGBA, or, through a gray
     * profile, gray as it came, since sRGB's grays are neutral.
     */
    fun convert16(bitmap: KiteBitmap16): KiteBitmap16 {
        val n = bitmap.channels
        val alpha = bitmap.hasAlpha
        val keepGray = channels == 1 && n <= 2
        val outChannels = if (keepGray) n else if (alpha) 4 else 3
        val out = ShortArray(bitmap.width * bitmap.height * outChannels)
        val device = DoubleArray(channels)
        for (i in 0 until bitmap.width * bitmap.height) {
            val s = i * n
            fun at(k: Int) = (bitmap.samples[s + k].toInt() and 0xFFFF) / 65535.0
            if (n <= 2) {
                device[0] = at(0)
                if (channels == 3) { device[1] = device[0]; device[2] = device[0] }
            } else {
                // An RGB image through a gray profile reads its first channel, as the 8-bit path does.
                device[0] = at(0)
                if (channels == 3) { device[1] = at(1); device[2] = at(2) }
            }
            val l = linear(device)
            val o = i * outChannels
            out[o] = Encode.to16(l[0]).toShort()
            if (!keepGray) {
                out[o + 1] = Encode.to16(l[1]).toShort()
                out[o + 2] = Encode.to16(l[2]).toShort()
            }
            if (alpha) out[o + outChannels - 1] = bitmap.samples[s + n - 1]
        }
        return KiteBitmap16(bitmap.width, bitmap.height, outChannels, out)
    }

    /** The sRGB curve as a table over linear light, interpolated: within a hundredth of a 16-bit level. */
    private object Encode {
        private const val STEPS = 65536
        private val table = FloatArray(STEPS + 2) { ColorMath.srgbEncode(it.toDouble() / STEPS).toFloat() }

        private fun encode(v: Double): Double {
            if (v <= 0.0) return 0.0
            if (v >= 1.0) return 1.0
            val x = v * STEPS
            val i = x.toInt()
            val f = x - i
            return table[i] + (table[i + 1] - table[i]) * f
        }

        fun to8(v: Double): Int = (encode(v) * 255.0 + 0.5).toInt()
        fun to16(v: Double): Int = (encode(v) * 65535.0 + 0.5).toInt()
    }

    companion object {
        /**
         * The conversion of [profile] to sRGB with [intent], or null when the profile is not one
         * this reads (CMYK and LUT-based profiles until their own step) or already is sRGB to
         * within a fraction of a level, which then stays untouched.
         */
        fun of(profile: IccProfile, intent: RenderingIntent): SrgbConverter? {
            if (profile.deviceClass == "link" || profile.deviceClass == "abst" || profile.deviceClass == "nmcl") return null
            if (profile.pcs != "XYZ " && profile.pcs != "Lab ") return null
            val gray = profile.colorSpace == "GRAY"
            if (!gray && profile.colorSpace != "RGB ") return null
            val curves: Array<IccCurve>
            val matrix: DoubleArray?
            if (gray) {
                curves = arrayOf(profile.curve("kTRC") ?: return null)
                matrix = null
            } else {
                curves = arrayOf(profile.curve("rTRC") ?: return null, profile.curve("gTRC") ?: return null, profile.curve("bTRC") ?: return null)
                val r = profile.xyz("rXYZ") ?: return null
                val g = profile.xyz("gXYZ") ?: return null
                val b = profile.xyz("bXYZ") ?: return null
                matrix = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
                if (ColorMath.invert(matrix) == null) return null
            }
            val channels = if (gray) 1 else 3
            var pcsMatrix = ColorMath.diagonal(1.0, 1.0, 1.0)
            var pcsOffset = doubleArrayOf(0.0, 0.0, 0.0)
            when (intent) {
                RenderingIntent.AbsoluteColorimetric -> {
                    // cmscnvrt.c ComputeAbsoluteIntent, observer fully adapted: the media white
                    // point over the output's, which lcms2's built-in sRGB gives as D50. A v2
                    // display profile's white point reads as D50 (_cmsReadMediaWhitePoint).
                    val white = if (profile.version < 4 && profile.deviceClass == "mntr") null else profile.xyz("wtpt")
                    if (white != null) pcsMatrix = ColorMath.diagonal(white[0] / ColorMath.D50[0], white[1] / ColorMath.D50[1], white[2] / ColorMath.D50[2])
                }
                RenderingIntent.Perceptual, RenderingIntent.Saturation -> {
                    // lcms2 forces black point compensation for perceptual and saturation when a v4
                    // profile takes part, and its built-in sRGB is v4. A matrix source's black is its
                    // darkest colorant through the relative intent, made neutral (cmssamp.c), and
                    // sRGB's is zero.
                    val probe = SrgbConverter(channels, curves, matrix, null, pcsMatrix, pcsOffset)
                    val black = probe.pcs(DoubleArray(channels))
                    val lab = ColorMath.xyzToLab(black)
                    val bp = ColorMath.labToXyz(doubleArrayOf(lab[0].coerceIn(0.0, 50.0), 0.0, 0.0))
                    if (bp[0] != 0.0 || bp[1] != 0.0 || bp[2] != 0.0) {
                        val d = ColorMath.D50
                        // cmscnvrt.c ComputeBlackPointCompensation with a black point of zero out.
                        pcsMatrix = ColorMath.diagonal(d[0] / (d[0] - bp[0]), d[1] / (d[1] - bp[1]), d[2] / (d[2] - bp[2]))
                        pcsOffset = doubleArrayOf(-d[0] * bp[0] / (d[0] - bp[0]), -d[1] * bp[1] / (d[1] - bp[1]), -d[2] * bp[2] / (d[2] - bp[2]))
                    }
                }
                RenderingIntent.RelativeColorimetric -> {}
            }
            val converter = SrgbConverter(channels, curves, matrix, null, pcsMatrix, pcsOffset)
            return if (converter.isNearlySrgb()) null else converter
        }
    }

    /** [device] through the source's curves and matrix: D50 XYZ, before the intent's scaling. */
    private fun pcs(device: DoubleArray): DoubleArray {
        val c = curves!!
        if (channels == 1) {
            val y = c[0].eval(device[0])
            return doubleArrayOf(ColorMath.D50[0] * y, ColorMath.D50[1] * y, ColorMath.D50[2] * y)
        }
        return ColorMath.apply(matrix!!, doubleArrayOf(c[0].eval(device[0]), c[1].eval(device[1]), c[2].eval(device[2])))
    }

    /** Whether every 8-bit value of every channel, and every mix tried, comes back within a tenth of a level. */
    private fun isNearlySrgb(): Boolean {
        if (channels != 3) return false
        val probe = DoubleArray(3)
        for (v in 0..255 step 5) for (k in 0 until 4) {
            probe.fill(0.0)
            if (k == 3) probe.fill(v / 255.0) else probe[k] = v / 255.0
            val l = linear(probe)
            for (c in 0 until 3) {
                if (kotlin.math.abs(ColorMath.srgbEncode(l[c].coerceIn(0.0, 1.0)) * 255.0 - probe[c] * 255.0) > 0.1) return false
            }
        }
        return true
    }
}
