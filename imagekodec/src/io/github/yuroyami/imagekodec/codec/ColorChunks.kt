package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ColorProfile
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * Reads what each format declares about its color space for [ColorProfile]. A damaged
 * chunk or segment is ignored rather than failing the probe, as libpng ignores a bad
 * ancillary chunk and libjpeg an incomplete profile: color information never decides
 * whether an image decodes.
 */
internal object ColorChunks {

    /** Largest profile a PNG `iCCP` may inflate to; real profiles are a few kilobytes to a few megabytes. */
    private const val MAX_ICC = 16L shl 20

    /** PNG's color chunks, gathered as the probe walks past them. */
    class Png {
        var icc: ByteArray? = null
        var iccName: String? = null
        var srgbIntent: Int? = null
        var gamma: Int? = null
        var chromaticities: IntArray? = null
        var cicp: IntArray? = null
        var contentLight: IntArray? = null
        var masteringDisplay: IntArray? = null

        /** Takes [body] of chunk [type] if it is a color chunk; the first of each kind counts. */
        fun take(type: String, body: ByteArray) {
            when (type) {
                "iCCP" -> if (icc == null) iccp(body)
                "sRGB" -> if (srgbIntent == null && body.size == 1 && body[0].toInt() in 0..3) srgbIntent = body[0].toInt()
                "gAMA" -> if (gamma == null && body.size == 4) gamma = u32be(body, 0).toInt().takeIf { it > 0 }
                "cHRM" -> if (chromaticities == null && body.size == 32) {
                    chromaticities = IntArray(8) { u32be(body, it * 4).toInt() }.takeIf { v -> v.all { it >= 0 } }
                }
                "cICP" -> if (cicp == null && body.size == 4) cicp = IntArray(4) { body[it].toInt() and 0xFF }
                "cLLi" -> if (contentLight == null && body.size == 8) contentLight = lightLevels(body, 0, 2)
                "mDCv" -> if (masteringDisplay == null && body.size == 24) masteringDisplay = masteringDisplay(body, 0)
            }
        }

        private fun iccp(body: ByteArray) {
            val nul = body.indexOf(0)
            if (nul !in 1..79 || nul + 2 > body.size || body[nul + 1].toInt() != 0) return
            val profile = try {
                Zlib.decompress(body.copyOfRange(nul + 2, body.size), maximumSize = MAX_ICC, sizeHint = 0)
            } catch (_: Exception) {
                return
            }
            // libpng refuses a profile whose header disagrees with its length, and so do browsers.
            if (profile.size < 132 || u32be(profile, 0) != profile.size.toLong()) return
            icc = profile
            iccName = CharArray(nul) { (body[it].toInt() and 0xFF).toChar() }.concatToString()
        }

        fun profile(): ColorProfile? =
            if (icc == null && srgbIntent == null && gamma == null && chromaticities == null && cicp == null &&
                contentLight == null && masteringDisplay == null
            ) {
                null
            } else {
                ColorProfile(icc, iccName, srgbIntent, gamma, chromaticities, cicp, contentLight, masteringDisplay).also { it.png = true }
            }
    }

    /** [count] big-endian 32-bit light levels from [at], capped at Int.MAX_VALUE, which no display reaches. */
    fun lightLevels(d: ByteArray, at: Int, count: Int): IntArray =
        IntArray(count) { u32be(d, at + 4 * it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    /**
     * A mastering display as PNG's `mDCv` and the ISOBMFF `mdcv` box both lay it out: red,
     * green, blue and white x and y in 16 bits each, then two 32-bit luminances.
     */
    fun masteringDisplay(d: ByteArray, at: Int): IntArray {
        val out = IntArray(10)
        for (i in 0 until 8) out[i] = ((d[at + 2 * i].toInt() and 0xFF) shl 8) or (d[at + 2 * i + 1].toInt() and 0xFF)
        lightLevels(d, at + 16, 2).copyInto(out, 8)
        return out
    }

    /**
     * The ICC profile a JPEG carries in `APP2` `ICC_PROFILE` segments before its scan,
     * joined in sequence order. As libjpeg's `jpeg_read_icc_profile`, a set with a
     * missing, repeated or inconsistent segment yields nothing.
     */
    fun jpegIcc(data: ByteArray): ByteArray? {
        var at = 2
        var chunks: Array<ByteArray?>? = null
        while (at + 4 <= data.size) {
            if ((data[at].toInt() and 0xFF) != 0xFF) return null
            val marker = data[at + 1].toInt() and 0xFF
            if (marker == 0xFF) { at++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue }
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
            if (length < 2 || at + 2 + length > data.size) break
            if (marker == 0xE2 && length >= 16 && isIccSignature(data, at + 4)) {
                val sequence = data[at + 16].toInt() and 0xFF
                val count = data[at + 17].toInt() and 0xFF
                if (count == 0 || sequence == 0 || sequence > count) return null
                val set = chunks ?: arrayOfNulls<ByteArray>(count).also { chunks = it }
                if (set.size != count || set[sequence - 1] != null) return null
                set[sequence - 1] = data.copyOfRange(at + 18, at + 2 + length)
            }
            at += 2 + length
        }
        val set = chunks ?: return null
        if (set.any { it == null }) return null
        return set.fold(ByteArray(0)) { all, part -> all + part!! }
    }

    private fun isIccSignature(d: ByteArray, at: Int): Boolean {
        val signature = "ICC_PROFILE"
        for (i in signature.indices) if (d[at + i].toInt() != signature[i].code) return false
        return d[at + signature.length].toInt() == 0
    }

    /** A BMP V5 header's embedded profile (`PROFILE_EMBEDDED`), at an offset from the header's start. */
    fun bmpIcc(data: ByteArray): ByteArray? {
        if (data.size < 14 + 124 || u32le(data, 14) != 124L) return null
        if (u32le(data, 14 + 56) != 0x4D424544L) return null   // 'MBED'
        val offset = 14 + u32le(data, 14 + 112)
        val size = u32le(data, 14 + 116)
        if (size <= 0 || offset > data.size || size > data.size - offset) return null
        return data.copyOfRange(offset.toInt(), (offset + size).toInt())
    }

    /** True for a GIF application extension named `ICCRGBG1` with authentication code `012`. */
    fun isGifIcc(name: String): Boolean = name == "ICCRGBG1012"

    fun profile(icc: ByteArray?): ColorProfile? = icc?.takeIf { it.isNotEmpty() }?.let { ColorProfile(icc = it) }

    private fun u32be(d: ByteArray, at: Int): Long =
        ((d[at].toLong() and 0xFF) shl 24) or ((d[at + 1].toLong() and 0xFF) shl 16) or
            ((d[at + 2].toLong() and 0xFF) shl 8) or (d[at + 3].toLong() and 0xFF)

    private fun u32le(d: ByteArray, at: Int): Long =
        (d[at].toLong() and 0xFF) or ((d[at + 1].toLong() and 0xFF) shl 8) or
            ((d[at + 2].toLong() and 0xFF) shl 16) or ((d[at + 3].toLong() and 0xFF) shl 24)
}
