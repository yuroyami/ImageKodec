package io.github.yuroyami.imagekodec.internal.color

import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.RenderingIntent

/**
 * A conversion from a source colour space to sRGB, in the stages lcms2 links an input profile
 * to its built-in sRGB with: the source's device values to D50 XYZ, the intent's scaling there
 * ([pcsMatrix] and [pcsOffset]: absolute colorimetric's white point ratio, or black point
 * compensation), then sRGB's matrix and curve inverted.
 *
 * [channels] is 1 for gray sources, read from a bitmap's red channel, 3 for RGB and 4 for CMYK,
 * which only [convertInk] takes. A matrix/TRC source keeps its [curves] and [matrix] apart, so
 * an 8-bit conversion runs as three table lookups, one matrix and one table lookup a sample;
 * any other goes through [toPcs] for each colour, remembered in a small cache.
 */
internal class SrgbConverter private constructor(
    val channels: Int,
    private val curves: Array<IccCurve>?,
    private val matrix: DoubleArray?,
    private val toPcs: ((DoubleArray) -> DoubleArray)?,
    pcsMatrix: DoubleArray,
    pcsOffset: DoubleArray,
) {
    /** D50 XYZ to linear sRGB, after the intent's scaling. */
    private val toLinear = ColorMath.multiply(ColorMath.D50_TO_SRGB, pcsMatrix)
    private val offset = ColorMath.apply(ColorMath.D50_TO_SRGB, pcsOffset)

    /** [device], 0 to 1 a channel, as linear sRGB, unclamped. */
    fun linear(device: DoubleArray): DoubleArray {
        val xyz = toPcs?.invoke(device) ?: matrixPcs(curves!!, matrix, device)
        val l = ColorMath.apply(toLinear, xyz)
        return doubleArrayOf(l[0] + offset[0], l[1] + offset[1], l[2] + offset[2])
    }

    /**
     * A colour, packed as its 8-bit channels, converted and packed as RGB, remembered by a
     * direct-mapped cache of up to 2^16 slots, fewer for an image of fewer [pixels].
     */
    private class Cache(pixels: Int, private val convert: (Int) -> Int) {
        private val bits = (32 - (pixels.coerceIn(1, 1 shl 15) * 2 - 1).countLeadingZeroBits()).coerceIn(8, 16)
        private val keys = IntArray(1 shl bits) { -1 }
        private val values = IntArray(1 shl bits)

        fun get(key: Int): Int {
            // -1 marks an empty slot, so the one key that equals it goes uncached.
            if (key == -1) return convert(key)
            val slot = (key * -0x61c88647) ushr (32 - bits)
            if (keys[slot] == key) return values[slot]
            val v = convert(key)
            keys[slot] = key
            values[slot] = v
            return v
        }
    }

    private fun packed(device: DoubleArray): Int {
        val l = linear(device)
        return (Encode.to8(l[0]) shl 16) or (Encode.to8(l[1]) shl 8) or Encode.to8(l[2])
    }

    fun convert(bitmap: KiteBitmap): KiteBitmap {
        require(channels != 4) { "a CMYK profile converts ink, not RGB" }
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
                val r = lin[0][(p ushr 16) and 0xFF]
                val g = if (channels == 1) 0.0 else lin[1][(p ushr 8) and 0xFF]
                val b = if (channels == 1) 0.0 else lin[2][p and 0xFF]
                out[i] = (p and 0xFF000000.toInt()) or
                    (Encode.to8(m[0] * r + m[1] * g + m[2] * b + offset[0]) shl 16) or
                    (Encode.to8(m[3] * r + m[4] * g + m[5] * b + offset[1]) shl 8) or
                    Encode.to8(m[6] * r + m[7] * g + m[8] * b + offset[2])
            }
        } else {
            val device = DoubleArray(channels)
            val cache = Cache(out.size) { rgb ->
                device[0] = ((rgb ushr 16) and 0xFF) / 255.0
                if (channels == 3) {
                    device[1] = ((rgb ushr 8) and 0xFF) / 255.0
                    device[2] = (rgb and 0xFF) / 255.0
                }
                packed(device)
            }
            for (i in out.indices) {
                val p = bitmap.argb[i]
                out[i] = (p and 0xFF000000.toInt()) or cache.get(p and 0xFFFFFF)
            }
        }
        return KiteBitmap(bitmap.width, bitmap.height, out)
    }

    /** [ink] at 8 bits, converted to sRGB with its alpha, or opaque when it has none. */
    fun convertInk(ink: Ink): KiteBitmap {
        require(channels == 4) { "only a CMYK profile converts ink" }
        val samples = ink.ink!!
        val alpha = ink.alpha
        val device = DoubleArray(4)
        val cache = Cache(ink.width * ink.height) { cmyk ->
            for (k in 0 until 4) device[k] = ((cmyk ushr (24 - 8 * k)) and 0xFF) / 255.0
            packed(device)
        }
        return KiteBitmap(ink.width, ink.height, IntArray(ink.width * ink.height) { i ->
            val key = ((samples[4 * i].toInt() and 0xFF) shl 24) or ((samples[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((samples[4 * i + 2].toInt() and 0xFF) shl 8) or (samples[4 * i + 3].toInt() and 0xFF)
            val a = if (alpha == null) 0xFF else alpha[i].toInt() and 0xFF
            (a shl 24) or cache.get(key)
        })
    }

    /** [convertInk] at 16 bits: RGB, or RGBA when [ink] has alpha, at full precision. */
    fun convertInk16(ink: Ink): KiteBitmap16 {
        require(channels == 4) { "only a CMYK profile converts ink" }
        val samples = ink.ink16!!
        val alpha = ink.alpha16
        val outChannels = if (alpha == null) 3 else 4
        val out = ShortArray(ink.width * ink.height * outChannels)
        val device = DoubleArray(4)
        for (i in 0 until ink.width * ink.height) {
            for (k in 0 until 4) device[k] = (samples[4 * i + k].toInt() and 0xFFFF) / 65535.0
            val l = linear(device)
            val o = i * outChannels
            for (c in 0 until 3) out[o + c] = Encode.to16(l[c]).toShort()
            if (alpha != null) out[o + 3] = alpha[i]
        }
        return KiteBitmap16(ink.width, ink.height, outChannels, out)
    }

    /**
     * [bitmap] converted at full precision, with alpha kept: RGB or RGBA, or, through a gray
     * profile, gray as it came, since sRGB's grays are neutral.
     */
    fun convert16(bitmap: KiteBitmap16): KiteBitmap16 {
        require(channels != 4) { "a CMYK profile converts ink, not RGB" }
        val n = bitmap.channels
        val alpha = bitmap.hasAlpha
        val keepGray = channels == 1 && n <= 2
        val outChannels = if (keepGray) n else if (alpha) 4 else 3
        val out = ShortArray(bitmap.width * bitmap.height * outChannels)
        val device = DoubleArray(channels)
        for (i in 0 until bitmap.width * bitmap.height) {
            val s = i * n
            fun at(k: Int) = (bitmap.samples[s + k].toInt() and 0xFFFF) / 65535.0
            device[0] = at(0)
            if (channels == 3) {
                if (n <= 2) {
                    device[1] = device[0]; device[2] = device[0]
                } else {
                    device[1] = at(1); device[2] = at(2)
                }
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
        /** lcms2's perceptual reference medium black, cmsPERCEPTUAL_BLACK_X, Y and Z. */
        private val PERCEPTUAL_BLACK = doubleArrayOf(0.00336, 0.0034731, 0.00287)

        /** The largest XYZ lcms2's pipelines encode, 1 + 32767/32768, which its offsets are divided by. */
        private const val MAX_ENCODEABLE_XYZ = 1.0 + 32767.0 / 32768.0

        private fun matrixPcs(curves: Array<IccCurve>, matrix: DoubleArray?, device: DoubleArray): DoubleArray {
            if (matrix == null) {
                val y = curves[0].eval(device[0])
                return doubleArrayOf(ColorMath.D50[0] * y, ColorMath.D50[1] * y, ColorMath.D50[2] * y)
            }
            return ColorMath.apply(matrix, doubleArrayOf(curves[0].eval(device[0]), curves[1].eval(device[1]), curves[2].eval(device[2])))
        }

        /** The A2B tag lcms2 reads for [intent] (Device2PCS16 in cmsio1.c). */
        private fun tagOf(intent: RenderingIntent) = when (intent) {
            RenderingIntent.Perceptual -> "A2B0"
            RenderingIntent.RelativeColorimetric, RenderingIntent.AbsoluteColorimetric -> "A2B1"
            RenderingIntent.Saturation -> "A2B2"
        }

        /** The matrix/TRC (or gray TRC) parts of [profile], or null when it has none. */
        private fun shaper(profile: IccProfile): Pair<Array<IccCurve>, DoubleArray?>? {
            if (profile.colorSpace == "GRAY") return arrayOf(profile.curve("kTRC") ?: return null) to null
            if (profile.colorSpace != "RGB ") return null
            val curves = arrayOf(profile.curve("rTRC") ?: return null, profile.curve("gTRC") ?: return null, profile.curve("bTRC") ?: return null)
            val r = profile.xyz("rXYZ") ?: return null
            val g = profile.xyz("gXYZ") ?: return null
            val b = profile.xyz("bXYZ") ?: return null
            val matrix = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
            if (ColorMath.invert(matrix) == null) return null
            return curves to matrix
        }

        /**
         * [profile]'s device-to-PCS function for [intent], as lcms2's _cmsReadInputLUT picks it:
         * the intent's A2B table, else A2B0, else the matrix/TRC tags. Null when it has none.
         */
        private fun toPcs(profile: IccProfile, intent: RenderingIntent): Pair<Int, (DoubleArray) -> DoubleArray>? {
            val lut = IccLut.read(profile, tagOf(intent)) ?: IccLut.read(profile, "A2B0")
            if (lut != null) return lut.inputs to lut::toXyz
            val (curves, matrix) = shaper(profile) ?: return null
            return (if (matrix == null) 1 else 3) to { device -> matrixPcs(curves, matrix, device) }
        }

        /**
         * The conversion of [profile] to sRGB with [intent], or null when the profile is not one
         * this reads or already is sRGB to within a tenth of a level, which then stays untouched.
         */
        fun of(profile: IccProfile, intent: RenderingIntent): SrgbConverter? {
            if (profile.deviceClass == "link" || profile.deviceClass == "abst" || profile.deviceClass == "nmcl") return null
            if (profile.pcs != "XYZ " && profile.pcs != "Lab ") return null
            val (channels, function) = toPcs(profile, intent) ?: return null
            if (channels == 2 || channels > 4) return null
            val hasLut = IccLut.read(profile, "A2B0") != null
            val shaper = if (hasLut) null else shaper(profile)
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
                    // lcms2 forces black point compensation for perceptual and saturation when a
                    // v4 profile takes part, and its built-in sRGB is v4 (_cmsLinkProfiles), with
                    // the black point cmsDetectBlackPoint finds; sRGB's own is zero.
                    val bp = blackPoint(profile, intent, channels)
                    if (bp[0] != 0.0 || bp[1] != 0.0 || bp[2] != 0.0) {
                        val d = ColorMath.D50
                        // cmscnvrt.c ComputeBlackPointCompensation with a black point of zero out.
                        pcsMatrix = ColorMath.diagonal(d[0] / (d[0] - bp[0]), d[1] / (d[1] - bp[1]), d[2] / (d[2] - bp[2]))
                        pcsOffset = doubleArrayOf(-d[0] * bp[0] / (d[0] - bp[0]), -d[1] * bp[1] / (d[1] - bp[1]), -d[2] * bp[2] / (d[2] - bp[2]))
                    }
                }
                RenderingIntent.RelativeColorimetric -> {}
            }
            // cmscnvrt.c IsEmptyLayer: lcms2 leaves out a layer within 0.002 of nothing, summed
            // over the matrix's distance from the identity and the offset in its XYZ encoding.
            val identity = ColorMath.diagonal(1.0, 1.0, 1.0)
            val distance = (0 until 9).sumOf { kotlin.math.abs(pcsMatrix[it] - identity[it]) } +
                pcsOffset.sumOf { kotlin.math.abs(it / MAX_ENCODEABLE_XYZ) }
            if (distance < 0.002) {
                pcsMatrix = identity
                pcsOffset = doubleArrayOf(0.0, 0.0, 0.0)
            }
            val converter = if (shaper != null) {
                SrgbConverter(channels, shaper.first, shaper.second, null, pcsMatrix, pcsOffset)
            } else {
                SrgbConverter(channels, null, null, function, pcsMatrix, pcsOffset)
            }
            return if (converter.isNearlySrgb()) null else converter
        }

        /**
         * cmssamp.c cmsDetectBlackPoint for an input profile: a v4 profile's perceptual and
         * saturation black is the perceptual reference medium's, unless it is a matrix/TRC
         * profile; otherwise the darkest colorant through the intent (relative colorimetric for
         * that v4 matrix case), made neutral with L* at most 50.
         */
        private fun blackPoint(profile: IccProfile, intent: RenderingIntent, channels: Int): DoubleArray {
            val matrixShaper = shaper(profile) != null
            if (profile.version >= 4 && !matrixShaper) return PERCEPTUAL_BLACK
            val through = if (profile.version >= 4) RenderingIntent.RelativeColorimetric else intent
            // cmsIsIntentSupported: the intent's own table, or matrix/TRC tags; otherwise black is zero.
            if (!matrixShaper && IccLut.read(profile, tagOf(through)) == null) return DoubleArray(3)
            val (_, function) = toPcs(profile, through) ?: return DoubleArray(3)
            // _cmsEndPointsBySpace: the darkest colorant is no light, or for CMYK every ink full.
            val black = DoubleArray(channels) { if (channels == 4) 1.0 else 0.0 }
            val lab = ColorMath.xyzToLab(function(black))
            return ColorMath.labToXyz(doubleArrayOf(lab[0].coerceIn(0.0, 50.0), 0.0, 0.0))
        }
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
