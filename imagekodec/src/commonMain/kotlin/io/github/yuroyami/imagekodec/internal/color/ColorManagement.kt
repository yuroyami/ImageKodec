package io.github.yuroyami.imagekodec.internal.color

import io.github.yuroyami.imagekodec.ColorProfile
import io.github.yuroyami.imagekodec.RenderingIntent

/** Which of a file's colour declarations converts it to sRGB, and how. */
internal object ColorManagement {

    /** A declaration this reads: its conversion, or null for one that declares sRGB. */
    private class Found(val converter: SrgbConverter?)

    /**
     * The conversion of [profile] to sRGB with [intent], or null when nothing needs converting:
     * the file declares sRGB or nothing, or only what this does not read. The first declaration
     * this reads decides, in the order [ColorProfile] gives: a PNG's `cICP`, `iCCP`, `sRGB`, then
     * `gAMA` with `cHRM`, as the PNG third edition orders them and Chrome reads them; elsewhere
     * the ICC profile, then the code points. [channels] is 1 for a gray image, which takes a
     * declaration that is not an ICC profile as gray.
     */
    fun converter(profile: ColorProfile, intent: RenderingIntent, channels: Int = 3): SrgbConverter? {
        val order: List<() -> Found?> = if (profile.png) {
            listOf({ cicp(profile, channels) }, { icc(profile, intent) }, { srgb(profile) }, { gammaAndChromaticities(profile, channels) })
        } else {
            listOf({ icc(profile, intent) }, { cicp(profile, channels) })
        }
        for (declaration in order) {
            val found = declaration() ?: continue
            return found.converter?.takeUnless { it.isNearlySrgb() }
        }
        return null
    }

    private fun icc(profile: ColorProfile, intent: RenderingIntent): Found? {
        val icc = profile.icc?.let { IccProfile.parse(it) } ?: return null
        return SrgbConverter.of(icc, intent)?.let { Found(it) }
    }

    private fun srgb(profile: ColorProfile): Found? = if (profile.srgbIntent != null) Found(null) else null

    /**
     * H.273 code points. In a PNG they describe the RGB samples themselves, so they must say
     * RGB (matrix 0) and their range flag applies to them; an AVIF's decoder has already
     * turned its YUV into full-range RGB, so only the primaries and the transfer are left.
     * An unspecified field reads as sRGB's, as browsers read it.
     */
    private fun cicp(profile: ColorProfile, channels: Int): Found? {
        val c = profile.cicp ?: return null
        if (c.size != 4 || (profile.png && c[2] != 0)) return null
        val narrow = profile.png && c[3] == 0
        val primaries = (if (c[0] == 2) Cicp.primaries(1) else Cicp.primaries(c[0])) ?: return null
        val transfer = (if (c[1] == 2) Cicp.transfer(13) else Cicp.transfer(c[1])) ?: return null
        val converter = when (transfer) {
            is Cicp.Transfer.Sdr -> SrgbConverter.shaper(transfer.curve, primaries.takeIf { channels != 1 }, narrow)
            else -> Cicp.hdrToLinearSrgb(primaries, transfer, peak(profile))?.let { SrgbConverter.function(if (channels == 1) 1 else 3, it, narrow) }
        }
        return converter?.let { Found(it) }
    }

    /**
     * The brightest light an HDR image holds, in cd/m²: its maximum content light level, else
     * its mastering display's peak, else [Cicp.DEFAULT_PEAK_NITS], as libplacebo picks it.
     */
    fun peak(profile: ColorProfile): Double {
        profile.contentLight?.get(0)?.takeIf { it > 0 }?.let { return it / 10000.0 }
        profile.masteringDisplay?.get(8)?.takeIf { it > 0 }?.let { return it / 10000.0 }
        return Cicp.DEFAULT_PEAK_NITS
    }

    /**
     * PNG's `gAMA` and `cHRM`. Alone, `gAMA` means sRGB's primaries with a power curve of the
     * inverse of its gamma, and `cHRM` means its primaries with sRGB's curve, as Chrome and
     * Firefox read them. A value outside reason is ignored as if absent.
     */
    private fun gammaAndChromaticities(profile: ColorProfile, channels: Int): Found? {
        // A decoding exponent from 1/10 to 10.
        val curve = profile.gamma?.takeIf { it in 10_000..1_000_000 }?.let { IccCurve.Gamma(100_000.0 / it) }
        val primaries = profile.chromaticities?.takeIf { it.size == 8 }?.let { w ->
            // cHRM holds white first, then red, green and blue.
            DoubleArray(8) { w[(it + 2) % 8] / 100_000.0 }
        }?.takeIf { ColorMath.rgbToD50(it.copyOf(6), it.copyOfRange(6, 8)) != null }
        if (curve == null && primaries == null) return null
        val converter = SrgbConverter.shaper(
            curve ?: Cicp.SRGB_CURVE,
            if (channels == 1) null else primaries ?: Cicp.primaries(1),
            narrow = false,
        )
        return converter?.let { Found(it) }
    }
}
