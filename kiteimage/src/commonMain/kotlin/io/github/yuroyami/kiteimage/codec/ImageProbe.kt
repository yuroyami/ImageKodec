package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.ImageFormat
import io.github.yuroyami.kiteimage.ImageInfo
import io.github.yuroyami.kiteimage.Orientation
import io.github.yuroyami.kiteimage.internal.ByteReader

/**
 * Header-only inspection: dimensions, depth, alpha, frame count and orientation
 * without decoding a single pixel. Backs `KiteImage.probe`.
 *
 * Each format gets the cheapest walk that still gives an exact answer:
 *
 *  - PNG/APNG: IHDR, plus the chunk walk up to the first data chunk for `tRNS`
 *    and `acTL` (frame count and loop count live there)
 *  - JPEG: marker walk to the first SOF, plus the APP1 EXIF orientation
 *  - GIF: logical screen descriptor, then the block walk counting image
 *    descriptors: sub-block payloads are skipped, never LZW-decoded
 *  - BMP/TIFF/JP2/WebP: fixed header fields (and, for animated WebP, the ANMF
 *    chunk count)
 *
 * [ImageInfo.isDecodable] mirrors the decoders' feature refusals, so a Coil-style
 * "should I claim this file?" question is one call rather than a hand-rolled
 * header sniff per format. It answers about features, not about integrity: a
 * truncated or corrupt file that declares only supported features still probes as
 * decodable and then fails, and for JPEG 2000 the check covers the main header
 * but not per-tile overrides.
 */
internal object ImageProbe {

    /**
     * JpxDecoder's own output ceiling. It is the one codec that does not go
     * through the shared input-relative budget, and its limit is lower.
     */
    private const val MAX_JP2_PIXELS = 64L shl 20

    fun probe(data: ByteArray): ImageInfo = when (ImageFormat.sniff(data)) {
        ImageFormat.PNG -> png(data)
        ImageFormat.JPEG -> jpeg(data)
        ImageFormat.GIF -> gif(data)
        ImageFormat.BMP -> bmp(data)
        ImageFormat.TIFF -> tiff(data)
        ImageFormat.JP2 -> jp2(data)
        ImageFormat.WEBP -> webp(data)
        null -> throw ImageDecodeException(
            "unrecognised image format (${data.size} bytes)",
        )
    }

    // --- PNG --------------------------------------------------------------------

    private fun png(data: ByteArray): ImageInfo {
        val r = ByteReader(data, pos = 8)

        var width = 0
        var height = 0
        var depth = 8
        var colorType = 0
        var hasTrns = false
        var frames = 1
        var loops = 1
        var apple = false
        var sawIhdr = false

        walk@ while (r.remaining >= 8) {
            val len = r.u32be()
            if (len > Int.MAX_VALUE.toLong()) throw ImageDecodeException("PNG: absurd chunk length $len")
            val type = r.bytes(4).decodeToString()
            val n = len.toInt()
            when (type) {
                "CgBI" -> { apple = true; r.skip(minOf(n, r.remaining)) }
                "IHDR" -> {
                    if (n != 13) throw ImageDecodeException("PNG: IHDR length $n")
                    val h = ByteReader(r.bytes(13))
                    width = h.u32be().toInt()
                    height = h.u32be().toInt()
                    depth = h.u8()
                    colorType = h.u8()
                    sawIhdr = true
                }
                "tRNS" -> { hasTrns = true; r.skip(minOf(n, r.remaining)) }
                "acTL" -> {
                    if (n < 8) throw ImageDecodeException("PNG: acTL length $n")
                    val a = ByteReader(r.bytes(n))
                    frames = a.u32be().toInt()
                    // APNG num_plays uses the same 0-means-forever rule as GIF.
                    loops = a.u32be().toInt()
                }
                // Pixel data begins: everything we care about is legally before it.
                "IDAT", "fdAT", "IEND" -> break@walk
                else -> r.skip(minOf(n, r.remaining))
            }
            if (r.remaining < 4) break@walk
            r.skip(4)   // CRC; probing does not verify (the decoder does)
        }

        if (!sawIhdr) throw ImageDecodeException("PNG: no IHDR")
        if (frames < 1) frames = 1

        return ImageInfo(
            format = ImageFormat.PNG,
            width = width,
            height = height,
            bitDepth = depth,
            hasAlpha = colorType == 4 || colorType == 6 || hasTrns,
            frameCount = frames,
            loopCount = loops,
            orientation = Orientation.Normal,
            isDecodable = !apple,
            unsupportedReason = if (apple) {
                "Apple CgBI PNG (Xcode-crushed, raw deflate + swapped channels)"
            } else null,
        )
    }

