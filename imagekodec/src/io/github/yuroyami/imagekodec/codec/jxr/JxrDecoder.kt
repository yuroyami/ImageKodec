/*
 * The JPEG XR facade: the pixel format setup of jxrlib's JxrDecApp (jxrencoderdecoder/JxrDecApp.c),
 * PKImageDecode_Copy_WMP and WMPhotoValidate (jxrgluelib/JXRGlueJxr.c, image/decode/strdec.c), and
 * the scRGB conversion of Convert_Half_To_Float and Convert_Float_To_U8 (jxrgluelib/JXRGluePFC.c),
 * at 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE.
 */
package io.github.yuroyami.imagekodec.codec.jxr

import io.github.yuroyami.imagekodec.ColorProfile
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageInfo
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.internal.color.ColorMath
import io.github.yuroyami.imagekodec.internal.color.Ink

/**
 * JPEG XR (ITU-T T.832 | ISO/IEC 29199-2), HD Photo and Windows Media Photo: reads the container
 * of Annex A, decodes the image codestream and its alpha, and gives the pixels or the description.
 *
 * Samples come out as jxrlib writes them for the file's pixel format, so they match `JxrDecApp`
 * sample for sample. The fixed-point, half and float formats hold linear scRGB, which is converted
 * to sRGB as jxrlib's format converter does before it writes 8 bits; their alpha is linear in any
 * case. Premultiplied colour is divided by its alpha.
 */
internal object JxrDecoder {

    /** The samples a decode writes, the format they are in, and what the file says about them. */
    internal class Decoded(val format: JxrPixelFormat, val raster: JxrRaster, val alpha: Boolean, val redHigh: Boolean = true)

    /** Colour and alpha at the pixel ceiling, as the other decoders count them. */
    private const val MAX_SAMPLES = 4 * Budget.MAX_PIXELS

    private class Parsed(val container: JxrContainer, val header: JxrImageHeader)

    private fun parse(data: ByteArray, lenient: Boolean = false): Parsed {
        val container = JxrContainer.read(data, lenient)
        val header = JxrImageHeader.read(data, container.imageOffset, container.imageEnd)
        return Parsed(container, header)
    }

    /**
     * Why this decoder cannot give the pixels of [p], or null when it can.
     *
     * CMYK decodes only from a YUVK codestream and YUVK only to CMYK, and an N-channel format only
     * from an NCOMPONENT codestream of as many channels: the pairings T.832's Table 183 allows.
     * jxrlib refuses the others too. CMYKDIRECT, which jxrlib does not read, is refused, and so is
     * N-channel alpha interleaved with the colour, which jxrlib writes only after RGB or CMYK.
     */
    private fun refusal(p: Parsed): String? {
        val pf = p.container.pixelFormat
        val h = p.header
        val rgbe = pf.colorFormat == JxrColor.RGBE
        return when {
            pf.colorFormat == JxrColor.CMYK && (h.sourceColorFormat != JxrColor.CMYK || h.plane.colorFormat != JxrColor.CMYK) ->
                "JPEG XR ${pf.name}: a codestream of colour format ${h.sourceColorFormat} and internal format ${h.plane.colorFormat}"
            pf.colorFormat == JxrColor.NCOMPONENT &&
                (h.sourceColorFormat != JxrColor.NCOMPONENT || h.plane.colorFormat != JxrColor.NCOMPONENT || h.plane.channels != pf.colorChannels) ->
                "JPEG XR ${pf.name}: a codestream of colour format ${h.sourceColorFormat}, internal format ${h.plane.colorFormat} and ${h.plane.channels} channels"
            pf.colorFormat != JxrColor.NCOMPONENT && (h.plane.colorFormat == JxrColor.NCOMPONENT || h.sourceColorFormat == JxrColor.NCOMPONENT) ->
                "JPEG XR: an N-channel codestream in ${pf.name}"
            pf.colorFormat == JxrColor.NCOMPONENT && h.alphaPlane -> "JPEG XR ${pf.name}: alpha interleaved with N channels"
            rgbe != (h.sourceColorFormat == JxrColor.RGBE) -> "JPEG XR: a codestream of colour format ${h.sourceColorFormat} in ${pf.name}"
            rgbe && (h.plane.colorFormat == JxrColor.Y_ONLY || p.container.planarAlpha || h.alphaPlane) -> "JPEG XR ${pf.name}: RGBE of colour format ${h.plane.colorFormat}"
            pf.colorFormat != JxrColor.CMYK && (h.plane.colorFormat == JxrColor.CMYK || h.sourceColorFormat == JxrColor.CMYK || h.sourceColorFormat == 5) ->
                "JPEG XR: a CMYK codestream in ${pf.name}"
            !Budget.fitsAbsolute(h.width, h.height) -> "JPEG XR image of ${h.width} by ${h.height} passes the pixel ceiling"
            h.width.toLong() * h.height * slots(pf) > MAX_SAMPLES -> "JPEG XR image of ${h.width} by ${h.height} in ${pf.name} passes the sample ceiling"
            h.mbWidth.toLong() * 512 * maxOf(h.plane.channels, 1) > MAX_SAMPLES -> "JPEG XR image ${h.width} wide passes the row ceiling"
            else -> null
        }
    }

