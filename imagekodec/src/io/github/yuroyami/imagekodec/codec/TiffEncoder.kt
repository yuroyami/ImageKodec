package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.TiffCompression
import io.github.yuroyami.imagekodec.TiffEncodeOptions
import io.github.yuroyami.imagekodec.TiffPhotometric
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * TIFF encoder (TIFF 6.0, with the Adobe Deflate compression of the TIFF Technical Notes and the
 * JPEG compression of Technical Note 2): a little-endian file of one directory a page, chunky
 * (PlanarConfiguration 1), in strips or tiles.
 *
 * By default each page is in strips of about 8 KiB compressed with Deflate after the horizontal
 * differencing predictor, as libtiff writes with `-c zip -p 2` (or `tiffcp -c zip:p2`): gray
 * (photometric 1) when every pixel of the page is gray, RGB (photometric 2) otherwise, each with
 * an unassociated alpha sample when the page has alpha. [TiffEncodeOptions] picks the other
 * compressions (none, PackBits, LZW, CCITT Group 3 and 4, JPEG), tiles, and bi-level, gray,
 * palette and RGB samples; [encodeCmyk] writes CMYK separations (photometric 5, InkSet 1).
 * Pages after the first are chained, each marked as a page of a multi-page file with its
 * PageNumber.
 */
internal object TiffEncoder {
    private const val BYTE = 1
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val UNDEFINED = 7
    private const val STRIP_BYTES = 8192

    /** The largest strip or tile of samples a block is built in, so that its buffer is an array. */
    private const val MAX_BLOCK_BYTES = (1L shl 31) - 64

    /**
     * A page as the writer sees it: its rows of chunky samples, packed MSB first below 8 bits and
     * little endian at 16, and what the directory says about them.
     */
    private class Page(
        val width: Int,
        val height: Int,
        val bits: Int,
        val samplesPerPixel: Int,
        val photometric: Int,
        val alpha: Boolean,
        val row: (y: Int, out: ByteArray) -> Unit,
    ) {
        val rowBytesLong = (width.toLong() * samplesPerPixel * bits + 7) / 8
        val rowBytes = rowBytesLong.toInt()

        /** Palette entries as 0xRRGGBB, padded to 2^[bits], for photometric 3. */
        var colorMap: IntArray? = null

        /** The bitmap a JPEG page codes, and whether it codes one gray component. */
        var jpegSource: KiteBitmap? = null
        var jpegGray = false
    }

    fun encode(pages: List<KiteBitmap>): ByteArray = encode(pages, TiffEncodeOptions(), ImageMetadata(), null)

    fun encode16(pages: List<KiteBitmap16>): ByteArray = encode16(pages, TiffEncodeOptions(), ImageMetadata(), null)

    fun encode(pages: List<KiteBitmap>, options: TiffEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        require(pages.isNotEmpty()) { "a TIFF needs at least one page" }
        return write(pages.map { page(it, options, cancel) }, options, metadata, cancel)
    }

    fun encode16(pages: List<KiteBitmap16>, options: TiffEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        require(pages.isNotEmpty()) { "a TIFF needs at least one page" }
        return write(pages.map { page(it, options) }, options, metadata, cancel)
    }

    /** CMYK ink, four samples a pixel from no ink (0) to full, as TIFF 6.0 section 16 stores it with InkSet 1. */
    fun encodeCmyk(
        width: Int, height: Int, ink: ByteArray?, ink16: ShortArray?,
        options: TiffEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?,
    ): ByteArray {
        require(width > 0 && height > 0) { "TIFF: CMYK dimensions must be positive: ${width}x$height" }
        val size = ink?.size ?: ink16!!.size
        require(size.toLong() == width.toLong() * height * 4) { "TIFF: $size ink samples for ${width}x$height pixels, which need ${width.toLong() * height * 4}" }
        require(options.photometric == TiffPhotometric.Auto) { "TIFF: CMYK ink is written as CMYK, so photometric must be Auto, was ${options.photometric}" }
        require(options.compression !in FAX && options.compression != TiffCompression.Jpeg) {
            "TIFF: ${options.compression} cannot hold CMYK ink"
        }
        val page = if (ink != null) {
            Page(width, height, 8, 4, 5, false) { y, out -> ink.copyInto(out, 0, y * width * 4, (y + 1) * width * 4) }
        } else {
            Page(width, height, 16, 4, 5, false) { y, out ->
                val base = y * width * 4
                for (i in 0 until width * 4) {
                    val v = ink16!![base + i].toInt()
                    out[2 * i] = v.toByte()
                    out[2 * i + 1] = (v ushr 8).toByte()
                }
            }
        }
        return write(listOf(page), options, metadata, cancel)
    }