    // --- JPEG -------------------------------------------------------------------

    private fun jpeg(data: ByteArray): ImageInfo {
        val r = ByteReader(data, pos = 2)
        while (true) {
            var marker = r.u8()
            if (marker != 0xFF) throw ImageDecodeException("JPEG: expected a marker at ${r.pos - 1}")
            while (marker == 0xFF) marker = r.u8()

            when (marker) {
                0x01, in 0xD0..0xD8 -> continue                     // standalone, no segment
                0xD9, 0xDA -> throw ImageDecodeException("JPEG: no frame header before the scan")
            }

            val len = r.u16be()
            if (len < 2) throw ImageDecodeException("JPEG: segment length $len")
            val payload = r.bytes(len - 2)

            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (!isSof) continue

            val f = ByteReader(payload)
            val precision = f.u8()
            val height = f.u16be()
            val width = f.u16be()
            val components = f.u8()

            // Everything JpegDecoder.processFrameHeader refuses, in its order.
            val why = when (marker) {
                0xC3, 0xC7, 0xCB, 0xCF -> "lossless JPEG"
                0xC5, 0xC6 -> "hierarchical/differential JPEG"
                0xC9, 0xCA, 0xCD, 0xCE -> "arithmetic-coded JPEG"
                0xC0, 0xC1, 0xC2 -> when {
                    precision != 8 -> "$precision-bit JPEG (8-bit samples only)"
                    height == 0 -> "JPEG with its height deferred to a DNL marker"
                    components != 1 && components != 3 && components != 4 ->
                        "$components-component JPEG"
                    else -> null
                }
                else -> "JPEG SOF marker 0x${marker.toString(16)}"
            }

            return ImageInfo(
                format = ImageFormat.JPEG,
                width = width,
                height = height,
                bitDepth = precision,
                // JPEG has no alpha channel. CMYK/YCCK carry a fourth ink, not alpha.
                hasAlpha = false,
                frameCount = 1,
                loopCount = 1,
                orientation = Exif.orientationFromJpeg(data) ?: Orientation.Normal,
                isDecodable = why == null,
                unsupportedReason = why,
            )
        }
    }

    // --- GIF --------------------------------------------------------------------

    private fun gif(data: ByteArray): ImageInfo {
        val r = ByteReader(data, pos = 6)
        val width = r.u16le()
        val height = r.u16le()
        val packed = r.u8()
        r.skip(2)
        if (packed and 0x80 != 0) r.skip(3 shl ((packed and 0x07) + 1))

        var frames = 0
        var loops = 1
        var transparency = false

        walk@ while (r.remaining > 0) {
            when (r.u8()) {
                0x2C -> {                                    // image descriptor
                    r.skip(8)                                // left, top, width, height
                    val fPacked = r.u8()
                    if (fPacked and 0x80 != 0) r.skip(3 shl ((fPacked and 0x07) + 1))
                    r.skip(1)                                // LZW minimum code size
                    skipSubBlocks(r)                         // compressed data, never decoded
                    frames++
                }
                0x21 -> {                                    // extension
                    val label = r.u8()
                    if (label == 0xF9) {                     // graphic control
                        val size = r.u8()
                        if (size >= 1) {
                            val flags = r.u8()
                            if (flags and 0x01 != 0) transparency = true
                            r.skip(size - 1)
                        }
                        skipSubBlocks(r)
                    } else if (label == 0xFF) {              // application
                        val size = r.u8()
                        val name = if (size in 1..r.remaining) r.bytes(size).decodeToString() else ""
                        if (name.startsWith("NETSCAPE") || name.startsWith("ANIMEXTS")) {
                            // sub-block: 1-byte id (1) + u16le loop count
                            val n = r.u8()
                            if (n >= 3) {
                                r.skip(1)
                                loops = r.u16le()
                                r.skip(n - 3)
                            } else {
                                r.skip(n)
                            }
                        }
                        skipSubBlocks(r)
                    } else {
                        skipSubBlocks(r)
                    }
                }
                0x3B -> break@walk                           // trailer
                else -> break@walk                           // junk: stop, report what we have
            }
        }

        return ImageInfo(
            format = ImageFormat.GIF,
            width = width,
            height = height,
            bitDepth = 8,
            hasAlpha = transparency,
            frameCount = maxOf(1, frames),
            loopCount = loops,
            orientation = Orientation.Normal,
            // [GifDecoder] implements every GIF87a/89a feature and throws no
            // UnsupportedImageException, so there is nothing for a header read to
            // rule out. A malformed or bomb-sized file still fails at decode.
            isDecodable = true,
        )
    }

