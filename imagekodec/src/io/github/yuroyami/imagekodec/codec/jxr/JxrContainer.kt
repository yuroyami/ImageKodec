/*
 * The JPEG XR file container: a Kotlin port of jxrlib's ReadContainer and ParsePFDEntry
 * (jxrgluelib/JXRGlueJxr.c) and of its pixel format table (jxrgluelib/JXRGlue.c, JXRGlue.h), at
 * 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE.
 */
package io.github.yuroyami.imagekodec.codec.jxr

/** Bit depths of a sample (BITDEPTH_BITS in jxrlib's windowsmediaphoto.h, T.832 Table 11). */
internal object JxrDepth {
    const val BD_1 = 0
    const val BD_8 = 1
    const val BD_16 = 2
    const val BD_16S = 3
    const val BD_16F = 4
    const val BD_32 = 5
    const val BD_32S = 6
    const val BD_32F = 7
    const val BD_5 = 8
    const val BD_10 = 9
    const val BD_565 = 10

    /** BD_1 with 0 for white, as OUTPUT_BITDEPTH 15 says. */
    const val BD_1_ALT = 15
}

/** Colour formats (COLORFORMAT in windowsmediaphoto.h, T.832 Tables 10 and 31). */
internal object JxrColor {
    const val Y_ONLY = 0
    const val YUV_420 = 1
    const val YUV_422 = 2
    const val YUV_444 = 3
    const val CMYK = 4
    const val NCOMPONENT = 6
    const val RGB = 7
    const val RGBE = 8
}

/**
 * A pixel format of T.832 Annex A.6, as jxrlib's pixelInfo table describes it: the colour format
 * and bit depth the decoder writes, the bits of one pixel, and whether it holds alpha,
 * premultiplied or not, and stores blue first.
 */