    /** The values jxrlib writes a pixel of [pf]: one per sample, or one for a packed pixel. */
    private fun slots(pf: JxrPixelFormat): Int = when (pf.bitDepth) {
        JxrDepth.BD_1, JxrDepth.BD_5, JxrDepth.BD_565, JxrDepth.BD_10 -> 1
        JxrDepth.BD_8 -> pf.bitsPerPixel / 8
        JxrDepth.BD_16, JxrDepth.BD_16S, JxrDepth.BD_16F -> pf.bitsPerPixel / 16
        else -> pf.bitsPerPixel / 32
    }

    fun probe(data: ByteArray): ImageInfo {
        val p = parse(data, lenient = true)
        val pf = p.container.pixelFormat
        val h = p.header
        val reason = refusal(p)
        return ImageInfo(
            format = ImageFormat.JXR,
            width = h.width,
            height = h.height,
            bitDepth = pf.bitsPerSample,
            hasAlpha = pf.alpha && (h.alphaPlane || p.container.planarAlpha),
            frameCount = 1,
            loopCount = 1,
            orientation = orientation(p.container.orientation),
            isDecodable = reason == null,
            unsupportedReason = reason,
            colorProfile = p.container.icc?.let { ColorProfile(icc = it) },
        ).also { it.colorChannels = if (pf.colorFormat == JxrColor.Y_ONLY) 1 else if (ink(pf)) 4 else 3 }
    }

    /** The Transformation tag of T.832 Table A.4 as an EXIF orientation: rotation first, then the flips. */
    private fun orientation(o: Int): Orientation = Orientation.fromExif(intArrayOf(1, 4, 2, 3, 6, 7, 5, 8)[o])

    /**
     * The thumbnail scale jxrlib decodes a reduction of [reduction] at, or 0 when it would give
     * another size than [reduction] promises. JxrDecApp asks for a thumbnail of each side divided
     * by the reduction, rounded up; WMPhotoValidate then picks the scale the sizes give, and setROI
     * the power of 2 that covers the width.
     */
    internal fun thumbnailScale(width: Int, height: Int, reduction: Int): Int {
        val tw = (width + reduction - 1) / reduction
        val th = (height + reduction - 1) / reduction
        var scale = 1
        if ((width + tw - 1) / tw != (height + th - 1) / th) {
            while ((width + scale - 1) / scale > tw && (height + scale - 1) / scale > th) scale = scale shl 1
        } else {
            scale = maxOf(1, (width + tw - 1) / tw)
        }
        if ((width + scale - 1) / scale != tw || (height + scale - 1) / scale != th) return 0
        var p = 1
        while (p.toLong() * tw < width) p = p shl 1
        return if (p == reduction) p else 0
    }