    private fun skipSubBlocks(r: ByteReader) {
        while (r.remaining > 0) {
            val n = r.u8()
            if (n == 0) return
            if (n > r.remaining) { r.pos = r.pos + r.remaining; return }
            r.skip(n)
        }
    }

    // --- BMP --------------------------------------------------------------------

    private fun bmp(data: ByteArray): ImageInfo {
        val r = ByteReader(data, pos = 14)
        val dibSize = r.u32le().toInt()

        val width: Int
        val height: Int
        val bpp: Int
        var compression = 0
        when (dibSize) {
            12 -> {                                          // BITMAPCOREHEADER
                width = r.u16le()
                height = r.u16le()
                r.skip(2)
                bpp = r.u16le()
            }
            40, 52, 56, 64, 108, 124 -> {
                width = r.i32le()
                val raw = r.i32le()
                height = if (raw < 0) -raw else raw
                r.skip(2)
                bpp = r.u16le()
                compression = r.u32le().toInt()
            }
            else -> throw ImageDecodeException("BMP: unknown DIB header size $dibSize")
        }

        // 32-bit BI_RGB alpha is usually a lie (all zero); BI_BITFIELDS/V4/V5 with
        // an alpha mask is the honest signal. Report the optimistic answer and let
        // the decoder's all-zero-alpha rule settle it.
        val hasAlpha = bpp == 32

        return ImageInfo(
            format = ImageFormat.BMP,
            width = width,
            height = height,
            bitDepth = if (bpp >= 8) 8 else bpp,
            hasAlpha = hasAlpha,
            frameCount = 1,
            loopCount = 1,
            orientation = Orientation.Normal,
            isDecodable = compression in intArrayOf(0, 1, 2, 3, 6) &&
                bpp in intArrayOf(1, 2, 4, 8, 16, 24, 32),
            unsupportedReason = when {
                compression == 4 -> "BMP wrapping a JPEG (BI_JPEG)"
                compression == 5 -> "BMP wrapping a PNG (BI_PNG)"
                compression !in intArrayOf(0, 1, 2, 3, 6) -> "BMP compression $compression"
                bpp !in intArrayOf(1, 2, 4, 8, 16, 24, 32) -> "BMP with $bpp bpp"
                else -> null
            },
        )
    }

    // --- TIFF -------------------------------------------------------------------

    private fun tiff(data: ByteArray): ImageInfo {
        val le = data[0] == 'I'.code.toByte()
        fun u16(at: Int): Int {
            if (at < 0 || at + 2 > data.size) throw ImageDecodeException("TIFF: truncated at $at")
            val a = data[at].toInt() and 0xFF
            val b = data[at + 1].toInt() and 0xFF
            return if (le) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(at: Int): Int {
            val lo = u16(if (le) at else at + 2)
            val hi = u16(if (le) at + 2 else at)
            val v = lo.toLong() or (hi.toLong() shl 16)
            if (v > Int.MAX_VALUE) throw ImageDecodeException("TIFF: offset $v out of range")
            return v.toInt()
        }

        val ifd = u32(4)
        val count = u16(ifd)
        var width = 0
        var height = 0
        var bits = 1
        var spp = 1
        var extraSamples = 0
        var orientation = Orientation.Normal
        // TIFF defaults, used when the tag is absent. Photometric has none, so -1
        // stands for "the file does not say". That is a malformed-file question
        // rather than a feature-support one, and it is left to the decoder.
        var compression = 1
        var photometric = -1
        var predictor = 1
        var t4Options = 0

        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            if (at + 12 > data.size) break
            val tag = u16(at)
            val type = u16(at + 2)
            val n = u32(at + 4)
            // BYTE, SHORT and LONG scalars sit inline; arrays point elsewhere. We
            // only read scalars plus the first element of BitsPerSample.
            fun scalar(): Int = when (type) {
                1 -> data[at + 8].toInt() and 0xFF
                3 -> u16(at + 8)
                4 -> u32(at + 8)
                else -> 0
            }
            // A count of exactly one is the only shape where the value is both
            // certainly inline and certainly the one TiffDecoder reads, so the
            // feature checks below never guess.
            fun single(): Int? = if (n == 1 && (type == 1 || type == 3 || type == 4)) scalar() else null
            // The first element of a BYTE/SHORT/LONG field, inline when the whole
            // array fits the 4-byte slot and behind the pointer otherwise. Same
            // rule TiffDecoder.values() applies, so the two agree on arrays.
            fun first(): Int? {
                val unit = when (type) { 1 -> 1; 3 -> 2; 4 -> 4; else -> return null }
                if (n < 1) return null
                val base = if (unit.toLong() * n <= 4) at + 8 else u32(at + 8)
                return when (unit) {
                    1 -> if (base >= 0 && base < data.size) data[base].toInt() and 0xFF else null
                    2 -> u16(base)
                    else -> u32(base)
                }
            }
            when (tag) {
                256 -> width = scalar()
                257 -> height = scalar()
                258 -> bits = first() ?: bits
                259 -> compression = single() ?: compression
                262 -> photometric = single() ?: photometric
                274 -> orientation = Orientation.fromExif(scalar())
                277 -> spp = scalar()
                292 -> t4Options = single() ?: t4Options
                317 -> predictor = single() ?: predictor
                338 -> extraSamples = if (n >= 1) 1 else 0
            }
        }

        val alpha = extraSamples > 0 || spp == 2 || spp == 4
        val reason = tiffUnsupported(bits, compression, photometric, predictor, t4Options)
        return ImageInfo(
            format = ImageFormat.TIFF,
            width = width,
            height = height,
            bitDepth = bits,
            hasAlpha = alpha,
            frameCount = 1,
            loopCount = 1,
            orientation = orientation,
            isDecodable = reason == null,
            unsupportedReason = reason,
        )
    }

