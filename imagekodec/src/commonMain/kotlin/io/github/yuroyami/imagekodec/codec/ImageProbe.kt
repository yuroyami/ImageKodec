package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageInfo
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.internal.ByteReader

/**
 * Header-only inspection: dimensions, depth, alpha, frame count and orientation
 * without decoding a single pixel. Backs `ImageKodec.probe`.
 *
 * Each format gets the cheapest walk that still gives an exact answer:
 *
 *  - PNG/APNG: IHDR, plus the chunk walk up to the first data chunk for `tRNS`
 *    and `acTL` (frame count and loop count live there)
 *  - JPEG: marker walk to the first SOF, plus the APP1 EXIF orientation
 *  - GIF: logical screen descriptor, then the block walk counting image
 *    descriptors: sub-block payloads are skipped, never LZW-decoded
 *  - JPEG 2000: main and tile-part marker headers, with packet bytes skipped
 *  - BMP/TIFF/WebP: fixed header fields (and, for animated WebP, the ANMF count)
 *
 * [ImageInfo.isDecodable] mirrors the decoders' feature refusals, so a Coil-style
 * "should I claim this file?" question is one call rather than a hand-rolled
 * header sniff per format. It answers about features, not about integrity: a
 * truncated or corrupt file whose headers declare supported features can still
 * probe as decodable and then fail during decoding.
 */
