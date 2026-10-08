package io.github.yuroyami.imagekodec

/**
 * What a file declares about the color space of its samples, as it declares it:
 * [ImageInfo.colorProfile] reports it, and a decode with [ColorTarget.Srgb] or
 * [ImageKodec.convertToSrgb] converts through it. Nearly every image is sRGB and
 * says so or says nothing; the rest, such as a Display P3 photo or an Adobe RGB
 * scan, draw with the wrong colors unless they are converted.
 *
 * - [icc] is the embedded ICC profile, byte for byte as the file holds it once
 *   decompressed or reassembled: PNG `iCCP`, JPEG `APP2` `ICC_PROFILE` segments in
 *   sequence order, TIFF tag 34675, WebP `ICCP`, JPEG 2000 `colr` methods 2 and 3,
 *   a BMP V5 header's embedded profile, GIF's `ICCRGBG1012` application extension.
 * - [iccName], [srgbIntent], [gamma], [chromaticities] and [cicp] are PNG's own
 *   chunks: the `iCCP` profile name, `sRGB`'s rendering intent (0 perceptual, 1
 *   relative colorimetric, 2 saturation, 3 absolute colorimetric), `gAMA`'s gamma
 *   times 100000 (45455 for the usual 1/2.2), `cHRM`'s white point, red, green and
 *   blue x and y times 100000, and `cICP`'s color primaries, transfer function,
 *   matrix coefficients and full-range flag (ITU-T H.273 code points). An AVIF's
 *   `nclx` colour box, or its AV1 sequence header, gives [cicp] too.
 * - [contentLight] and [masteringDisplay] describe HDR content: PNG's `cLLi` and
 *   `mDCv` chunks, or an AVIF's `clli` and `mdcv` boxes. [contentLight] holds the
 *   maximum content light level and the maximum frame-average light level in
 *   0.0001 cd/m², and [masteringDisplay] the mastering display's red, green and
 *   blue x and y and its white x and y in 0.00002, then its maximum and minimum
 *   luminance in 0.0001 cd/m², as both formats store them.
 *
 * Every field is null when the file does not hold it.
 *
 * A conversion reads them in the order the format sets. A PNG's `cICP` comes first,
 * then `iCCP`, `sRGB`, and `gAMA` with `cHRM`, as the PNG third edition orders them;
 * elsewhere the ICC profile comes before [cicp], as AVIF orders them.
 */
public class ColorProfile(
    public val icc: ByteArray? = null,
    public val iccName: String? = null,
    public val srgbIntent: Int? = null,
    public val gamma: Int? = null,
    public val chromaticities: IntArray? = null,
    public val cicp: IntArray? = null,
    public val contentLight: IntArray? = null,
    public val masteringDisplay: IntArray? = null,
) {
    /**
     * Whether these are a PNG's chunks, whose `cICP` comes before its profile and whose
     * full-range flag applies to the RGB samples themselves.
     */
    internal var png: Boolean = false

    /**
     * The data color space the profile's header names, trimmed: "RGB", "GRAY",
     * "CMYK", "Lab" and so on; null without a profile or with a header too short.
     */
    public val iccColorSpace: String?
        get() {
            val p = icc ?: return null
            if (p.size < 20) return null
            return CharArray(4) { (p[16 + it].toInt() and 0xFF).toChar() }.concatToString().trim()
        }

    /**
     * The profile's description, such as "Display P3" or "Adobe RGB (1998)", read from
     * its `desc` tag: v2's ASCII text or v4's first localized string. Null without a
     * profile or when the tag is missing or damaged.
     */
    public val iccDescription: String?
        get() = icc?.let { IccText.description(it) }

    override fun toString(): String = buildString {
        append("ColorProfile(")
        val parts = ArrayList<String>()
        icc?.let { parts += "icc ${it.size} bytes" + (iccDescription?.let { d -> " \"$d\"" } ?: "") }
        iccName?.let { parts += "name \"$it\"" }
        srgbIntent?.let { parts += "sRGB intent $it" }
        gamma?.let { parts += "gamma $it" }
        chromaticities?.let { parts += "cHRM ${it.toList()}" }
        cicp?.let { parts += "cICP ${it.toList()}" }
        contentLight?.let { parts += "content light ${it.toList()}" }
        masteringDisplay?.let { parts += "mastering display ${it.toList()}" }
        append(parts.joinToString(", "))
        append(")")
    }
}

/** Reads the `desc` tag of an ICC profile, tolerating damage by returning null. */
internal object IccText {

    private fun u32(p: ByteArray, at: Int): Long? {
        if (at < 0 || at > p.size - 4) return null
        return ((p[at].toLong() and 0xFF) shl 24) or ((p[at + 1].toLong() and 0xFF) shl 16) or
            ((p[at + 2].toLong() and 0xFF) shl 8) or (p[at + 3].toLong() and 0xFF)
    }

    fun description(p: ByteArray): String? {
        val count = u32(p, 128) ?: return null
        if (count > (p.size - 132) / 12) return null
        for (i in 0 until count.toInt()) {
            val entry = 132 + i * 12
            if (u32(p, entry) != 0x64657363L) continue   // 'desc'
            val offset = u32(p, entry + 4) ?: return null
            val size = u32(p, entry + 8) ?: return null
            if (offset > p.size || size > p.size - offset || size < 12) return null
            val start = offset.toInt()
            val end = start + size.toInt()
            return when (u32(p, start)) {
                // v2 textDescriptionType: an ASCII count, including its NUL, then the text.
                0x64657363L -> {
                    val n = u32(p, start + 8) ?: return null
                    if (n < 1 || n > end - start - 12) return null
                    CharArray(n.toInt() - 1) { (p[start + 12 + it].toInt() and 0xFF).toChar() }
                        .concatToString().trimEnd('\u0000').ifEmpty { null }
                }
                // v4 multiLocalizedUnicodeType: records of language, country, length and
                // offset; the first record's UTF-16BE string.
                0x6D6C7563L -> {
                    val records = u32(p, start + 8) ?: return null
                    if (records < 1 || end - start < 28) return null
                    val length = u32(p, start + 20) ?: return null
                    val at = u32(p, start + 24) ?: return null
                    if (at > size || length > size - at || length % 2 != 0L) return null
                    val s = start + at.toInt()
                    CharArray(length.toInt() / 2) {
                        (((p[s + it * 2].toInt() and 0xFF) shl 8) or (p[s + it * 2 + 1].toInt() and 0xFF)).toChar()
                    }.concatToString().trimEnd('\u0000').ifEmpty { null }
                }
                else -> null
            }
        }
        return null
    }
}