    private val FAX = setOf(TiffCompression.CcittG4, TiffCompression.CcittG3)

    private fun page(b: KiteBitmap, options: TiffEncodeOptions, cancel: (() -> Unit)?): Page {
        val px = b.argb
        val compression = options.compression
        val photometric = when (options.photometric) {
            TiffPhotometric.Auto -> if (compression in FAX) TiffPhotometric.BiLevel else null
            else -> options.photometric
        }
        if (compression in FAX) {
            require(photometric == TiffPhotometric.BiLevel) { "TIFF: $compression codes a bi-level page, not ${options.photometric}" }
            val columns = if (options.tiled) options.tileWidth else b.width
            require(columns <= CCITT_MAX_ENCODE_COLUMNS) { "TIFF: CCITT: $columns columns is more than $CCITT_MAX_ENCODE_COLUMNS" }
        }
        if (compression == TiffCompression.Jpeg) {
            require(photometric != TiffPhotometric.BiLevel && photometric != TiffPhotometric.Palette) {
                "TIFF: JPEG codes gray or colour samples, not $photometric"
            }
            require(!b.hasTransparency()) { "TIFF: JPEG cannot hold alpha, and the ${b.width}x${b.height} page has some" }
            val gray = when (photometric) {
                TiffPhotometric.Gray -> true
                TiffPhotometric.Rgb -> false
                else -> px.all { isGray(it) }
            }
            // The JPEG encoder converts to YCbCr itself, so the rows are never asked for.
            return Page(b.width, b.height, 8, if (gray) 1 else 3, if (gray) 1 else 6, false) { _, _ -> }.apply {
                jpegSource = b
                jpegGray = gray
            }
        }
        return when (photometric) {
            TiffPhotometric.BiLevel -> {
                val packed = bilevelRows(b, blackIs1 = true, cancel = cancel)
                val stride = (b.width + 7) / 8
                Page(b.width, b.height, 1, 1, 0, false) { y, out -> packed.copyInto(out, 0, y * stride, (y + 1) * stride) }
            }
            TiffPhotometric.Palette -> palettePage(b)
            else -> {
                val gray = when (photometric) {
                    TiffPhotometric.Gray -> true
                    TiffPhotometric.Rgb -> false
                    else -> px.all { isGray(it) }
                }
                val alpha = b.hasTransparency()
                Page(b.width, b.height, 8, (if (gray) 1 else 3) + (if (alpha) 1 else 0), if (gray) 1 else 2, alpha) { y, out ->
                    var o = 0
                    for (x in 0 until b.width) {
                        val p = px[y * b.width + x]
                        if (gray) {
                            out[o++] = luma(p ushr 16 and 0xFF, p ushr 8 and 0xFF, p and 0xFF).toByte()
                        } else {
                            out[o++] = (p ushr 16).toByte()
                            out[o++] = (p ushr 8).toByte()
                            out[o++] = p.toByte()
                        }
                        if (alpha) out[o++] = (p ushr 24).toByte()
                    }
                }
            }
        }
    }

    private fun isGray(p: Int): Boolean = ((p ushr 16) and 0xFF) == ((p ushr 8) and 0xFF) && ((p ushr 8) and 0xFF) == (p and 0xFF)

    /** BT.601 luma in 256ths, rounded; a gray pixel keeps its level, since the weights sum to 256. */
    private fun luma(r: Int, g: Int, b: Int): Int = (77 * r + 150 * g + 29 * b + 128) ushr 8