    /** [decodeRaw] at a reduction of 2, 4 or 8, as jxrlib's thumbnail, or null where jxrlib would give another size. */
    internal fun decodeRawReduced(data: ByteArray, reduction: Int, cancel: (() -> Unit)? = null, asJxrlib: Boolean = true): Decoded? {
        val p = parse(data)
        val h = p.header
        // jxrlib's CMYK thumbnail is not ported, and its N-channel thumbnail puts alpha in the fourth
        // slot whatever the count, so these decode in full and average.
        val cf = p.container.pixelFormat.colorFormat
        if (cf == JxrColor.CMYK || cf == JxrColor.NCOMPONENT) return null
        val scale = thumbnailScale(h.width, h.height, reduction)
        if (scale == 0 || h.extraLeft != 0 || h.extraTop != 0) return null
        // jxrlib's quarter thumbnail of subsampled chroma cannot be narrower than 8 (its loop wraps),
        // so the facade decodes such an image in full instead of failing a file the full decode reads.
        if (!asJxrlib && scale == 4 && h.width < 8) return null
        return decodeRaw(data, cancel, scale, asJxrlib)
    }

    /** [decode] at a reduction of 2, 4 or 8 through [decodeRawReduced], or null where it gives none. */
    fun decodeReduced(data: ByteArray, reduction: Int, cancel: (() -> Unit)? = null): KiteBitmap? =
        decodeRawReduced(data, reduction, cancel, asJxrlib = false)?.let { bitmap(it) }

    /**
     * The samples of [data] as jxrlib writes them, with planar or interleaved alpha in its slot, at
     * thumbnail scale [scale]. [cancel] runs before each row of macroblocks.
     */
    internal fun decodeRaw(data: ByteArray, cancel: (() -> Unit)? = null, scale: Int = 1, asJxrlib: Boolean = true): Decoded {
        val p = parse(data)
        refusal(p)?.let { throw UnsupportedImageException(it) }
        val pf = p.container.pixelFormat
        val h = p.header
        if (h.sourceBitDepth != pf.bitDepth) jxrFail("a codestream of bit depth ${h.sourceBitDepth} in pixel format ${pf.name}")
        val raster = JxrRaster((h.width + scale - 1) / scale, (h.height + scale - 1) / scale, slots(pf))
        // WMPhotoValidate: the codestream's colour format overrides the pixel format's where they differ.
        var external = pf.colorFormat
        if (h.plane.colorFormat == JxrColor.YUV_422 && external == JxrColor.YUV_420) external = JxrColor.YUV_422
        if (h.plane.colorFormat == JxrColor.YUV_444 && (external == JxrColor.YUV_422 || external == JxrColor.YUV_420)) external = JxrColor.YUV_444
        if (h.sourceColorFormat == JxrColor.RGB && external != JxrColor.Y_ONLY) external = JxrColor.RGB
        val planar = p.container.planarAlpha && !h.alphaPlane
        if (pf.colorFormat == JxrColor.RGBE) {
            // The codestream holds each channel as an integer of its own exponent; the decode gives
            // those integers, after the inverse colour transform, as signed 32-bit RGB.
            val internal = JxrRaster(raster.width, raster.height, 3)
            val wide = JxrOutputFormat(JxrColor.RGB, JxrDepth.BD_32S, rgb = true, alpha = false, leadingPadding = 0)
            JxrCodestream.decode(data, p.container.imageOffset, p.container.imageEnd, wide, internal, cancel, scale, asJxrlib)
            toRgbe(internal, raster)
            return Decoded(pf, raster, alpha = false)
        }
        val format = JxrOutputFormat(external, h.sourceBitDepth, rgb = !pf.bgr, alpha = pf.alpha && h.alphaPlane, leadingPadding = 0)
        JxrCodestream.decode(data, p.container.imageOffset, p.container.imageEnd, format, raster, cancel, scale, asJxrlib)
        if (planar) {
            val alphaHeader = JxrImageHeader.read(data, p.container.alphaOffset, p.container.alphaEnd)
            if (alphaHeader.sourceBitDepth != pf.bitDepth) jxrFail("an alpha plane of bit depth ${alphaHeader.sourceBitDepth} in pixel format ${pf.name}")
            val alphaFormat = JxrOutputFormat(JxrColor.Y_ONLY, alphaHeader.sourceBitDepth, rgb = true, alpha = false, leadingPadding = raster.slots - 1)
            JxrCodestream.decode(data, p.container.alphaOffset, p.container.alphaEnd, alphaFormat, raster, cancel, scale, asJxrlib)
        }
        // The codec's red of a packed format is the picture's blue, as jxrlib's RGB555_RGB24 reads it: the full
        // decode packs it low unless the codestream swaps red and blue, and a thumbnail always packs it low.
        return Decoded(pf, raster, pf.alpha && (h.alphaPlane || planar), redHigh = scale != 1 || !h.rbSwapped)
    }