internal object ImageProbe {

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
        var loops = 1L
        var apple = false
        var sawIhdr = false
        var limit: String? = null

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
                    val w = h.u32be()
                    val hh = h.u32be()
                    PngDecoder.checkDimensions(w, hh)
                    limit = PngDecoder.sizeLimit(w, hh)
                    width = w.toInt()
                    height = hh.toInt()
                    depth = h.u8()
                    colorType = h.u8()
                    sawIhdr = true
                }
                "tRNS" -> { hasTrns = true; r.skip(minOf(n, r.remaining)) }
                "acTL" -> {
                    if (n < 8) throw ImageDecodeException("PNG: acTL length $n")
                    val a = ByteReader(r.bytes(n))
                    frames = a.u32be().toInt()
                    // Preserve the complete APNG play field, including compatibility counts.
                    loops = a.u32be()
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
        val channels = when (colorType) { 0, 3 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> 0 }
        val reason = when {
            apple -> "Apple CgBI PNG (Xcode-crushed, raw deflate + swapped channels)"
            limit != null -> limit
            // A colour type or depth the decoder refuses as damaged leaves nothing to weigh here.
            channels > 0 && depth in 1..16 -> PngDecoder.budgetRefusal(width, height, depth * channels, data.size)
            else -> null
        }

        return ImageInfo(
            format = ImageFormat.PNG,
            width = width,
            height = height,
            bitDepth = depth,
            hasAlpha = colorType == 4 || colorType == 6 || hasTrns,
            frameCount = frames,
            loopCount = loops,
            orientation = Orientation.Normal,
            isDecodable = reason == null,
            unsupportedReason = reason,
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
            // Each component: its id, its sampling factors, its quantization table.
            val factors = IntArray(components) {
                f.u8()
                f.u8().also { f.u8() }
            }
            val why = JpegDecoder.unsupportedFrame(marker, precision, height, factors)

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
        var loops = 1L
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
                        GifLooping.readApplication(r)?.let { loops = it }
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
            if (at < 0 || at > data.size - 2) throw ImageDecodeException("TIFF: truncated at $at")
            val a = data[at].toInt() and 0xFF
            val b = data[at + 1].toInt() and 0xFF
            return if (le) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(at: Int): Int {
            if (at < 0 || at > data.size - 4) throw ImageDecodeException("TIFF: truncated at $at")
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
        var extraSamples: LongArray? = null
        var orientation = Orientation.Normal
        // TIFF defaults, used when the tag is absent. Photometric has none, so -1
        // stands for "the file does not say". That is a malformed-file question
        // rather than a feature-support one, and it is left to the decoder.
        var compression = 1
        var photometric = -1
        var predictor = 1
        var t4Options = 0
        var sampleFormats: IntArray? = null
        var referenceOffset = -1
        var referenceType = 0
        var referenceCount = 0L

        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            if (at > data.size - 12) break
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
                266 -> {
                    if (type != 3 || n != 1) {
                        throw ImageDecodeException("TIFF: FillOrder (266) requires one SHORT value")
                    }
                    val fillOrder = u16(at + 8)
                    if (fillOrder != 1 && fillOrder != 2) {
                        throw ImageDecodeException("TIFF: unknown FillOrder $fillOrder")
                    }
                }
                274 -> orientation = Orientation.fromExif(scalar())
                277 -> spp = scalar()
                292 -> t4Options = single() ?: t4Options
                317 -> predictor = single() ?: predictor
                338 -> {
                    if (type != 3 || n !in 0..15) {
                        throw ImageDecodeException("TIFF: ExtraSamples requires up to 15 SHORT values")
                    }
                    val base = if (n <= 2) at + 8 else u32(at + 8)
                    if (base < 0 || base > data.size - n * 2) {
                        throw ImageDecodeException("TIFF: truncated ExtraSamples values")
                    }
                    extraSamples = LongArray(n) { u16(base + it * 2).toLong() }
                }
                339 -> {
                    if (type != 3 || n !in 1..16) {
                        throw ImageDecodeException("TIFF: SampleFormat requires SHORT values for 1 to 16 samples")
                    }
                    val base = if (n <= 2) at + 8 else u32(at + 8)
                    if (base < 0 || base > data.size - n * 2) {
                        throw ImageDecodeException("TIFF: truncated SampleFormat values")
                    }
                    sampleFormats = IntArray(n) { u16(base + it * 2) }
                }
                532 -> {
                    referenceOffset = at + 8
                    referenceType = type
                    referenceCount = n.toLong()
                }
            }
        }

        val alpha = TiffAlpha(spp, photometric, extraSamples).sample >= 0
        if (referenceOffset >= 0 && (photometric == 2 || photometric == 6)) {
            TiffReferenceBlackWhite.read(data, le, referenceType, referenceCount, referenceOffset)
        }
        if (sampleFormats != null && sampleFormats.size != spp) {
            throw ImageDecodeException("TIFF: SampleFormat requires $spp values, got ${sampleFormats.size}")
        }
        val sampleFormat = sampleFormats?.firstOrNull { it != 1 && it != 4 } ?: 1
        val reason = tiffUnsupported(bits, compression, photometric, predictor, t4Options, sampleFormat)
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
        sampleFormat: Int,
    ): String? = when {
        bits !in intArrayOf(1, 2, 4, 8, 16) ->
            "TIFF with $bits bits per sample (1, 2, 4, 8 and 16 are decodable)"
        sampleFormat != 1 -> "TIFF SampleFormat $sampleFormat (unsigned integer samples only)"
        photometric == 6 && bits != 8 -> "TIFF YCbCr with $bits-bit samples (8-bit only)"
        compression !in intArrayOf(1, 2, 3, 4, 5, 8, 32773, 32946) ->
            "TIFF compression $compression" + if (compression == 6 || compression == 7) " (JPEG-in-TIFF)" else ""
        compression == 3 && (t4Options and 2) != 0 -> "TIFF CCITT G3 uncompressed mode (T4Options bit 1)"
        predictor !in intArrayOf(1, 2) -> "TIFF predictor $predictor"
        // Horizontal differencing is only implemented for whole-byte samples.
        predictor == 2 && bits != 8 && bits != 16 -> "TIFF predictor 2 with $bits-bit samples"
        photometric >= 0 && photometric !in intArrayOf(0, 1, 2, 3, 6) ->
            "TIFF photometric interpretation $photometric"
        else -> null
    }

    // --- JPEG 2000 --------------------------------------------------------------

    private class Jp2Span(val start: Int, val end: Int)

    private fun jp2(data: ByteArray): ImageInfo {
        val boxes = Jp2Color.parse(data) ?: throw ImageDecodeException("JP2: no contiguous codestream box")
        val stream = Jp2Span(boxes.codestreamStart, boxes.codestreamEnd)
        val r = Jp2Headers.Reader(data, stream.start, stream.end, stream.start)
        if (r.u16() != 0xFF4F) r.fail("does not start with SOC")
        if (r.u16() != 0xFF51) r.fail("SIZ must follow SOC")
        r.context = "SIZ"
        val siz = r.pos
        val len = r.u16()
        if (len < 2) r.fail("invalid segment length $len")
        val sizEnd = siz.toLong() + len
        if (sizEnd > stream.end) r.fail("header cut off (segment length $len)")
        r.end = sizEnd.toInt()
        r.u16()
        val xsiz = r.u32long(); val ysiz = r.u32long()
        val xo = r.u32long(); val yo = r.u32long()
        val grid = Jp2TileGrid(xsiz, ysiz, xo, yo, r.u32long(), r.u32long(), r.u32long(), r.u32long())
        val comps = r.u16()
        val ssiz = if (comps > 0 && r.pos < r.end) (r.u8() and 0x7F) + 1 else 8
        val w = xsiz - xo
        val h = ysiz - yo
        if (w <= 0 || h <= 0 || w > Int.MAX_VALUE || h > Int.MAX_VALUE) r.fail("bad image size ${w}x$h")
        val reason = jp2Unsupported(data, stream, siz, r.end, grid.count, comps, w.toInt(), h.toInt())
            ?: Jp2Color.unsupported(boxes)
        // The decoder's own reading of the header boxes says which channel, if any, is opacity.
        val alpha = if (comps in 1..16 && Jp2Color.unsupported(boxes) == null) {
            Jp2Color.plan(boxes, comps).alpha != null
        } else {
            boxes.definitions?.any { it.type == 1 || it.type == 2 } == true
        }
        return ImageInfo(
            format = ImageFormat.JP2,
            width = w.toInt(),
            height = h.toInt(),
            bitDepth = ssiz,
            hasAlpha = alpha,
            frameCount = 1,
            loopCount = 1,
            orientation = Orientation.Normal,
            isDecodable = reason == null,
            unsupportedReason = reason,
        )
    }

    /** Header parameters share the decoder's readers; packet bytes are skipped by Psot. */
    private fun jp2Unsupported(
        data: ByteArray, stream: Jp2Span, siz: Int, sizEnd: Int, tiles: Int, comps: Int, w: Int, h: Int,
    ): String? {
        if (comps !in 1..16) return "JPEG 2000 with $comps components (1 to 16 are decodable)"
        Jp2Limits.sizeRefusal(w, h, data.size)?.let { return it }
        for (c in 0 until comps) {
            val at = siz + 38 + c * 3
            if (at > sizEnd - 3) break
            val bits = (data[at].toInt() and 0x7F) + 1
            if (bits > 16) return "JPEG 2000 with $bits-bit samples (16-bit maximum)"
            if ((data[at + 1].toInt() and 0xFF) == 0 || (data[at + 2].toInt() and 0xFF) == 0) {
                return "JPEG 2000 with a zero component subsampling factor"
            }
        }
        val r = Jp2Headers.Reader(data, sizEnd, stream.end, stream.start)
        var mainCod: Jp2Headers.Coding? = null
        val tileCod = HashMap<Int, Jp2Headers.Coding>()
        var inTile = -1
        var tileEnd = stream.end.toLong()
        try {
            while (r.pos <= stream.end - 2) {
                val markerAt = r.pos
                val marker = r.u16()
                if (marker == 0xFFD9) return null
                if (marker == 0xFF93) {
                    if (inTile < 0 || r.pos > tileEnd) return null
                    r.pos = minOf(tileEnd, stream.end.toLong()).toInt()
                    inTile = -1
                    continue
                }
                if (marker in 0xFF30..0xFF3F) continue
                if (marker < 0xFF00) return null
                r.context = Jp2Headers.markerName(marker)
                val len = r.u16()
                if (len < 2) return null
                val segmentEnd = r.pos.toLong() + len - 2
                if (segmentEnd > stream.end || (inTile >= 0 && segmentEnd > tileEnd)) return null
                r.end = segmentEnd.toInt()
                Jp2Headers.unsupportedMarker(marker)?.let { r.unsupported(it) }
                when (marker) {
                    0xFF52 -> {
                        val cod = Jp2Headers.readCod(r)
                        if (inTile >= 0) tileCod[inTile] = cod else mainCod = cod
                    }
                    0xFF53 -> {
                        Jp2Headers.component(r, comps)
                        val base = (if (inTile >= 0) tileCod[inTile] else null) ?: mainCod ?: return null
                        Jp2Headers.readCoc(r, base)
                    }
                    0xFF5C -> Jp2Headers.readQuant(r, r.end)
                    0xFF5D -> {
                        Jp2Headers.component(r, comps)
                        Jp2Headers.readQuant(r, r.end)
                    }
                    0xFF90 -> {
                        if (inTile >= 0 || len != 10) return null
                        val tile = r.u16()
                        if (tile >= tiles) return null
                        val psot = r.u32long()
                        r.u8(); r.u8()
                        inTile = tile
                        tileEnd = if (psot == 0L) stream.end.toLong() else markerAt.toLong() + psot
                        if (tileEnd < r.end.toLong() + 2) return null
                    }
                }
                r.pos = r.end
                r.end = stream.end
                r.context = "codestream"
            }
        } catch (e: io.github.yuroyami.imagekodec.UnsupportedImageException) {
            return e.message
        } catch (e: Jp2ParameterException) {
            return e.message
        } catch (_: ImageDecodeException) {
            // Incomplete headers reveal no further feature declarations; integrity
            // is the decoder's contract, not an implied pixel decode in the probe.
        }
        return null
    }

    /** The codestream box bounds a marker reader even when another box follows it. */
    // --- WebP -------------------------------------------------------------------

    private fun webp(data: ByteArray): ImageInfo {
        var p = 12                                            // past "RIFF" size "WEBP"
        var width = 0
        var height = 0
        var alpha = false
        var frames = 1
        var loops = 1L
        var animated = false
        var sawAny = false
        var sawImage = false
        var lossy = false

        fun u16le(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        fun u24le(at: Int) = u16le(at) or ((data[at + 2].toInt() and 0xFF) shl 16)
        fun u32le(at: Int) = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)

        while (p + 8 <= data.size) {
            val fourcc = data.copyOfRange(p, p + 4).decodeToString()
            val size = u32le(p + 4)
            val body = p + 8
            // A chunk the data cuts off cannot be skipped, but an image chunk only needs its first
            // bytes, and they are usually there. Stopping before them missed a lossy file whose image
            // chunk starts inside a 64 KiB peek and ends outside it.
            val cutOff = body + size > data.size + 1L
            if (cutOff && fourcc != "VP8 " && fourcc != "VP8L" && fourcc != "ANMF") break

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
                    loops = u16le(body + 4).toLong()
                    frames = 0
                }
                "ANMF" -> {
                    frames++
                    // The frame's own image chunk sits past the 16-byte ANMF
                    // header. Without descending, a lossy animation would probe
                    // decodable and then fail inside the frame loop.
                    val end = minOf(body + size.toInt(), data.size)
                    val frameIsLossy = webpFrameIsLossy(data, body + 16, end)
                    if (frameIsLossy != null) sawImage = true
                    if (frameIsLossy == true) lossy = true
                }
                "ALPH" -> alpha = true
                "VP8 " -> {
                    // Uncompressed data chunk of a key frame: 3-byte frame tag,
                    // 3-byte start code 9D 01 2A, then 14-bit width and height.
                    if (body + 10 > data.size) break
                    lossy = true
                    sawImage = true
                    if (!sawAny) {
                        width = u16le(body + 6) and 0x3FFF
                        height = u16le(body + 8) and 0x3FFF
                        sawAny = true
                    }
                }
                "VP8L" -> {
                    if (body + 5 > data.size) break
                    val bits = u32le(body + 1)
                    sawImage = true
                    if (!sawAny) {
                        width = ((bits and 0x3FFF).toInt()) + 1
                        height = (((bits shr 14) and 0x3FFF).toInt()) + 1
                        sawAny = true
                    }
                    if (((bits shr 28) and 1L) != 0L) alpha = true
                }
            }
            if (cutOff) break
            // Chunks are padded to an even size.
            p = body + size.toInt() + (size.toInt() and 1)
        }

        if (!sawAny) throw ImageDecodeException("WebP: no VP8/VP8L/VP8X chunk")
        // Without an image chunk the data cannot say whether the image is lossy, so it is not decodable.
        val reason = when {
            lossy -> "WebP lossy (VP8)"
            !sawImage -> "WebP data ends before its first image chunk"
            else -> null
        }

        return ImageInfo(
            format = ImageFormat.WEBP,
            width = width,
            height = height,
            bitDepth = 8,
            hasAlpha = alpha,
            frameCount = if (animated) maxOf(1, frames) else 1,
            loopCount = if (animated) loops else 1L,
            orientation = Orientation.Normal,
            isDecodable = reason == null,
            unsupportedReason = reason,
        )
    }

    /**
     * Whether the animation frame between [start] and [end] carries a lossy `VP8 ` image chunk (true)
     * or a lossless `VP8L` one (false), mirroring WebpDecoder's sub-chunk walk. Null when the walk
     * reaches neither, because the frame is malformed or the data ends first.
     */
    private fun webpFrameIsLossy(data: ByteArray, start: Int, end: Int): Boolean? {
        var p = start
        while (p >= 0 && p + 8 <= end && p + 8 <= data.size) {
            when (data.copyOfRange(p, p + 4).decodeToString()) {
                "VP8 " -> return true
                "VP8L" -> return false
            }
            var size = 0L
            for (i in 3 downTo 0) size = (size shl 8) or (data[p + 4 + i].toLong() and 0xFF)
            if (size > Int.MAX_VALUE) return null
            p += 8 + size.toInt() + (size.toInt() and 1)
        }
        return null
    }
}