    private fun palettePage(b: KiteBitmap): Page {
        val px = b.argb
        require(!b.hasTransparency()) { "TIFF: a palette cannot hold alpha, and the ${b.width}x${b.height} page has some" }
        val index = HashMap<Int, Int>()
        val colours = ArrayList<Int>()
        for (p in px) {
            if (index.containsKey(p)) continue
            require(colours.size < 256) { "TIFF: a palette holds 256 colours, and the ${b.width}x${b.height} page has more" }
            index[p] = colours.size
            colours += p and 0xFFFFFF
        }
        val bits = when {
            colours.size <= 2 -> 1
            colours.size <= 4 -> 2
            colours.size <= 16 -> 4
            else -> 8
        }
        val indices = IntArray(px.size) { index.getValue(px[it]) }
        return Page(b.width, b.height, bits, 1, 3, false) { y, out ->
            out.fill(0)
            val base = y * b.width
            val perByte = 8 / bits
            for (x in 0 until b.width) {
                val at = x / perByte
                val shift = 8 - bits * (x % perByte + 1)
                out[at] = (out[at].toInt() or (indices[base + x] shl shift)).toByte()
            }
        }.apply { colorMap = IntArray(1 shl bits) { if (it < colours.size) colours[it] else 0 } }
    }

    private fun page(b: KiteBitmap16, options: TiffEncodeOptions): Page {
        require(options.compression !in FAX && options.compression != TiffCompression.Jpeg) {
            "TIFF: ${options.compression} cannot hold 16-bit samples"
        }
        require(options.photometric != TiffPhotometric.BiLevel && options.photometric != TiffPhotometric.Palette) {
            "TIFF: a ${options.photometric} page has no 16-bit samples"
        }
        val ch = b.channels
        val s = b.samples
        val alpha = ch == 2 || ch == 4
        val gray = when (options.photometric) {
            TiffPhotometric.Gray -> true
            TiffPhotometric.Rgb -> false
            else -> ch <= 2
        }
        val spp = (if (gray) 1 else 3) + (if (alpha) 1 else 0)
        if (gray == ch <= 2) {
            return Page(b.width, b.height, 16, spp, if (gray) 1 else 2, alpha) { y, out ->
                val base = y * b.width * ch
                for (i in 0 until b.width * ch) {
                    val v = s[base + i].toInt()
                    out[2 * i] = v.toByte()
                    out[2 * i + 1] = (v ushr 8).toByte()
                }
            }
        }
        // Gray from colour takes each pixel's luma; colour from gray repeats the gray sample.
        return Page(b.width, b.height, 16, spp, if (gray) 1 else 2, alpha) { y, out ->
            var o = 0
            for (x in 0 until b.width) {
                val at = (y * b.width + x) * ch
                fun put(v: Int) {
                    out[o++] = v.toByte()
                    out[o++] = (v ushr 8).toByte()
                }
                if (gray) {
                    put(luma(s[at].toInt() and 0xFFFF, s[at + 1].toInt() and 0xFFFF, s[at + 2].toInt() and 0xFFFF))
                } else {
                    val g = s[at].toInt() and 0xFFFF
                    put(g); put(g); put(g)
                }
                if (alpha) put(s[at + ch - 1].toInt() and 0xFFFF)
            }
        }
    }

    /** Where the strips or tiles of a page fall, and what goes into each. */
    private class Layout(page: Page, options: TiffEncodeOptions, subsampled: Boolean) {
        val tiled = options.tiled
        val blockWidth = if (tiled) options.tileWidth else page.width
        val blockLength: Int
        val across: Int
        val down: Int