    /**
     * The RGBE pixels of [internal], each channel's integer of T.832's RGBE coding, into [raster] as
     * jxrlib writes 32bppRGBE: a mantissa a channel and the exponent they share. An integer i of a
     * channel holds the exponent i >> 7 and the mantissa 128 + (i & 127), or, below 128, the mantissa
     * i at exponent 1; the shared exponent is the largest, and each mantissa is shifted down to it.
     * A pixel of no positive channel is 0 in all four.
     */
    private fun toRgbe(internal: JxrRaster, raster: JxrRaster) {
        val src = internal.samples
        val out = raster.samples
        val e = IntArray(3)
        val m = IntArray(3)
        for (i in 0 until internal.width * internal.height) {
            var shared = 0
            for (c in 0 until 3) {
                val v = src[i * 3 + c]
                if (v <= 0) {
                    e[c] = 0; m[c] = 0
                } else {
                    e[c] = maxOf(1, v shr 7)
                    m[c] = if (v >= 128) (v and 127) or 128 else v
                    shared = maxOf(shared, e[c])
                }
            }
            if (shared > 255) jxrFail("an RGBE exponent of $shared")
            for (c in 0 until 3) out[i * 4 + c] = if (m[c] == 0) 0 else m[c] shr minOf(31, shared - e[c])
            out[i * 4 + 3] = shared
        }
    }

    fun decode(data: ByteArray, cancel: (() -> Unit)? = null): KiteBitmap = bitmap(decodeRaw(data, cancel))

    private fun bitmap(d: Decoded): KiteBitmap {
        val pf = d.format
        if (pf.bitDepth != JxrDepth.BD_8 || pf.colorFormat == JxrColor.RGBE) return to16(d).toBitmap()
        val r = d.raster
        val s = r.samples
        val argb = IntArray(r.width * r.height)
        var o = 0
        val bgr = pf.bgr
        for (i in argb.indices) {
            argb[i] = if (pf.colorFormat == JxrColor.Y_ONLY) {
                0xFF000000.toInt() or (s[o] * 0x010101)
            } else if (ink(pf)) {
                // TIFF's naive CMYK, as TiffDecoder and libtiff's putRGBcontig8bitCMYKtile draw it, truncating.
                val k = 255 - s[o + 3]
                val a = if (d.alpha) s[o + pf.colorChannels] else 255
                (a shl 24) or ((k * (255 - s[o]) / 255) shl 16) or ((k * (255 - s[o + 1]) / 255) shl 8) or (k * (255 - s[o + 2]) / 255)
            } else {
                var red = s[o + if (bgr) 2 else 0]
                var green = s[o + 1]
                var blue = s[o + if (bgr) 0 else 2]
                val a = if (d.alpha) s[o + 3] else 255
                if (pf.premultiplied) {
                    red = straight(red, a, 255)
                    green = straight(green, a, 255)
                    blue = straight(blue, a, 255)
                }
                (a shl 24) or (red shl 16) or (green shl 8) or blue
            }
            o += r.slots
        }
        return KiteBitmap(r.width, r.height, argb)
    }