internal class JxrPixelFormat(
    val name: String,
    val colorFormat: Int,
    val bitDepth: Int,
    val bitsPerPixel: Int,
    val bitsPerSample: Int,
    val alpha: Boolean = false,
    val premultiplied: Boolean = false,
    val bgr: Boolean = false,
) {
    companion object {
        /** The GUIDs that do not share the 6fddc324-4e03-4bfe-b185-3d77768dc9xx family of the others. */
        private val OTHER = mapOf(
            "d98c6b95-3efe-47d6-bb25-eb1748ab0cf1" to JxrPixelFormat("32bppRGB", JxrColor.RGB, JxrDepth.BD_8, 32, 8),
            "f5c7ad2d-6a8d-43dd-a7a8-a29935261ae9" to JxrPixelFormat("32bppRGBA", JxrColor.RGB, JxrDepth.BD_8, 32, 8, alpha = true),
            "3cc4a650-a527-4d37-a916-3142c7ebedba" to
                JxrPixelFormat("32bppPRGBA", JxrColor.RGB, JxrDepth.BD_8, 32, 8, alpha = true, premultiplied = true),
            "e3fed78f-e8db-4acf-84c1-e97f6136b327" to JxrPixelFormat("96bppRGBFloat", JxrColor.RGB, JxrDepth.BD_32F, 96, 32),
        )

        private fun f(name: String, cf: Int, bd: Int, bits: Int, sample: Int, alpha: Boolean = false, premul: Boolean = false, bgr: Boolean = false) =
            JxrPixelFormat(name, cf, bd, bits, sample, alpha, premul, bgr)

        /** The family's formats by their last byte. */
        private val FAMILY: Map<Int, JxrPixelFormat> = mapOf(
            0x05 to f("BlackWhite", JxrColor.Y_ONLY, JxrDepth.BD_1, 1, 1),
            0x08 to f("8bppGray", JxrColor.Y_ONLY, JxrDepth.BD_8, 8, 8),
            0x0B to f("16bppGray", JxrColor.Y_ONLY, JxrDepth.BD_16, 16, 16),
            0x13 to f("16bppGrayFixedPoint", JxrColor.Y_ONLY, JxrDepth.BD_16S, 16, 16),
            0x3E to f("16bppGrayHalf", JxrColor.Y_ONLY, JxrDepth.BD_16F, 16, 16),
            0x3F to f("32bppGrayFixedPoint", JxrColor.Y_ONLY, JxrDepth.BD_32S, 32, 32),
            0x11 to f("32bppGrayFloat", JxrColor.Y_ONLY, JxrDepth.BD_32F, 32, 32),
            0x0D to f("24bppRGB", JxrColor.RGB, JxrDepth.BD_8, 24, 8),
            0x0C to f("24bppBGR", JxrColor.RGB, JxrDepth.BD_8, 24, 8, bgr = true),
            0x0E to f("32bppBGR", JxrColor.RGB, JxrDepth.BD_8, 32, 8, bgr = true),
            0x15 to f("48bppRGB", JxrColor.RGB, JxrDepth.BD_16, 48, 16),
            0x12 to f("48bppRGBFixedPoint", JxrColor.RGB, JxrDepth.BD_16S, 48, 16),
            0x3B to f("48bppRGBHalf", JxrColor.RGB, JxrDepth.BD_16F, 48, 16),
            0x40 to f("64bppRGBFixedPoint", JxrColor.RGB, JxrDepth.BD_16S, 64, 16),
            0x42 to f("64bppRGBHalf", JxrColor.RGB, JxrDepth.BD_16F, 64, 16),
            0x18 to f("96bppRGBFixedPoint", JxrColor.RGB, JxrDepth.BD_32S, 96, 32),
            0x41 to f("128bppRGBFixedPoint", JxrColor.RGB, JxrDepth.BD_32S, 128, 32),
            0x1B to f("128bppRGBFloat", JxrColor.RGB, JxrDepth.BD_32F, 128, 32),
            0x0F to f("32bppBGRA", JxrColor.RGB, JxrDepth.BD_8, 32, 8, alpha = true, bgr = true),
            0x16 to f("64bppRGBA", JxrColor.RGB, JxrDepth.BD_16, 64, 16, alpha = true),
            0x1D to f("64bppRGBAFixedPoint", JxrColor.RGB, JxrDepth.BD_16S, 64, 16, alpha = true),
            0x3A to f("64bppRGBAHalf", JxrColor.RGB, JxrDepth.BD_16F, 64, 16, alpha = true),
            0x1E to f("128bppRGBAFixedPoint", JxrColor.RGB, JxrDepth.BD_32S, 128, 32, alpha = true),
            0x19 to f("128bppRGBAFloat", JxrColor.RGB, JxrDepth.BD_32F, 128, 32, alpha = true),
            0x10 to f("32bppPBGRA", JxrColor.RGB, JxrDepth.BD_8, 32, 8, alpha = true, premul = true, bgr = true),
            0x17 to f("64bppPRGBA", JxrColor.RGB, JxrDepth.BD_16, 64, 16, alpha = true, premul = true),
            0x1A to f("128bppPRGBAFloat", JxrColor.RGB, JxrDepth.BD_32F, 128, 32, alpha = true, premul = true),
            0x09 to f("16bppRGB555", JxrColor.RGB, JxrDepth.BD_5, 16, 5),
            0x0A to f("16bppRGB565", JxrColor.RGB, JxrDepth.BD_565, 16, 6),
            0x14 to f("32bppRGB101010", JxrColor.RGB, JxrDepth.BD_10, 32, 10),
            0x1C to f("32bppCMYK", JxrColor.CMYK, JxrDepth.BD_8, 32, 8),
            0x2C to f("40bppCMYKAlpha", JxrColor.CMYK, JxrDepth.BD_8, 40, 8, alpha = true),
            0x1F to f("64bppCMYK", JxrColor.CMYK, JxrDepth.BD_16, 64, 16),
            0x2D to f("80bppCMYKAlpha", JxrColor.CMYK, JxrDepth.BD_16, 80, 16, alpha = true),
            0x3D to f("32bppRGBE", JxrColor.RGBE, JxrDepth.BD_8, 32, 8),
        ) + nChannel(0x20, JxrDepth.BD_8, false) + nChannel(0x26, JxrDepth.BD_16, false) +
            nChannel(0x2E, JxrDepth.BD_8, true) + nChannel(0x34, JxrDepth.BD_16, true)

        /** The N-channel formats of 3 to 8 channels from GUID byte [first], with an alpha channel after them when [alpha]. */
        private fun nChannel(first: Int, bd: Int, alpha: Boolean): Map<Int, JxrPixelFormat> = (0 until 6).associate { k ->
            val sample = if (bd == JxrDepth.BD_16) 16 else 8
            val n = 3 + k + if (alpha) 1 else 0
            (first + k) to f("${n * sample}bpp${3 + k}Channels${if (alpha) "Alpha" else ""}", JxrColor.NCOMPONENT, bd, n * sample, sample, alpha)
        }

        /** The format of the 16 bytes at [at] of [data], a GUID stored as Windows stores one, or null if unknown. */
        fun of(data: ByteArray, at: Int): JxrPixelFormat? {
            fun b(i: Int) = data[at + i].toInt() and 0xFF
            fun hex(vararg i: Int) = i.joinToString("") { b(it).toString(16).padStart(2, '0') }
            val guid = "${hex(3, 2, 1, 0)}-${hex(5, 4)}-${hex(7, 6)}-${hex(8, 9)}-${hex(10, 11, 12, 13, 14, 15)}"
            OTHER[guid]?.let { return it }
            if (!guid.startsWith("6fddc324-4e03-4bfe-b185-3d77768dc9")) return null
            return FAMILY[b(15)]
        }
    }
}

/**
 * What the image file directory of a JPEG XR file (T.832 Annex A) says: its pixel format,
 * where the image and planar alpha codestreams lie, the orientation and the ICC profile.
 */