        init {
            if (tiled) {
                blockLength = options.tileLength
            } else {
                val mcu = if (page.jpegSource == null) 1 else if (subsampled) 16 else 8
                blockLength = if (options.rowsPerStrip > 0) {
                    require(options.rowsPerStrip >= page.height || options.rowsPerStrip % mcu == 0) {
                        "TIFF: a JPEG strip holds a multiple of $mcu rows, and rowsPerStrip is ${options.rowsPerStrip}"
                    }
                    options.rowsPerStrip.coerceAtMost(page.height)
                } else {
                    val rows = (STRIP_BYTES / maxOf(1L, page.rowBytesLong)).toInt().coerceIn(1, page.height)
                    // A JPEG strip that is not the last is a whole number of MCU rows (Technical Note 2).
                    if (mcu > 1) minOf(page.height, (rows + mcu - 1) / mcu * mcu) else rows
                }
            }
            across = if (tiled) (page.width + blockWidth - 1) / blockWidth else 1
            down = (page.height + blockLength - 1) / blockLength
            val blockRowBytes = (blockWidth.toLong() * page.samplesPerPixel * page.bits + 7) / 8
            // A band of rows is built whole before it is cut into tiles, so it has to fit an array too.
            val bandBytes = page.rowBytesLong * minOf(blockLength, page.height)
            require(bandBytes <= MAX_BLOCK_BYTES && blockRowBytes * blockLength <= MAX_BLOCK_BYTES) {
                "TIFF: a ${blockWidth}x$blockLength block of ${page.samplesPerPixel * page.bits}-bit pixels is more than $MAX_BLOCK_BYTES bytes"
            }
        }

        val blockRowBytes = ((blockWidth.toLong() * page.samplesPerPixel * page.bits + 7) / 8).toInt()

        /** Rows that block row [by] holds: a tile always holds its full length, a strip only the rows left. */
        fun rows(page: Page, by: Int): Int = if (tiled) blockLength else minOf(blockLength, page.height - by * blockLength)
    }