    /**
     * The features [TiffDecoder] refuses, tested in the order the decoder reaches
     * them so probe and decode name the same one. Everything else a TIFF can be
     * wrong about is malformed structure, which only a decode can find.
     */
    private fun tiffUnsupported(
        bits: Int,
        compression: Int,
        photometric: Int,
        predictor: Int,
        t4Options: Int,
    ): String? = when {
        bits !in intArrayOf(1, 2, 4, 8, 16) ->
            "TIFF with $bits bits per sample (1, 2, 4, 8 and 16 are decodable)"
        photometric == 6 && bits != 8 -> "TIFF YCbCr with $bits-bit samples (8-bit only)"
        compression !in intArrayOf(1, 2, 3, 4, 5, 8, 32773, 32946) ->
            "TIFF compression $compression" + if (compression == 6 || compression == 7) " (JPEG-in-TIFF)" else ""
        compression == 3 && (t4Options and 1) != 0 -> "TIFF CCITT G3 two-dimensional coding (T4Options bit 0)"
        predictor !in intArrayOf(1, 2) -> "TIFF predictor $predictor"
        // Horizontal differencing is only implemented for whole-byte samples.
        predictor == 2 && bits != 8 && bits != 16 -> "TIFF predictor 2 with $bits-bit samples"
        photometric >= 0 && photometric !in intArrayOf(0, 1, 2, 3, 6) ->
            "TIFF photometric interpretation $photometric"
        else -> null
    }

    // --- JPEG 2000 --------------------------------------------------------------

    private fun jp2(data: ByteArray): ImageInfo {
        // Locate the codestream: raw J2K starts with SOC (FF 4F); a JP2 container
        // wraps it in a "jp2c" box.
        var at = when {
            data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0x4F -> 0
            else -> jp2cOffset(data) ?: throw ImageDecodeException("JP2: no contiguous codestream box")
        }

        fun u16(p: Int): Int {
            if (p + 2 > data.size) throw ImageDecodeException("JP2: truncated codestream")
            return ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
        }
        fun u32(p: Int): Long = (u16(p).toLong() shl 16) or u16(p + 2).toLong()

        if (u16(at) != 0xFF4F) throw ImageDecodeException("JP2: codestream does not start with SOC")
        at += 2
        if (u16(at) != 0xFF51) throw ImageDecodeException("JP2: SIZ must follow SOC")

        // SIZ: Lsiz(2) Rsiz(2) Xsiz(4) Ysiz(4) XOsiz(4) YOsiz(4) XTsiz(4) YTsiz(4)
        //      XTOsiz(4) YTOsiz(4) Csiz(2) then per-component Ssiz/XRsiz/YRsiz.
        val siz = at + 2
        val xsiz = u32(siz + 4)
        val ysiz = u32(siz + 8)
        val xo = u32(siz + 12)
        val yo = u32(siz + 16)
        val comps = u16(siz + 36)
        val ssiz = if (siz + 38 < data.size) (data[siz + 38].toInt() and 0x7F) + 1 else 8

        val w = (xsiz - xo)
        val h = (ysiz - yo)
        if (w <= 0 || h <= 0 || w > Int.MAX_VALUE || h > Int.MAX_VALUE) {
            throw ImageDecodeException("JP2: bad image size ${w}x$h")
        }

        val reason = jp2Unsupported(data, siz, comps, w.toInt(), h.toInt())
        return ImageInfo(
            format = ImageFormat.JP2,
            width = w.toInt(),
            height = h.toInt(),
            bitDepth = ssiz,
            hasAlpha = comps == 2 || comps == 4,
            frameCount = 1,
            loopCount = 1,
            orientation = Orientation.Normal,
            isDecodable = reason == null,
            unsupportedReason = reason,
        )
    }