internal class JxrContainer(
    val pixelFormat: JxrPixelFormat,
    val imageOffset: Int,
    val imageEnd: Int,
    val alphaOffset: Int,
    val alphaEnd: Int,
    /** The Transformation tag, O_NONE (0) when the file has none, as jxrlib reads it to match Vista. */
    val orientation: Int,
    val icc: ByteArray?,
) {
    /** True when the alpha is a codestream of its own (planar alpha, T.832 A.7.17). */
    val planarAlpha: Boolean get() = pixelFormat.alpha && alphaOffset > 0 && alphaEnd > alphaOffset

    companion object {
        private const val TAG_PIXEL_FORMAT = 0xBC01
        private const val TAG_TRANSFORMATION = 0xBC02
        private const val TAG_IMAGE_OFFSET = 0xBCC0
        private const val TAG_IMAGE_BYTE_COUNT = 0xBCC1
        private const val TAG_ALPHA_OFFSET = 0xBCC2
        private const val TAG_ALPHA_BYTE_COUNT = 0xBCC3
        private const val TAG_ICC_PROFILE = 0x8773

        /** Reads the file directory of [data]. A codestream that runs past the end is damage, unless [lenient], as a probe of the first bytes is. */
        fun read(data: ByteArray, lenient: Boolean = false): JxrContainer {
            fun u16(at: Int): Int {
                if (at < 0 || at + 2 > data.size) jxrFail("the file directory runs past the end of the file")
                return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
            }
            fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
            if (data.size < 8 || data[0] != 'I'.code.toByte() || data[1] != 'I'.code.toByte() || (data[2].toInt() and 0xFF) != 0xBC) {
                jxrFail("no JPEG XR signature")
            }
            // Version 0 is HD Photo's and 1 JPEG XR's; jxrlib takes both and nothing else.
            val version = data[3].toInt() and 0xFF
            if (version > 1) jxrFail("file version $version")
            val ifd = u32(4)
            if (ifd > data.size - 2) jxrFail("the file directory lies past the end of the file")
            val entries = u16(ifd.toInt())
            if (entries == 0 || entries == 0xFFFF) jxrFail("an empty file directory")
            var format: JxrPixelFormat? = null
            var sawFormat = false
            var imageOffset = -1L
            var imageCount = 0L
            var alphaOffset = 0L
            var alphaCount = 0L
            var orientation = 0
            var icc: ByteArray? = null
            for (i in 0 until entries) {
                val at = ifd.toInt() + 2 + 12 * i
                val tag = u16(at)
                val type = u16(at + 2)
                val count = u32(at + 4)
                // A SHORT value sits in the low half of the value field, as in TIFF.
                val value = if (type == 3 && count == 1L) u16(at + 8).toLong() else u32(at + 8)
                when (tag) {
                    TAG_PIXEL_FORMAT -> {
                        if (value > data.size - 16) jxrFail("the pixel format lies past the end of the file")
                        sawFormat = true
                        format = JxrPixelFormat.of(data, value.toInt())
                    }
                    TAG_TRANSFORMATION -> {
                        if (count != 1L) jxrFail("a Transformation of $count values")
                        if (value in 0L..7L) orientation = value.toInt()
                    }
                    TAG_IMAGE_OFFSET -> {
                        if (count != 1L) jxrFail("an ImageOffset of $count values")
                        imageOffset = value
                    }
                    TAG_IMAGE_BYTE_COUNT -> {
                        if (count != 1L) jxrFail("an ImageByteCount of $count values")
                        imageCount = value
                    }
                    TAG_ALPHA_OFFSET -> {
                        if (count != 1L) jxrFail("an AlphaOffset of $count values")
                        alphaOffset = value
                    }
                    TAG_ALPHA_BYTE_COUNT -> {
                        if (count != 1L) jxrFail("an AlphaByteCount of $count values")
                        alphaCount = value
                    }
                    TAG_ICC_PROFILE -> if (count in 1L..(data.size.toLong() - value) && value >= 0) {
                        icc = data.copyOfRange(value.toInt(), (value + count).toInt())
                    }
                }
            }
            if (!sawFormat) jxrFail("no pixel format")
            val pf = format ?: throw io.github.yuroyami.imagekodec.UnsupportedImageException("JPEG XR: an unknown pixel format")
            if (imageOffset < 0 || imageOffset >= data.size) jxrFail("the image lies past the end of the file")
            fun end(offset: Long, count: Long): Int {
                if (offset + count > data.size && !lenient) jxrFail("the file ends ${offset + count - data.size} bytes before its codestream does")
                return if (count <= 0 || offset + count > data.size) data.size else (offset + count).toInt()
            }
            // JxrEncApp writes the size of the whole file as AlphaByteCount, so only the offset is held to the file.
            if (pf.alpha && alphaOffset > 0 && alphaCount > 0 && alphaOffset >= data.size && !lenient) jxrFail("the alpha plane lies past the end of the file")
            val hasAlpha = alphaOffset in 1 until data.size && alphaCount > 0
            return JxrContainer(
                pixelFormat = pf,
                imageOffset = imageOffset.toInt(),
                imageEnd = end(imageOffset, imageCount),
                alphaOffset = if (hasAlpha) alphaOffset.toInt() else 0,
                alphaEnd = if (hasAlpha) minOf(data.size.toLong(), alphaOffset + alphaCount).toInt() else 0,
                orientation = orientation,
                icc = icc,
            )
        }
    }
}