    private fun write(pages: List<Page>, options: TiffEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val out = ByteArrayBuilder(1024)
        out.append(byteArrayOf(0x49, 0x49, 42, 0))
        // The offset of the first directory, patched once it is known.
        val firstIfdAt = out.size
        out.append(ByteArray(4))
        var patchAt = firstIfdAt
        val jpegQuality = options.quality.coerceAtLeast(1)
        // The JPEG encoder halves the chroma at quality 90 and below.
        val subsampled = jpegQuality <= 90
        for ((index, page) in pages.withIndex()) {
            val layout = Layout(page, options, subsampled && page.jpegSource != null && !page.jpegGray)
            val predict = options.predictor && page.photometric != 3 && page.bits >= 8 &&
                (options.compression == TiffCompression.Deflate || options.compression == TiffCompression.Lzw)
            var jpegTables: ByteArray? = null
            val offsets = IntArray(layout.across * layout.down)
            val counts = IntArray(offsets.size)
            var block = 0
            val rowBuffer = if (page.jpegSource == null) ByteArray(page.rowBytes) else ByteArray(0)
            for (by in 0 until layout.down) {
                val rows = layout.rows(page, by)
                // The rows of this band of blocks, each built once and cut into tiles.
                val bandRows = minOf(rows, page.height - by * layout.blockLength)
                val bandBytes = if (page.jpegSource != null) null else ByteArray(page.rowBytes * bandRows).also {
                    for (r in 0 until bandRows) {
                        page.row(by * layout.blockLength + r, rowBuffer)
                        rowBuffer.copyInto(it, r * page.rowBytes)
                    }
                }
                for (bx in 0 until layout.across) {
                    cancel?.invoke()
                    val coded = if (page.jpegSource != null) {
                        val stream = JpegEncoder.encode(
                            cut(page.jpegSource!!, bx * layout.blockWidth, by * layout.blockLength, layout.blockWidth, rows),
                            jpegQuality, null, page.jpegGray,
                        )
                        val (tables, abbreviated) = abbreviate(stream)
                        if (jpegTables == null) jpegTables = tables
                        abbreviated
                    } else {
                        val raw = ByteArray(layout.blockRowBytes * rows)
                        val from = bx.toLong() * layout.blockWidth * page.samplesPerPixel * page.bits / 8
                        val length = minOf(layout.blockRowBytes.toLong(), page.rowBytes - from).toInt()
                        for (r in 0 until bandRows) bandBytes!!.copyInto(raw, r * layout.blockRowBytes, r * page.rowBytes + from.toInt(), r * page.rowBytes + from.toInt() + length)
                        compress(raw, layout.blockRowBytes, rows, layout.blockWidth, page, options.compression, predict, cancel)
                    }
                    pad(out)
                    offsets[block] = out.size
                    counts[block] = coded.size
                    out.append(coded)
                    block++
                }
            }
            pad(out)
            // Values that do not fit in an entry go before the directory.
            val bitsAt = if (page.samplesPerPixel > 2) out.size.also { for (k in 0 until page.samplesPerPixel) short(out, page.bits) } else -1
            val offsetsAt = if (offsets.size > 1) out.size.also { for (o in offsets) long(out, o) } else -1
            val countsAt = if (offsets.size > 1) out.size.also { for (s in counts) long(out, s) } else -1
            val resolutionAt = out.size
            long(out, 72)
            long(out, 1)
            val entries = ArrayList<IntArray>()
            fun entry(tag: Int, type: Int, count: Int, value: Int) {
                entries += intArrayOf(tag, type, count, value)
            }
            /** A field of bytes, in the entry when it fits four and before the directory otherwise. */
            fun bytes(tag: Int, type: Int, data: ByteArray) {
                if (data.size <= 4) {
                    var v = 0
                    for (i in data.indices) v = v or ((data[i].toInt() and 0xFF) shl (8 * i))
                    entry(tag, type, data.size, v)
                } else {
                    pad(out)
                    entry(tag, type, data.size, out.size)
                    out.append(data)
                }
            }
            page.colorMap?.let { map ->
                // TIFF 6.0 section 5: all the reds, then the greens, then the blues, each 16 bits.
                pad(out)
                entry(320, SHORT, map.size * 3, out.size)
                for (shift in intArrayOf(16, 8, 0)) for (c in map) short(out, ((c ushr shift) and 0xFF) * 257)
            }
            jpegTables?.let { bytes(347, UNDEFINED, it) }
            if (page.photometric == 6) {
                pad(out)
                entry(532, RATIONAL, 6, out.size)
                for (v in intArrayOf(0, 255, 128, 255, 128, 255)) {
                    long(out, v)
                    long(out, 1)
                }
            }
            metadata.icc?.let { bytes(34675, UNDEFINED, it) }
            if (index == 0) metadata.xmp?.let { bytes(700, BYTE, it.encodeToByteArray()) }
            pad(out)
            val ifd = out.size
            patch(out, patchAt, ifd)
            val compressionCode = when (options.compression) {
                TiffCompression.None -> 1
                TiffCompression.PackBits -> 32773
                TiffCompression.Lzw -> 5
                TiffCompression.Deflate -> 8
                TiffCompression.CcittG4 -> 4
                TiffCompression.CcittG3 -> 3
                TiffCompression.Jpeg -> 7
            }
            entry(254, LONG, 1, if (pages.size > 1) 2 else 0)
            entry(256, LONG, 1, page.width)
            entry(257, LONG, 1, page.height)
            entry(258, SHORT, page.samplesPerPixel, if (bitsAt >= 0) bitsAt else page.bits or (if (page.samplesPerPixel == 2) page.bits shl 16 else 0))
            entry(259, SHORT, 1, compressionCode)
            entry(262, SHORT, 1, page.photometric)
            entry(if (layout.tiled) 324 else 273, LONG, offsets.size, if (offsetsAt >= 0) offsetsAt else offsets[0])
            entry(277, SHORT, 1, page.samplesPerPixel)
            if (!layout.tiled) entry(278, LONG, 1, layout.blockLength)
            entry(if (layout.tiled) 325 else 279, LONG, counts.size, if (countsAt >= 0) countsAt else counts[0])
            entry(282, RATIONAL, 1, resolutionAt)
            entry(283, RATIONAL, 1, resolutionAt)
            entry(284, SHORT, 1, 1)
            if (options.compression == TiffCompression.CcittG3) entry(292, LONG, 1, 0)
            if (options.compression == TiffCompression.CcittG4) entry(293, LONG, 1, 0)
            entry(296, SHORT, 1, 2)
            if (pages.size > 1) entry(297, SHORT, 2, index or (pages.size shl 16))
            if (predict) entry(317, SHORT, 1, 2)
            if (layout.tiled) {
                entry(322, LONG, 1, layout.blockWidth)
                entry(323, LONG, 1, layout.blockLength)
            }
            if (page.photometric == 5) entry(332, SHORT, 1, 1)
            if (page.alpha) entry(338, SHORT, 1, 2)
            if (page.photometric == 6) entry(530, SHORT, 2, if (subsampled) 2 or (2 shl 16) else 1 or (1 shl 16))
            // TIFF 6.0 section 2: the entries are sorted by tag.
            entries.sortBy { it[0] }
            short(out, entries.size)
            for (e in entries) {
                short(out, e[0])
                short(out, e[1])
                long(out, e[2])
                long(out, e[3])
            }
            patchAt = out.size
            long(out, 0)
        }
        return out.toByteArray()
    }