    /**
     * Walk the JPEG 2000 main header for the features [JpxDecoder] declines, so
     * `probe` can name one instead of the decode failing later.
     *
     * [siz] is the offset of the SIZ segment's length field. The walk covers
     * marker segments only and stops at the first tile-part (SOT), so a per-tile
     * coding-style override or a PPT segment inside a tile-part header is not
     * seen here and still surfaces as a decode failure. Nor are the COD and QCD
     * parameter ranges checked beyond the code-block style.
     */
    private fun jp2Unsupported(data: ByteArray, siz: Int, comps: Int, w: Int, h: Int): String? {
        if (comps !in 1..16) return "JPEG 2000 with $comps components (1 to 16 are decodable)"
        if (w.toLong() * h > MAX_JP2_PIXELS) {
            return "JPEG 2000 larger than ${MAX_JP2_PIXELS shr 20} megapixels"
        }
        // Per-component Ssiz/XRsiz/YRsiz triples follow Csiz.
        for (c in 0 until comps) {
            val at = siz + 38 + c * 3
            if (at + 3 > data.size) break
            val bits = (data[at].toInt() and 0x7F) + 1
            if (bits > 16) return "JPEG 2000 with $bits-bit samples (16-bit maximum)"
            val dx = data[at + 1].toInt() and 0xFF
            val dy = data[at + 2].toInt() and 0xFF
            if (dx == 0 || dy == 0) return "JPEG 2000 with a zero component subsampling factor"
        }

        fun u16(p: Int): Int? =
            if (p < 0 || p + 2 > data.size) null
            else ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)

        fun u8(p: Int): Int? = if (p < 0 || p >= data.size) null else data[p].toInt() and 0xFF

        fun codeBlockStyle(at: Int): String? {
            val style = u8(at) ?: return null
            return if (style == 0) null else "JPEG 2000 code-block style 0x${style.toString(16)}"
        }

