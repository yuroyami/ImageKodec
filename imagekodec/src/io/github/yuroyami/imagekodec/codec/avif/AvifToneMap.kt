package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.GainMapMetadata
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

/**
 * The payload of a tone-mapped image item (`tmap`, ISO/IEC 23008-12 6.6.2.4 with ISO 21496-1),
 * read as libavif 1.1 reads it in `avifParseToneMappedImageBox` and `avifParseGainMapMetadata`:
 *
 * ```
 * u8 version = 0; u16 minimum_version; u16 writer_version
 * u1 is_multichannel; u1 use_base_colour_space; u6 reserved
 * u32 base_hdr_headroom_n, _d; u32 alternate_hdr_headroom_n, _d
 * per channel (3 if multichannel, else 1):
 *   s32 gain_map_min_n; u32 _d; s32 gain_map_max_n; u32 _d; u32 gamma_n; u32 _d
 *   s32 base_offset_n; u32 _d; s32 alternate_offset_n; u32 _d
 * ```
 */
internal object AvifToneMap {

    /** The metadata of [payload]; [ImageDecodeException] when it is cut off or has a zero denominator. */
    fun read(payload: ByteArray): GainMapMetadata {
        val r = IsoReader(payload, 0, payload.size, "tmap")
        val version = r.u8()
        if (version != 0) throw UnsupportedImageException("AVIF tone-mapped item of version $version")
        val minimumVersion = r.u16()
        if (minimumVersion > 0) throw UnsupportedImageException("AVIF tone-mapped item that needs metadata version $minimumVersion")
        val writerVersion = r.u16()
        if (writerVersion < minimumVersion) r.fail("has writer version $writerVersion below its minimum version $minimumVersion")
        val flags = r.u8()
        val channels = if ((flags and 0x80) != 0) 3 else 1
        fun fraction(signed: Boolean, name: String): Double {
            val n = if (signed) r.s32().toLong() else r.u32()
            val d = r.u32()
            if (d == 0L) r.fail("has a zero denominator in its $name")
            return n.toDouble() / d.toDouble()
        }
        val baseHeadroom = fraction(false, "base HDR headroom")
        val alternateHeadroom = fraction(false, "alternate HDR headroom")
        val min = DoubleArray(channels)
        val max = DoubleArray(channels)
        val gamma = DoubleArray(channels)
        val baseOffset = DoubleArray(channels)
        val alternateOffset = DoubleArray(channels)
        for (c in 0 until channels) {
            min[c] = fraction(true, "gain map minimum")
            max[c] = fraction(true, "gain map maximum")
            gamma[c] = fraction(false, "gamma")
            baseOffset[c] = fraction(true, "base offset")
            alternateOffset[c] = fraction(true, "alternate offset")
        }
        // A writer of a later version may add fields that a reader of version 0 skips.
        if (writerVersion == 0 && r.remaining != 0) r.fail("has ${r.remaining} bytes past its metadata")
        return GainMapMetadata(
            version, minimumVersion, writerVersion, (flags and 0x40) != 0, baseHeadroom, alternateHeadroom,
            min, max, gamma, baseOffset, alternateOffset, payload.copyOf(),
        )
    }
}