    /** The [w] by [h] pixels of [b] from ([x0], [y0]), the edge pixels repeated past its right and bottom. */
    private fun cut(b: KiteBitmap, x0: Int, y0: Int, w: Int, h: Int): KiteBitmap {
        if (x0 == 0 && y0 == 0 && w == b.width && h == b.height) return b
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val sy = minOf(y0 + y, b.height - 1)
            for (x in 0 until w) px[y * w + x] = b.argb[sy * b.width + minOf(x0 + x, b.width - 1)]
        }
        return KiteBitmap(w, h, px)
    }

    /**
     * A JPEG interchange stream split as Technical Note 2 stores it: its quantization and Huffman
     * tables as a JPEGTables stream (SOI, the tables, EOI), and the abbreviated stream of the block
     * (SOI, the frame header, the scan, EOI). The JFIF segment is left out: TIFF says the rest.
     */
    private fun abbreviate(stream: ByteArray): Pair<ByteArray, ByteArray> {
        val tables = ByteArrayBuilder(1024)
        val block = ByteArrayBuilder(stream.size)
        tables.append(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        block.append(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        var at = 2
        while (true) {
            val marker = stream[at + 1].toInt() and 0xFF
            val length = ((stream[at + 2].toInt() and 0xFF) shl 8) or (stream[at + 3].toInt() and 0xFF)
            when (marker) {
                0xDB, 0xC4 -> tables.append(stream, at, length + 2)
                0xDA -> {
                    block.append(stream, at, stream.size - at)
                    break
                }
                in 0xE0..0xEF -> {}
                else -> block.append(stream, at, length + 2)
            }
            at += length + 2
        }
        tables.append(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return tables.toByteArray() to block.toByteArray()
    }

    /** One strip or tile of [rows] rows of [rowBytes] bytes, [columns] pixels each, compressed. */
    private fun compress(
        raw: ByteArray, rowBytes: Int, rows: Int, columns: Int, page: Page,
        compression: TiffCompression, predict: Boolean, cancel: (() -> Unit)?,
    ): ByteArray {
        if (predict) difference(raw, rowBytes, rows, page.samplesPerPixel, page.bits)
        return when (compression) {
            TiffCompression.None -> raw
            TiffCompression.PackBits -> packBits(raw, rowBytes, rows)
            TiffCompression.Lzw -> lzw(raw)
            TiffCompression.Deflate -> Zlib.compress(raw)
            // The rows are packed with black as 1, which is what WhiteIsZero stores.
            TiffCompression.CcittG4 -> encodeCcittRows(raw, columns, rows, -1, true, false, false, true, cancel)
            TiffCompression.CcittG3 -> encodeCcittRows(raw, columns, rows, 0, true, false, true, false, cancel)
            TiffCompression.Jpeg -> error("JPEG blocks are coded from the bitmap")
        }
    }

    /** TIFF 6.0 section 14: each sample less the same sample of the pixel to its left, row by row. */
    private fun difference(raw: ByteArray, rowBytes: Int, rows: Int, spp: Int, bits: Int) {
        for (r in 0 until rows) {
            val o = r * rowBytes
            if (bits == 8) {
                for (i in rowBytes - 1 downTo spp) raw[o + i] = (raw[o + i] - raw[o + i - spp]).toByte()
            } else {
                for (i in rowBytes / 2 - 1 downTo spp) {
                    val at = o + 2 * i
                    val back = o + 2 * (i - spp)
                    val v = (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8)
                    val prev = (raw[back].toInt() and 0xFF) or ((raw[back + 1].toInt() and 0xFF) shl 8)
                    val d = (v - prev) and 0xFFFF
                    raw[at] = d.toByte()
                    raw[at + 1] = (d ushr 8).toByte()
                }
            }
        }
    }

    /**
     * PackBits (TIFF 6.0 section 9), each row packed alone: a run of two or more equal bytes as
     * a repeat (header 1 - n), anything else as literals (header n - 1), at most 128 bytes each.
     */
    private fun packBits(raw: ByteArray, rowBytes: Int, rows: Int): ByteArray {
        val out = ByteArrayBuilder(raw.size + raw.size / 64 + 16)
        for (r in 0 until rows) {
            val end = (r + 1) * rowBytes
            var i = r * rowBytes
            var literal = i
            fun flushLiterals(until: Int) {
                var from = literal
                while (from < until) {
                    val n = minOf(128, until - from)
                    out.append((n - 1).toByte())
                    out.append(raw, from, n)
                    from += n
                }
            }
            while (i < end) {
                var run = 1
                while (i + run < end && run < 128 && raw[i + run] == raw[i]) run++
                // A run of two between literals costs as much as two literals, so it joins them.
                if (run >= 3 || (run == 2 && literal == i)) {
                    flushLiterals(i)
                    out.append((1 - run).toByte())
                    out.append(raw[i])
                    i += run
                    literal = i
                } else {
                    i += run
                }
            }
            flushLiterals(end)
        }
        return out.toByteArray()
    }

    /**
     * TIFF LZW (TIFF 6.0 section 13): codes from 9 to 12 bits, MSB first, starting with Clear (256)
     * and ending with EndOfInformation (257). The width grows one code early, as the specification's
     * own decoder and libtiff read it: once the table holds 511, 1023 or 2047 entries. The table
     * is cleared before it would need a thirteenth bit.
     */
    private fun lzw(raw: ByteArray): ByteArray {
        val out = ByteArrayBuilder(raw.size / 2 + 16)
        var acc = 0L
        var count = 0
        // The next free code; the decoder's table is one entry behind it while it reads a code.
        var next = 258
        fun put(code: Int) {
            val held = next - 1
            val width = when {
                held < 511 -> 9
                held < 1023 -> 10
                held < 2047 -> 11
                else -> 12
            }
            acc = (acc shl width) or code.toLong()
            count += width
            while (count >= 8) {
                count -= 8
                out.append((acc ushr count).toByte())
            }
            acc = acc and ((1L shl count) - 1)
        }
        // Open addressing over (prefix code, byte) pairs; a prime size keeps the probes short.
        val size = 5003
        val keys = IntArray(size)
        val codes = IntArray(size)
        fun clearTable() {
            keys.fill(-1)
            next = 258
        }
        clearTable()
        put(256)
        // The first code after a Clear adds no entry in the decoder; `next` then counts from 258 as in it.
        if (raw.isEmpty()) {
            put(257)
        } else {
            var prefix = raw[0].toInt() and 0xFF
            for (i in 1 until raw.size) {
                val c = raw[i].toInt() and 0xFF
                val key = (prefix shl 8) or c
                var h = key % size
                while (keys[h] != -1 && keys[h] != key) h = if (h == 0) size - 1 else h - 1
                if (keys[h] == key) {
                    prefix = codes[h]
                    continue
                }
                put(prefix)
                keys[h] = key
                codes[h] = next++
                if (next == 4094) {
                    put(256)
                    clearTable()
                }
                prefix = c
            }
            put(prefix)
            next++
            put(257)
        }
        if (count > 0) out.append((acc shl (8 - count)).toByte())
        return out.toByteArray()
    }

    private fun pad(out: ByteArrayBuilder) {
        if (out.size % 2 != 0) out.append(ByteArray(1))
    }

    private fun short(out: ByteArrayBuilder, v: Int) = out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte()))

    private fun long(out: ByteArrayBuilder, v: Int) = out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))

    private fun patch(out: ByteArrayBuilder, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v ushr 8).toByte()
        out[at + 2] = (v ushr 16).toByte()
        out[at + 3] = (v ushr 24).toByte()
    }
}