    fun decode16(data: ByteArray, cancel: (() -> Unit)? = null): KiteBitmap16 {
        val d = decodeRaw(data, cancel)
        // 8-bit ink widens what decode draws, as an 8-bit CMYK TIFF's does.
        return if (ink(d.format) && d.format.bitDepth == JxrDepth.BD_8) KiteBitmap16.widened(bitmap(d), alpha = d.alpha) else to16(d)
    }

    /** Colour divided by its alpha, 0 where the alpha is, as TIFF 6.0 section 18 asks of associated alpha. */
    private fun straight(value: Int, a: Int, max: Int): Int =
        if (a == 0) 0 else minOf(max.toLong(), (value.toLong() * max + a / 2) / a).toInt()

    private fun to16(d: Decoded): KiteBitmap16 {
        val pf = d.format
        val r = d.raster
        val s = r.samples
        val gray = pf.colorFormat == JxrColor.Y_ONLY
        val cmyk = ink(pf)
        val channels = (if (gray) 1 else 3) + (if (d.alpha) 1 else 0)
        val out = ShortArray(r.width * r.height * channels)
        val bd = pf.bitDepth
        var o = 0
        var w = 0
        val colorOrder = if (gray) intArrayOf(0) else if (pf.bgr) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)
        val alphaSlot = pf.colorChannels
        val v = IntArray(4)
        repeat(r.width * r.height) {
            when {
                cmyk -> {
                    val k = sample16(s[o + 3], bd, alpha = true)
                    for (c in 0 until 3) v[c] = cmyk16(sample16(s[o + c], bd, alpha = true), k)
                }
                pf.colorFormat == JxrColor.RGBE -> {
                    // RGBE_RGB96Float of JXRGluePFC.c: a mantissa times 2^(E - 136), or 0 at an exponent of 0.
                    val exponent = s[o + 3]
                    for (c in 0 until 3) v[c] = if (exponent == 0) 0 else linear(s[o + c] * twoTo(exponent - 136), alpha = false)
                }
                bd == JxrDepth.BD_5 -> {
                    val x = s[o]
                    for (c in 0 until 3) v[c] = widen((x shr (if (d.redHigh) 10 - 5 * c else 5 * c)) and 31, 5)
                }
                bd == JxrDepth.BD_565 -> {
                    val x = s[o]
                    v[0] = widen((x shr (if (d.redHigh) 11 else 0)) and 31, 5)
                    v[1] = widen((x shr 5) and 63, 6)
                    v[2] = widen((x shr (if (d.redHigh) 0 else 11)) and 31, 5)
                }
                bd == JxrDepth.BD_10 -> {
                    val x = s[o]
                    for (c in 0 until 3) v[c] = widen((x shr (if (d.redHigh) 20 - 10 * c else 10 * c)) and 1023, 10)
                }
                else -> {
                    for (c in colorOrder.indices) v[c] = sample16(s[o + colorOrder[c]], bd, alpha = false)
                }
            }
            if (d.alpha) v[colorOrder.size] = sample16(s[o + alphaSlot], bd, alpha = true)
            if (pf.premultiplied && d.alpha) {
                val a = v[colorOrder.size]
                for (c in colorOrder.indices) v[c] = straight(v[c], a, 65535)
            }
            for (c in 0 until channels) out[w + c] = v[c].toShort()
            w += channels
            o += r.slots
        }
        return KiteBitmap16(r.width, r.height, channels, out)
    }

    /**
     * Whether [pf] holds ink: CMYK, or an N-channel format of four channels or more, whose first
     * four T.832 Annex C has read as CMYK when nothing else names their colour, the rest dropped.
     * Three N channels are RGB there.
     */
    private fun ink(pf: JxrPixelFormat): Boolean =
        pf.colorFormat == JxrColor.CMYK || (pf.colorFormat == JxrColor.NCOMPONENT && pf.colorChannels >= 4)

    /** A 16-bit colorant [c] under black [k] as light, TiffDecoder's 16-bit form of libtiff's CMYK formula, rounded. */
    private fun cmyk16(c: Int, k: Int): Int = (((65535L - k) * (65535 - c) + 32767) / 65535).toInt()

    /**
     * The CMYK ink of [data] for a conversion through its profile, at 16 bits with [wide] and else
     * at 8, or null when it is not CMYK.
     */
    fun decodeInk(data: ByteArray, wide: Boolean, cancel: (() -> Unit)? = null): Ink? {
        if (!ink(parse(data, lenient = true).container.pixelFormat)) return null
        val d = decodeRaw(data, cancel)
        val r = d.raster
        val s = r.samples
        val n = r.width * r.height
        val deep = d.format.bitDepth == JxrDepth.BD_16
        fun at8(v: Int) = if (deep) (v * 255 + 32767) / 65535 else v
        fun at16(v: Int) = if (deep) v else v * 257
        return if (wide) {
            Ink(
                r.width, r.height,
                ink16 = ShortArray(n * 4) { at16(s[(it / 4) * r.slots + it % 4]).toShort() },
                alpha16 = if (d.alpha) ShortArray(n) { at16(s[it * r.slots + d.format.colorChannels]).toShort() } else null,
            )
        } else {
            Ink(
                r.width, r.height,
                ink = ByteArray(n * 4) { at8(s[(it / 4) * r.slots + it % 4]).toByte() },
                alpha = if (d.alpha) ByteArray(n) { at8(s[it * r.slots + d.format.colorChannels]).toByte() } else null,
            )
        }
    }

    /** 2 to the power [e], exactly, for an exponent a binary64 holds as a normal number. */
    private fun twoTo(e: Int): Double = Double.fromBits((1023L + e) shl 52)

    /** A sample of [bits] bits widened to 16 by repeating its bits. */
    private fun widen(x: Int, bits: Int): Int {
        var v = 0
        var at = 16 - bits
        while (at > -bits) {
            v = if (at >= 0) v or (x shl at) else v or (x shr -at)
            at -= bits
        }
        return v and 0xFFFF
    }

    /** One sample at 16 bits: as stored, widened, or a linear value converted to sRGB unless it is alpha. */
    private fun sample16(x: Int, bd: Int, alpha: Boolean): Int = when (bd) {
        JxrDepth.BD_1 -> if (x != 0) 65535 else 0
        JxrDepth.BD_8 -> x * 257
        JxrDepth.BD_16 -> x
        JxrDepth.BD_16S -> linear(x / 8192.0, alpha)
        JxrDepth.BD_16F -> linear(half(x), alpha)
        JxrDepth.BD_32S -> linear(x / 16777216.0, alpha)
        else -> linear(Float.fromBits(x).toDouble(), alpha)
    }

    /** A half's value (Convert_Half_To_Float of JXRGluePFC.c, which reads a denormal as 0). */
    private fun half(h: Int): Double {
        val e = (h shr 10) and 0x1F
        val m = h and 0x3FF
        val sign = if (h and 0x8000 != 0) -1.0 else 1.0
        return when (e) {
            0 -> 0.0
            31 -> if (m == 0) sign * Double.POSITIVE_INFINITY else Double.NaN
            else -> sign * (1.0 + m / 1024.0) * (1 shl e).toDouble() / 32768.0
        }
    }

    /** A linear value at 16 bits: sRGB-encoded colour, as Convert_Float_To_U8 does at 8, or alpha as it is. */
    private fun linear(f: Double, alpha: Boolean): Int {
        if (!(f > 0)) return 0
        if (f >= 1) return 65535
        val e = if (alpha) f else ColorMath.srgbEncode(f)
        return (e * 65535 + 0.5).toInt().coerceIn(0, 65535)
    }
}