        var p = siz + (u16(siz) ?: return null)                    // past the SIZ segment
        while (true) {
            val marker = u16(p) ?: return null
            if (marker < 0xFF00) return null                       // lost sync; leave it to the decode
            when (marker) {
                0xFF90, 0xFF93, 0xFFD9 -> return null              // SOT / SOD / EOC: main header over
                0xFF5E -> return "JPEG 2000 region of interest (RGN)"
                0xFF5F -> return "JPEG 2000 progression order change (POC)"
                0xFF60, 0xFF61 -> return "JPEG 2000 packed packet headers (PPM/PPT)"
                // COD: marker(2) Lcod(2) Scod SGcod(4) then SPcod decomposition,
                // code-block width, height, style.
                0xFF52 -> codeBlockStyle(p + 12)?.let { return it }
                // COC: the same style byte, past a Ccoc that widens with Csiz.
                0xFF53 -> codeBlockStyle(p + 8 + if (comps < 257) 1 else 2)?.let { return it }
            }
            if (marker in 0xFF30..0xFF3F) {                        // standalone, no segment
                p += 2
                continue
            }
            val len = u16(p + 2) ?: return null
            if (len < 2) return null
            p += 2 + len
        }
    }

    /** Walk JP2 boxes for the "jp2c" contiguous codestream; returns its payload offset. */
    private fun jp2cOffset(data: ByteArray): Int? {
        var p = 0
        while (p + 8 <= data.size) {
            var len = ((data[p].toInt() and 0xFF).toLong() shl 24) or
                ((data[p + 1].toInt() and 0xFF).toLong() shl 16) or
                ((data[p + 2].toInt() and 0xFF).toLong() shl 8) or
                (data[p + 3].toInt() and 0xFF).toLong()
            val type = data.copyOfRange(p + 4, p + 8).decodeToString()
            var header = 8
            if (len == 1L) {                                   // 64-bit extended length
                if (p + 16 > data.size) return null
                var xl = 0L
                for (i in 0 until 8) xl = (xl shl 8) or (data[p + 8 + i].toLong() and 0xFF)
                len = xl
                header = 16
            } else if (len == 0L) {
                len = (data.size - p).toLong()                 // "to end of file"
            }
            if (type == "jp2c") return p + header
            if (len < header) return null
            p += len.toInt()
        }
        return null
    }

    // --- WebP -------------------------------------------------------------------

    private fun webp(data: ByteArray): ImageInfo {
        var p = 12                                            // past "RIFF" size "WEBP"
        var width = 0
        var height = 0
        var alpha = false
        var frames = 1
        var loops = 1
        var animated = false
        var sawAny = false
        var lossy = false

        fun u16le(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        fun u24le(at: Int) = u16le(at) or ((data[at + 2].toInt() and 0xFF) shl 16)
        fun u32le(at: Int) = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)

        while (p + 8 <= data.size) {
            val fourcc = data.copyOfRange(p, p + 4).decodeToString()
            val size = u32le(p + 4)
            val body = p + 8
            if (size < 0 || body + size > data.size + 1L) break

            when (fourcc) {
                "VP8X" -> {
                    if (body + 10 > data.size) break
                    val flags = data[body].toInt() and 0xFF
                    alpha = alpha || (flags and 0x10) != 0
                    animated = (flags and 0x02) != 0
                    width = u24le(body + 4) + 1
                    height = u24le(body + 7) + 1
                    sawAny = true
                }
                "ANIM" -> {
                    if (body + 6 > data.size) break
                    loops = u16le(body + 4)
                    frames = 0
                }
                "ANMF" -> {
                    frames++
                    // The frame's own image chunk sits past the 16-byte ANMF
                    // header. Without descending, a lossy animation would probe
                    // decodable and then fail inside the frame loop.
                    val end = minOf(body + size.toInt(), data.size)
                    if (webpFrameIsLossy(data, body + 16, end)) lossy = true
                }
                "ALPH" -> alpha = true
                "VP8 " -> {
                    // Uncompressed data chunk of a key frame: 3-byte frame tag,
                    // 3-byte start code 9D 01 2A, then 14-bit width and height.
                    if (body + 10 > data.size) break
                    lossy = true
                    if (!sawAny) {
                        width = u16le(body + 6) and 0x3FFF
                        height = u16le(body + 8) and 0x3FFF
                        sawAny = true
                    }
                }
                "VP8L" -> {
                    if (body + 5 > data.size) break
                    val bits = u32le(body + 1)
                    if (!sawAny) {
                        width = ((bits and 0x3FFF).toInt()) + 1
                        height = (((bits shr 14) and 0x3FFF).toInt()) + 1
                        sawAny = true
                    }
                    if (((bits shr 28) and 1L) != 0L) alpha = true
                }
            }
            // Chunks are padded to an even size.
            p = body + size.toInt() + (size.toInt() and 1)
        }

        if (!sawAny) throw ImageDecodeException("WebP: no VP8/VP8L/VP8X chunk")

        return ImageInfo(
            format = ImageFormat.WEBP,
            width = width,
            height = height,
            bitDepth = 8,
            hasAlpha = alpha,
            frameCount = if (animated) maxOf(1, frames) else 1,
            loopCount = if (animated) loops else 1,
            orientation = Orientation.Normal,
            isDecodable = !lossy,
            unsupportedReason = if (lossy) "WebP lossy (VP8)" else null,
        )
    }

    /**
     * True when the animation frame between [start] and [end] carries a lossy
     * `VP8 ` image chunk, mirroring WebpDecoder's sub-chunk walk. A frame whose
     * chunks cannot be read is reported as not lossy and left to the decode.
     */
    private fun webpFrameIsLossy(data: ByteArray, start: Int, end: Int): Boolean {
        var p = start
        while (p >= 0 && p + 8 <= end && p + 8 <= data.size) {
            when (data.copyOfRange(p, p + 4).decodeToString()) {
                "VP8 " -> return true
                "VP8L" -> return false
            }
            var size = 0L
            for (i in 3 downTo 0) size = (size shl 8) or (data[p + 4 + i].toLong() and 0xFF)
            if (size > Int.MAX_VALUE) return false
            p += 8 + size.toInt() + (size.toInt() and 1)
        }
        return false
    }
}
