package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.internal.flate.InflateException
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * Baseline TIFF decoder (commons-imaging as the semantic reference). Scope:
 * the strip-based baseline that covers the files people actually have:
 *
 *  - both byte orders (II/MM), and every page: each image file directory in the
 *    main chain is one, as libtiff, ImageIO and libvips count them
 *  - **strips and tiles**: tiled files assemble edge-padded tiles back into full
 *    rows after restoring any per-tile horizontal predictor
 *  - compressions: none (1), CCITT G3-1D (2, byte-aligned rows), G3 via
 *    T4Options 1D/mixed 2D (3), G4 (4), the absorbed [CcittFax] codec, TIFF-LZW
 *    with EarlyChange (5), old-style JPEG rebuilt into one stream as libtiff
 *    does it ([TiffOldJpeg], 6), JPEG as Technical Note 2 defines it (7),
 *    Deflate (8 / 32946), PackBits (32773)
 *  - photometric 0/1 (bilevel + gray, either polarity, optional alpha), 2 (RGB,
 *    optional associated or straight alpha via ExtraSamples), 3 (palette, 16-bit
 *    ColorMap entries), 6 (**YCbCr**, including chroma subsampling in both the
 *    chunky unit layout and separate planes)
 *  - bits per sample 1, 2, 4, 8 and 16 ([decode] narrows 16-bit to its high byte,
 *    [decode16] keeps it whole), horizontal-differencing predictor (2) for
 *    both 8- and 16-bit samples
 *  - **both planar configurations**: chunky (1) and separate planes (2)
 *  - both FillOrder values, independent of sample byte order and compression
 *  - unsigned integer samples; undefined SampleFormat follows the unsigned default
 *
 * What is left out is named at the point of failure: floating-point samples have
 * no home in an 8-bit-per-channel bitmap.
 */
internal object TiffDecoder {

    private const val MAX_DIMENSION = 1 shl 24
    private const val MAX_PIXELS = 1L shl 28

    /**
     * Ceiling on any single decompressed buffer. Every dimension here comes from
     * the file, and a corrupted SamplesPerPixel or TileLength multiplies straight
     * into an allocation, so the product is checked before it is made rather than
     * after the allocator gives up.
     */
    private const val MAX_BUFFER_BYTES = 1L shl 30

    private val reversedBits = ByteArray(256) { value ->
        var source = value
        var reversed = 0
        repeat(8) {
            reversed = (reversed shl 1) or (source and 1)
            source = source ushr 1
        }
        reversed.toByte()
    }

    private fun err(msg: String): Nothing = throw ImageDecodeException("TIFF: $msg")

    /**
     * Bytes in one row of [width] pixels, each holding [samples] samples of [bits] bits.
     * Long, because the product wraps an Int: 2^24 columns of 8 samples at 16 bits is 2^31 bits.
     */
    private fun rowBytes(width: Int, samples: Int, bits: Int): Long = (width.toLong() * samples * bits + 7) / 8

    private class Reader(val d: ByteArray, val le: Boolean) {
        fun u8(at: Int): Int {
            if (at < 0 || at >= d.size) err("truncated at offset $at")
            return d[at].toInt() and 0xFF
        }

        fun u16(at: Int): Int =
            if (le) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)

        fun u32(at: Int): Long =
            if (le) {
                u16(at).toLong() or (u16(at + 2).toLong() shl 16)
            } else {
                (u16(at).toLong() shl 16) or u16(at + 2).toLong()
            }
    }

    private class Entry(val tag: Int, val type: Int, val count: Long, val valueOfs: Int)

    private fun reader(data: ByteArray): Reader {
        if (data.size < 8) err("too short")
        val le = data[0].toInt() == 'I'.code && data[1].toInt() == 'I'.code
        val be = data[0].toInt() == 'M'.code && data[1].toInt() == 'M'.code
        if (!le && !be) err("bad byte-order mark")
        val r = Reader(data, le)
        if (r.u16(2) != 42) err("bad magic")
        return r
    }

    /**
     * The offsets of the first [limit] image file directories in the file's main
     * chain, one a page, in order. Each directory ends with the offset of the next,
     * and the chain ends at a zero offset. It also ends where that offset leads
     * outside the file or back to a directory already visited, or where the offset
     * itself is cut off, and the pages before stay readable: libtiff stops at the
     * same places, so damage past a page costs the pages after it, not the file.
     * The visited set is what keeps a chain that loops from running forever.
     */
    fun pages(data: ByteArray, limit: Int = Int.MAX_VALUE): IntArray {
        val r = reader(data)
        val first = r.u32(4)
        if (first < 8 || first >= data.size) err("bad IFD offset $first")
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        var ifd = first.toInt()
        while (out.size < limit) {
            out += ifd
            seen += ifd
            if (ifd > data.size - 2) break   // the entry count itself is missing: decoding that page says so
            val link = ifd + 2 + 12L * r.u16(ifd)
            if (link > data.size - 4) break
            val next = r.u32(link.toInt())
            if (next < 8 || next > data.size - 2 || next.toInt() in seen) break
            ifd = next.toInt()
        }
        return out.toIntArray()
    }

    /** Decodes page [page], counting from 0. */
    fun decode(data: ByteArray, page: Int = 0): KiteBitmap = read(data, page).argb()

    /** Decodes page [page] at 16 bits a sample, in the channels it stores. */
    fun decode16(data: ByteArray, page: Int = 0): KiteBitmap16 = read(data, page).wide()

    /** A page whose samples are read and ready to convert, at 8 or at 16 bits a channel. */
    private interface Raster {
        fun argb(): KiteBitmap
        fun wide(): KiteBitmap16
    }

    private fun read(data: ByteArray, page: Int): Raster {
        val r = reader(data)
        val le = r.le
        val chain = pages(data, limit = page + 1)
        require(page < chain.size) { "TIFF: page $page asked of a file with ${chain.size} page(s)" }
        val ifdOfs = chain[page]

        // --- IFD walk -----------------------------------------------------------
        val entries = HashMap<Int, Entry>()
        val count = r.u16(ifdOfs)
        for (i in 0 until count) {
            val at = ifdOfs + 2 + i * 12
            entries[r.u16(at)] = Entry(r.u16(at), r.u16(at + 2), r.u32(at + 4), at + 8)
        }

        fun typeSize(type: Int) = when (type) {
            1, 2 -> 1; 3 -> 2; 4 -> 4; 5 -> 8
            else -> err("unsupported field type $type")
        }

        /** All values of an entry as Longs (SHORT/LONG/BYTE). */
        fun values(e: Entry): LongArray {
            val size = typeSize(e.type) * e.count
            // The count is a 32-bit field straight off the wire: a corrupt one
            // would otherwise size an array before anyone checks the values exist.
            // They have to physically fit in the file, which is the honest bound.
            if (e.count < 0 || size > data.size) {
                err("field ${e.tag} declares ${e.count} values, more than the file holds")
            }
            val base = if (size <= 4) e.valueOfs else r.u32(e.valueOfs).toInt()
            return LongArray(e.count.toInt()) { i ->
                when (e.type) {
                    1 -> r.u8(base + i).toLong()
                    3 -> r.u16(base + i * 2).toLong()
                    4 -> r.u32(base + i * 4)
                    else -> err("unexpected type ${e.type} for tag ${e.tag}")
                }
            }
        }

        /** The raw bytes of a BYTE, ASCII or UNDEFINED field, such as JPEGTables. */
        fun bytesOf(e: Entry): ByteArray {
            if (e.type != 1 && e.type != 2 && e.type != 7) err("field ${e.tag} requires bytes, got type ${e.type}")
            if (e.count < 0 || e.count > data.size) err("field ${e.tag} declares ${e.count} bytes, more than the file holds")
            val n = e.count.toInt()
            val base = if (n <= 4) e.valueOfs.toLong() else r.u32(e.valueOfs)
            if (base > data.size - n) err("field ${e.tag} runs past the end of the file")
            return data.copyOfRange(base.toInt(), base.toInt() + n)
        }

        // A field with a zero count carries no value at all, so it falls back to
        // the default exactly like an absent one.
        fun single(tag: Int, default: Long? = null): Long =
            entries[tag]?.let { values(it).firstOrNull() } ?: default ?: err("missing required tag $tag")

        val width = single(256).toInt()
        val height = single(257).toInt()
        if (width <= 0 || height <= 0) err("bad dimensions ${width}x$height")
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
            err("${width}x$height exceeds safety limits")
        }
        val compression = single(259, 1).toInt()
        val photometric = single(262).toInt()
        val spp = single(277, 1).toInt()
        if (spp < 1 || spp > 16) err("samples per pixel $spp out of range")
        val predictor = single(317, 1).toInt()
        val planar = single(284, 1).toInt()
        if (planar != 1 && planar != 2) err("unknown planar configuration $planar")

        // TIFF 6.0 specifies exact shapes for these optional fields. Defaults
        // apply to absent tags; a present malformed field cannot supply them.
        fun optionalValues(tag: Int, name: String, type: Int, count: Long): LongArray? =
            entries[tag]?.let {
                if (it.type != type || it.count != count) {
                    err("$name ($tag) requires type $type and $count values, got type ${it.type} and ${it.count}")
                }
                values(it)
            }
        val t4Options = if (compression == 3) optionalValues(292, "T4Options", 4, 1)?.first() ?: 0L else 0L
        if (compression == 3 && t4Options and 2L != 0L) {
            throw UnsupportedImageException("TIFF: CCITT G3 uncompressed mode (T4Options bit 1) is not supported")
        }
        val fillOrder = optionalValues(266, "FillOrder", 3, 1)?.first()?.toInt() ?: 1
        if (fillOrder != 1 && fillOrder != 2) err("unknown FillOrder $fillOrder")

        // JPEG strips and tiles (Technical Note 2). Each is a JPEG stream of its own,
        // usually abbreviated: the tables they share sit in JPEGTables (347).
        val jpeg = compression == 7
        val jpegTables = if (jpeg) entries[347]?.let { bytesOf(it) } else null

        val bitsEntry = entries[258]?.let { values(it) }?.takeIf { it.isNotEmpty() } ?: longArrayOf(1)
        val bits = bitsEntry[0].toInt()
        if (bitsEntry.any { it != bits.toLong() }) err("heterogeneous bits per sample")
        if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) {
            throw UnsupportedImageException("TIFF: $bits bits per sample is not supported (1, 2, 4, 8 or 16)")
        }
        // A T.6 reference row can be represented by one bit, regardless of its
        // width. Other compression paths expand stored bytes, including packing.
        if (compression !in 2..4 && !Budget.fits(width, height, data.size, bits * spp)) {
            err("${width}x$height cannot come from ${data.size} bytes at ${bits * spp} bits per pixel")
        }
        if (compression in 2..4 && (bits != 1 || spp != 1)) err("CCITT requires one bilevel sample")
        val extraSamples = entries[338]?.let {
            if (it.type != 3) err("ExtraSamples (338) requires SHORT values")
            if (it.count > 15) err("ExtraSamples count ${it.count} exceeds samples per pixel")
            values(it)
        }
        val alpha = TiffAlpha(spp, photometric, extraSamples)

        // TIFF 6.0 section 19 treats undefined samples like absent SampleFormat:
        // unsigned integers. Signed and floating samples need another sample path.
        val sampleFormats = optionalValues(339, "SampleFormat", 3, spp.toLong()) ?: longArrayOf(1)
        sampleFormats.firstOrNull { it != 1L && it != 4L }?.let {
            throw UnsupportedImageException("TIFF: SampleFormat $it is not supported (unsigned integer samples only)")
        }

        if (jpeg) {
            if (bits != 8) throw UnsupportedImageException("TIFF: JPEG with $bits-bit samples is not supported (8-bit only)")
            if (photometric == 6 && planar == 2) {
                throw UnsupportedImageException("TIFF: JPEG YCbCr in separate planes is not supported")
            }
        }
        // JPEG YCbCr leaves the JPEG decoder as RGB, upsampled and converted the way
        // libtiff's RGB color mode has libjpeg do it, which is what ImageMagick and
        // Pillow read; the TIFF fields about subsampling and ranges then describe
        // nothing that is left.
        val jpegRgb = jpeg && photometric == 6
        val pixels = if (jpegRgb) 2 else photometric

        val tiled = entries.containsKey(322)
        val oldJpeg = if (compression == 6) {
            if (bits != 8) throw UnsupportedImageException("TIFF: old-style JPEG with $bits-bit samples is not supported (8-bit only)")
            if (planar == 2 && spp > 1) throw UnsupportedImageException("TIFF: old-style JPEG in separate planes is not supported")
            if (single(512, 1) == 14L) throw UnsupportedImageException("TIFF: old-style lossless JPEG is not supported")
            val subsampling = if (photometric == 6) optionalValues(530, "YCbCrSubSampling", 3, 2) ?: longArrayOf(2, 2) else longArrayOf(1, 1)
            fun longs(tag: Int) = entries[tag]?.let { values(it) }
            TiffOldJpeg.decode(data, TiffOldJpeg.Source(
                width = width,
                height = height,
                tiled = tiled,
                strileWidth = if (tiled) single(322).toInt() else width,
                strileLength = if (tiled) single(323).toInt() else single(278, height.toLong()).coerceAtMost(height.toLong()).toInt(),
                samples = spp,
                ycbcr = photometric == 6,
                tagSubH = subsampling[0].toInt(),
                tagSubV = subsampling[1].toInt(),
                interchangeFormat = single(513, 0),
                interchangeFormatLength = single(514, 0),
                restartIntervalTag = single(515, 0).toInt(),
                qTables = longs(519),
                dcTables = longs(520),
                acTables = longs(521),
                strileOffsets = longs(if (tiled) 324 else 273) ?: err("missing block offsets"),
                strileCounts = longs(if (tiled) 325 else 279),
            ))
        } else null

        val references = if (pixels == 2 || pixels == 6) entries[532]?.takeUnless { jpegRgb }?.let {
            TiffReferenceBlackWhite.read(data, le, it.type, it.count, it.valueOfs)
        } else null

        // Chroma subsampling only exists for YCbCr; everything else is 1:1. An old-style JPEG
        // stream says what it really has, which libtiff trusts over the tag.
        val subSampling = if (oldJpeg != null) {
            longArrayOf(oldJpeg.subH.toLong(), oldJpeg.subV.toLong())
        } else if (pixels == 6) {
            optionalValues(530, "YCbCrSubSampling", 3, 2) ?: longArrayOf(2, 2)
        } else {
            longArrayOf(1, 1)
        }
        val subH = subSampling[0].toInt()
        val subV = subSampling[1].toInt()
        if (photometric == 6) {
            if (subH !in intArrayOf(1, 2, 4) || subV !in intArrayOf(1, 2, 4)) {
                err("YCbCr subsampling ${subH}x$subV is not legal")
            }
            if (bits != 8) throw UnsupportedImageException("TIFF: YCbCr needs 8-bit samples")
            if (spp != 3) err("YCbCr with $spp samples")
        }

        // --- geometry: strips or tiles, one plane or several ---------------------
        val planes = if (planar == 2) spp else 1
        val samplesPerPlane = if (planar == 2) 1 else spp

        // A subsampled YCbCr row group stores h×v luma plus one of each chroma per
        // unit, so its "row" is a group of `subV` image rows.
        val ycbcrUnits = pixels == 6 && planar == 1 && (subH > 1 || subV > 1)
        val unitsAcross = (width + subH - 1) / subH
        val unitBytes = subH * subV + 2

        val planeRowBytes = if (ycbcrUnits) {
            unitsAcross * unitBytes
        } else {
            rowBytes(width, samplesPerPlane, bits).toInt()
        }
        val planeRows = if (ycbcrUnits) (height + subV - 1) / subV else height
        if (planeRowBytes.toLong() * planeRows * planes > MAX_BUFFER_BYTES) {
            err("sample buffer of ${planeRowBytes.toLong() * planeRows * planes} bytes exceeds safety limits")
        }

        val blocks = ArrayList<ByteArray>(planes)
        val tileWidth = if (tiled) single(322).toInt() else width
        val tileLength = if (tiled) single(323).toInt() else 0
        if (tiled) {
            if (tileWidth <= 0 || tileLength <= 0) err("bad tile size ${tileWidth}x$tileLength")
            if (tileWidth > MAX_DIMENSION || tileLength > MAX_DIMENSION) err("tile ${tileWidth}x$tileLength too large")
            if (ycbcrUnits && (tileWidth % subH != 0 || tileLength % subV != 0)) {
                err("tile ${tileWidth}x$tileLength does not align with YCbCr subsampling ${subH}x$subV")
            }
            val tileBytes = if (ycbcrUnits) {
                (tileWidth / subH).toLong() * (tileLength / subV) * unitBytes
            } else rowBytes(tileWidth, samplesPerPlane, bits) * tileLength
            if (tileBytes > MAX_BUFFER_BYTES) err("tile of $tileBytes bytes exceeds safety limits")
        }

        val offsetsTag = if (tiled) 324 else 273
        val countsTag = if (tiled) 325 else 279
        val offsets = entries[offsetsTag]?.let { values(it) } ?: err("missing block offsets")
        val counts = entries[countsTag]?.let { values(it) }
            ?: if (compression == 1 || oldJpeg != null) LongArray(offsets.size) { Long.MAX_VALUE } else err("missing block byte counts")
        if (offsets.size != counts.size) err("block offset/count mismatch")

        // Reject missing/out-of-range blocks before reserving the sample plane.
        val rowsPerStrip = if (tiled) 0 else single(278, 0xFFFFFFFFL).coerceAtMost(planeRows.toLong()).toInt()
        if (!tiled && rowsPerStrip <= 0) err("rows per strip is zero")
        val blocksPerPlane = if (tiled) {
            ((width + tileWidth - 1) / tileWidth).toLong() * ((height + tileLength - 1) / tileLength)
        } else ((planeRows + rowsPerStrip - 1) / rowsPerStrip).toLong()
        val requiredBlocks = blocksPerPlane * planes
        if (requiredBlocks > offsets.size) err("need $requiredBlocks blocks, only ${offsets.size} declared")
        // An old-style JPEG reads its blocks as libtiff does, skipping an empty or missing one.
        if (oldJpeg == null) for (index in 0 until requiredBlocks.toInt()) {
            if (offsets[index] < 0 || offsets[index] >= data.size) err("block $index offset out of range")
            if (compression != 1 && counts[index] == 0L) err("block $index is empty")
        }

        /** Decompress block [index], which is expected to hold [expect] bytes. */
        fun block(index: Int, expect: Int, rows: Int, columns: Int): ByteArray {
            if (index >= offsets.size) err("block $index beyond the ${offsets.size} declared")
            val ofs = offsets[index].toInt()
            if (ofs < 0 || ofs > data.size) err("block $index offset out of range")
            if (compression == 1) {
                // TIFF's uncompressed size is determined by geometry. Like libtiff,
                // recover a bogus byte count only when all those bytes are present.
                if (expect > data.size - ofs) err("block $index ends early: need $expect bytes, have ${data.size - ofs}")
                return normalizeFillOrder(data.copyOfRange(ofs, ofs + expect), fillOrder)
            }
            val len = minOf(counts[index], (data.size - ofs).toLong()).toInt()
            // libtiff never reverses the bits of a JPEG stream, whatever FillOrder says.
            if (jpeg) return jpegBlock(index, data.copyOfRange(ofs, ofs + len), jpegTables, rows, columns, samplesPerPlane, jpegRgb)
            val comp = normalizeFillOrder(data.copyOfRange(ofs, ofs + len), fillOrder)
            fun fax(k: Int, byteAligned: Boolean, endOfLine: Boolean = false): ByteArray {
                if (bits != 1 || spp != 1) err("CCITT requires one bilevel sample")
                return ccittStrip(comp, k, columns, rows, byteAligned, endOfLine)
            }
            val decoded = when (compression) {
                5 -> tiffLzw(comp, expect)
                8, 32946 -> try {
                    Zlib.decompress(comp, expect.toLong())
                } catch (e: InflateException) {
                    throw ImageDecodeException("TIFF: strip inflate failed: ${e.message}", e)
                }
                32773 -> packBits(comp, expect)
                2 -> fax(k = 0, byteAligned = true)
                3 -> {
                    // T4Options fill zeros align the end of EOL, not the row cursor.
                    fax(k = if (t4Options and 1L != 0L) 1 else 0, byteAligned = false, endOfLine = true)
                }
                4 -> fax(k = -1, byteAligned = false)
                else -> throw UnsupportedImageException("TIFF: compression $compression is not supported")
            }
            if (decoded.size != expect) err("block $index decoded ${decoded.size} bytes, expected $expect")
            return decoded
        }

        // TIFF 6.0 sections 14/15: prediction starts anew at each tile row,
        // including padding, before that row is joined to its neighbours.
        fun restorePredictor(buffer: ByteArray, rowBytes: Int, rows: Int) {
            // A predictor belongs to the lossless codecs; libtiff's JPEG codec never sets one up.
            if (predictor == 1 || jpeg) return
            if (predictor != 2) throw UnsupportedImageException("TIFF: predictor $predictor is not supported")
            val stride = samplesPerPlane
            for (y in 0 until rows) {
                val ro = y * rowBytes
                when (bits) {
                    8 -> for (i in stride until rowBytes) {
                        buffer[ro + i] = (buffer[ro + i] + buffer[ro + i - stride]).toByte()
                    }
                    16 -> {
                        // Differencing is per 16-bit sample, in the file's byte order.
                        val samples = rowBytes / 2
                        for (i in stride until samples) {
                            val prev = read16(buffer, ro + (i - stride) * 2, le)
                            val cur = read16(buffer, ro + i * 2, le)
                            write16(buffer, ro + i * 2, (cur + prev) and 0xFFFF, le)
                        }
                    }
                    else -> throw UnsupportedImageException(
                        "TIFF: predictor 2 with $bits-bit samples is not supported",
                    )
                }
            }
        }

        if (oldJpeg != null) {
            blocks.add(oldJpegSamples(oldJpeg, width, height, spp, planeRowBytes, planeRows, ycbcrUnits, unitsAcross, unitBytes))
        } else if (tiled) {
            val across = (width + tileWidth - 1) / tileWidth
            val down = (height + tileLength - 1) / tileLength
            // Tiles are always padded to their full size, even at the right and
            // bottom edges: the padding is real data on the wire, just discarded.
            // Fits an Int: the whole tile passed the buffer ceiling above.
            val tileRowBytes = if (ycbcrUnits) (tileWidth / subH) * unitBytes
                else rowBytes(tileWidth, samplesPerPlane, bits).toInt()
            val tileRows = if (ycbcrUnits) tileLength / subV else tileLength
            val tilesPerPlane = across * down
            for (p in 0 until planes) {
                val plane = ByteArray(planeRowBytes * planeRows)
                for (ty in 0 until down) {
                    for (tx in 0 until across) {
                        val index = p * tilesPerPlane + ty * across + tx
                        val tile = block(index, tileRowBytes * tileRows, tileLength, tileWidth)
                        restorePredictor(tile, tileRowBytes, tileRows)
                        val copyBytes = minOf(tileRowBytes, planeRowBytes - tx * tileRowBytes)
                        if (copyBytes <= 0) continue
                        for (row in 0 until minOf(tileRows, planeRows - ty * tileRows)) {
                            val src = row * tileRowBytes
                            if (src + copyBytes > tile.size) break
                            tile.copyInto(
                                plane,
                                destinationOffset = (ty * tileRows + row) * planeRowBytes + tx * tileRowBytes,
                                startIndex = src,
                                endIndex = src + copyBytes,
                            )
                        }
                    }
                }
                blocks.add(plane)
            }
        } else {
            val stripsPerPlane = (planeRows + rowsPerStrip - 1) / rowsPerStrip
            for (p in 0 until planes) {
                val plane = ByteArray(planeRowBytes * planeRows)
                var at = 0
                for (s in 0 until stripsPerPlane) {
                    val stripRows = minOf(rowsPerStrip, planeRows - s * rowsPerStrip)
                    if (stripRows <= 0) break
                    val expect = planeRowBytes * stripRows
                    val strip = block(p * stripsPerPlane + s, expect, stripRows, width)
                    restorePredictor(strip, planeRowBytes, stripRows)
                    strip.copyInto(plane, at, 0, minOf(expect, strip.size))
                    at += expect
                }
                blocks.add(plane)
            }
        }

        // --- sample access --------------------------------------------------------
        /** Sample [c] of pixel ([x], [y]) at its native bit depth. */
        fun sample(x: Int, y: Int, c: Int): Int {
            val plane = if (planar == 2) blocks[c] else blocks[0]
            val index = if (planar == 2) x else x * spp + c
            val row = y * planeRowBytes
            return when (bits) {
                8 -> plane[row + index].toInt() and 0xFF
                16 -> read16(plane, row + index * 2, le)
                else -> {
                    val bitPos = index * bits
                    val byte = plane[row + (bitPos ushr 3)].toInt() and 0xFF
                    (byte ushr (8 - bits - (bitPos and 7))) and ((1 shl bits) - 1)
                }
            }
        }

        /** Native-depth sample to 8 bits, by replication for the small depths. */
        fun scale8(v: Int): Int = when (bits) {
            1 -> v * 255
            2 -> v * 85
            4 -> v * 17
            8 -> v
            else -> v ushr 8                     // 16-bit narrows to its high byte
        }

        val sampleMax = (1 shl bits) - 1
        fun straight(value: Int, opacity: Int, max: Int = sampleMax): Int {
            if (!alpha.associated) return value
            // TIFF 6.0 section 18 defines zero color for zero associated alpha.
            if (opacity == 0) return 0
            return minOf(max.toLong(), (value.toLong() * sampleMax + opacity / 2) / opacity).toInt()
        }
        fun opacity(x: Int, y: Int): Int = if (alpha.sample >= 0) sample(x, y, alpha.sample) else sampleMax

        return object : Raster {
        override fun argb(): KiteBitmap {
            val argb = IntArray(width * height)
            when (pixels) {
                0, 1 -> {
                    val invert = photometric == 0    // WhiteIsZero
                    for (y in 0 until height) for (x in 0 until width) {
                        val opacity = opacity(x, y)
                        var g = scale8(straight(sample(x, y, 0), opacity))
                        if (invert) g = 255 - g
                        if (alpha.associated && opacity == 0) g = 0
                        val a = scale8(opacity)
                        argb[y * width + x] = (a shl 24) or (g shl 16) or (g shl 8) or g
                    }
                }
                2 -> {
                    if (spp < 3) err("RGB with $spp samples")
                    val ranges = references?.rgbTables(bits)
                    for (y in 0 until height) for (x in 0 until width) {
                        val opacity = opacity(x, y)
                        val a = scale8(opacity)
                        fun channel(c: Int): Int {
                            if (alpha.associated && opacity == 0) return 0
                            val value = straight(sample(x, y, c), opacity)
                            return ranges?.get(c)?.get(value) ?: scale8(value)
                        }
                        argb[y * width + x] = (a shl 24) or
                            (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                    }
                }
                3 -> {
                    val mapEntry = entries[320] ?: err("palette image without ColorMap")
                    val map = values(mapEntry)
                    val n = 1 shl bits
                    if (map.size < 3 * n) err("ColorMap too small")
                    for (y in 0 until height) for (x in 0 until width) {
                        val idx = sample(x, y, 0)
                        if (idx >= n) err("palette index $idx out of $n entries")
                        // ColorMap entries are 16-bit; take the high byte.
                        val opacity = opacity(x, y)
                        val rr = straight(map[idx].toInt(), opacity, 65535) ushr 8
                        val gg = straight(map[n + idx].toInt(), opacity, 65535) ushr 8
                        val bb = straight(map[2 * n + idx].toInt(), opacity, 65535) ushr 8
                        argb[y * width + x] = (scale8(opacity) shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
                6 -> {
                    val ranges = references?.ycbcrTables()
                    for (y in 0 until height) for (x in 0 until width) {
                        val yy: Int
                        val cb: Int
                        val cr: Int
                        if (ycbcrUnits) {
                            // Chunky subsampled layout: each unit is subH×subV luma
                            // samples followed by one Cb and one Cr for the whole unit.
                            val unit = (y / subV) * unitsAcross + (x / subH)
                            val base = unit * unitBytes
                            val within = (y % subV) * subH + (x % subH)
                            val plane = blocks[0]
                            if (base + unitBytes > plane.size) err("YCbCr unit past the data")
                            yy = plane[base + within].toInt() and 0xFF
                            cb = plane[base + subH * subV].toInt() and 0xFF
                            cr = plane[base + subH * subV + 1].toInt() and 0xFF
                        } else if (planar == 2) {
                            // Separate planes: chroma planes are stored at reduced size.
                            yy = blocks[0][y * planeRowBytes + x].toInt() and 0xFF
                            val cw = (width + subH - 1) / subH
                            val ci = (y / subV) * cw + (x / subH)
                            cb = blocks[1].getOrElse(ci) { 0 }.toInt() and 0xFF
                            cr = blocks[2].getOrElse(ci) { 0 }.toInt() and 0xFF
                        } else {
                            yy = sample(x, y, 0)
                            cb = sample(x, y, 1)
                            cr = sample(x, y, 2)
                        }
                        argb[y * width + x] = if (references != null && ranges != null) {
                            references.ycbcrToArgb(yy, cb, cr, ranges)
                        } else ycbcrToArgb(yy, cb, cr)
                    }
                }
                else -> throw UnsupportedImageException("TIFF: photometric $pixels is not supported")
            }

            return KiteBitmap(width, height, argb)
        }

        override fun wide(): KiteBitmap16 {
            // Narrower samples replicate up, as scale8 replicates them to 8 bits, so the
            // high byte of each value is exactly what argb() gives.
            fun scale16(v: Int): Int = when (bits) {
                1 -> v * 65535
                2 -> v * 21845
                4 -> v * 4369
                8 -> v * 257
                else -> v
            }
            val withAlpha = alpha.sample >= 0
            return when (pixels) {
                0, 1 -> {
                    val channels = if (withAlpha) 2 else 1
                    val out = ShortArray(width * height * channels)
                    val invert = photometric == 0    // WhiteIsZero
                    for (y in 0 until height) for (x in 0 until width) {
                        val opacity = opacity(x, y)
                        var g = scale16(straight(sample(x, y, 0), opacity))
                        if (invert) g = 65535 - g
                        if (alpha.associated && opacity == 0) g = 0
                        val at = (y * width + x) * channels
                        out[at] = g.toShort()
                        if (withAlpha) out[at + 1] = scale16(opacity).toShort()
                    }
                    KiteBitmap16(width, height, channels, out)
                }
                2 -> {
                    if (spp < 3) err("RGB with $spp samples")
                    val ranges = references?.rgbTables16(bits)
                    val channels = if (withAlpha) 4 else 3
                    val out = ShortArray(width * height * channels)
                    for (y in 0 until height) for (x in 0 until width) {
                        val opacity = opacity(x, y)
                        val at = (y * width + x) * channels
                        for (c in 0 until 3) {
                            out[at + c] = if (alpha.associated && opacity == 0) 0 else {
                                val value = straight(sample(x, y, c), opacity)
                                (ranges?.get(c)?.get(value) ?: scale16(value)).toShort()
                            }
                        }
                        if (withAlpha) out[at + 3] = scale16(opacity).toShort()
                    }
                    KiteBitmap16(width, height, channels, out)
                }
                3 -> {
                    // The ColorMap holds 16-bit entries, all of which survive here.
                    val mapEntry = entries[320] ?: err("palette image without ColorMap")
                    val map = values(mapEntry)
                    val n = 1 shl bits
                    if (map.size < 3 * n) err("ColorMap too small")
                    val channels = if (withAlpha) 4 else 3
                    val out = ShortArray(width * height * channels)
                    for (y in 0 until height) for (x in 0 until width) {
                        val idx = sample(x, y, 0)
                        if (idx >= n) err("palette index $idx out of $n entries")
                        val opacity = opacity(x, y)
                        val at = (y * width + x) * channels
                        for (c in 0 until 3) out[at + c] = straight(map[c * n + idx].toInt(), opacity, 65535).toShort()
                        if (withAlpha) out[at + 3] = scale16(opacity).toShort()
                    }
                    KiteBitmap16(width, height, channels, out)
                }
                // YCbCr is 8-bit only, so its 8-bit conversion widens without loss.
                else -> KiteBitmap16.widened(argb(), alpha = false)
            }
        }
        }
    }

    /**
     * JPEG block [index]: the shared [tables] with their EOI removed, then the block's
     * own stream with its SOI removed, as Technical Note 2 lays them out and libtiff
     * feeds them to libjpeg. The block must code [columns] by [rows] pixels of
     * [samples] components; a last strip coded at the full strip height is cut to its
     * rows, as libtiff allows. With [ycbcr] the components convert to RGB.
     */
    private fun jpegBlock(
        index: Int,
        block: ByteArray,
        tables: ByteArray?,
        rows: Int,
        columns: Int,
        samples: Int,
        ycbcr: Boolean,
    ): ByteArray {
        val stream = if (tables == null) block else {
            val head = if (tables.size >= 4 && marker(tables, tables.size - 2) == 0xD9) tables.size - 2 else tables.size
            val skip = if (block.size >= 2 && marker(block, 0) == 0xD8) 2 else 0
            tables.copyOf(head) + block.copyOfRange(skip, block.size)
        }
        val decoded = try {
            JpegDecoder.decodeComponents(stream)
        } catch (e: UnsupportedImageException) {
            throw UnsupportedImageException("TIFF: JPEG block $index: ${e.message}", e)
        } catch (e: ImageDecodeException) {
            throw ImageDecodeException("TIFF: JPEG block $index: ${e.message}", e)
        }
        val c = decoded.components
        if (c.width != columns || c.height < rows) {
            err("JPEG block $index codes ${c.width}x${c.height} pixels for a ${columns}x$rows block")
        }
        if (c.componentCount != samples) {
            err("JPEG block $index has ${c.componentCount} components for $samples samples")
        }
        val out = ByteArray(columns * rows * samples)
        if (ycbcr) {
            val rgb = JpegDecoder.toBitmap(c, JpegDecoder.ColorModel.YCBCR).argb
            for (i in 0 until columns * rows) {
                val p = rgb[i]
                out[i * 3] = (p ushr 16).toByte()
                out[i * 3 + 1] = (p ushr 8).toByte()
                out[i * 3 + 2] = p.toByte()
            }
        } else {
            c.samples.copyInto(out, 0, 0, out.size)
        }
        return out
    }

    /**
     * The samples of an old-style JPEG image laid out as an uncompressed TIFF holds them:
     * YCbCr units of [unitBytes] when the chroma is subsampled, else [samples] a pixel.
     */
    private fun oldJpegSamples(
        image: TiffOldJpeg.Image,
        width: Int,
        height: Int,
        samples: Int,
        planeRowBytes: Int,
        planeRows: Int,
        units: Boolean,
        unitsAcross: Int,
        unitBytes: Int,
    ): ByteArray {
        val out = ByteArray(planeRowBytes * planeRows)
        val upsampled = image.upsampled
        if (upsampled != null) {
            if (upsampled.componentCount != samples || upsampled.width < width || upsampled.height < height) {
                err("old-style JPEG frame does not cover the ${width}x$height image")
            }
            val stride = upsampled.width * samples
            for (y in 0 until height) {
                upsampled.samples.copyInto(out, y * planeRowBytes, y * stride, y * stride + width * samples)
            }
            return out
        }
        val planes = image.planes!!.planes
        if (planes.size != samples) err("old-style JPEG has ${planes.size} components for $samples samples")
        // Every plane must hold what the layout reads from it; a frame whose sampling
        // disagrees with the TIFF fields is damaged.
        fun covers(plane: JpegDecoder.Plane, across: Int, down: Int) =
            plane.stride >= across && plane.samples.size / plane.stride >= down
        if (units) {
            val subH = image.subH
            val subV = image.subV
            val (luma, cb, cr) = planes
            if (!covers(luma, unitsAcross * subH, planeRows * subV) || !covers(cb, unitsAcross, planeRows) ||
                !covers(cr, unitsAcross, planeRows)
            ) {
                err("old-style JPEG planes do not cover the ${width}x$height image")
            }
            for (uy in 0 until planeRows) for (ux in 0 until unitsAcross) {
                val base = uy * planeRowBytes + ux * unitBytes
                for (j in 0 until subV) {
                    val row = (uy * subV + j) * luma.stride + ux * subH
                    for (i in 0 until subH) out[base + j * subH + i] = luma.samples[row + i]
                }
                out[base + subH * subV] = cb.samples[uy * cb.stride + ux]
                out[base + subH * subV + 1] = cr.samples[uy * cr.stride + ux]
            }
        } else {
            if (planes.any { !covers(it, width, height) }) err("old-style JPEG planes do not cover the ${width}x$height image")
            for (y in 0 until height) for (x in 0 until width) for (c in 0 until samples) {
                out[y * planeRowBytes + x * samples + c] = planes[c].samples[y * planes[c].stride + x]
            }
        }
        return out
    }

    private fun marker(d: ByteArray, at: Int): Int =
        if ((d[at].toInt() and 0xFF) == 0xFF) d[at + 1].toInt() and 0xFF else -1

    private fun normalizeFillOrder(buffer: ByteArray, fillOrder: Int): ByteArray {
        // Like libtiff's TIFFFillStrip/Tile, reverse stored bytes before the codec:
        // FillOrder=2 reverses compressed framing too, including Deflate headers.
        if (fillOrder == 2) for (i in buffer.indices) {
            buffer[i] = reversedBits[buffer[i].toInt() and 255]
        }
        return buffer
    }

    private fun read16(d: ByteArray, at: Int, le: Boolean): Int {
        if (at + 2 > d.size) return 0
        val a = d[at].toInt() and 0xFF
        val b = d[at + 1].toInt() and 0xFF
        return if (le) a or (b shl 8) else (a shl 8) or b
    }

    private fun write16(d: ByteArray, at: Int, v: Int, le: Boolean) {
        if (at + 2 > d.size) return
        if (le) {
            d[at] = (v and 0xFF).toByte(); d[at + 1] = ((v ushr 8) and 0xFF).toByte()
        } else {
            d[at] = ((v ushr 8) and 0xFF).toByte(); d[at + 1] = (v and 0xFF).toByte()
        }
    }

    /**
     * YCbCr → RGB with the exact CCIR 601-1 fractions TIFF specifies, in integers
     * so every target agrees. The absent-tag full-range case avoids building
     * reference tables; declared ranges use [TiffReferenceBlackWhite].
     */
    private fun ycbcrToArgb(y: Int, cb: Int, cr: Int): Int {
        val chromaB = cb - 128
        val chromaR = cr - 128
        val r = (y * 500 + 701 * chromaR + 250) / 500
        val g = (y * 293500 - 101004 * chromaB - 209599 * chromaR + 146750) / 293500
        val b = (y * 250 + 443 * chromaB + 125) / 250
        return (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
    }

    private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /** CCITT run colors become TIFF samples; PhotometricInterpretation is applied later. */
    private fun ccittStrip(
        comp: ByteArray, k: Int, width: Int, rows: Int, byteAligned: Boolean, endOfLine: Boolean = false,
    ): ByteArray {
        val opts = CcittOptions(
            columns = width, rows = rows, endOfBlock = false,
            blackIs1 = true, encodedByteAlign = byteAligned, endOfLine = endOfLine,
        )
        return CcittFax.decode(comp, k, opts)
    }

    /** TIFF flavor of LZW: MSB-first codes, EarlyChange (code width bumps one early). */
    private fun tiffLzw(input: ByteArray, expected: Int): ByteArray {
        val clear = 256
        val eoi = 257
        val out = ByteArray(expected)
        var outAt = 0

        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val first = ByteArray(4096)
        for (i in 0 until 256) {
            suffix[i] = i.toByte()
            first[i] = i.toByte()
        }
        val stack = ByteArray(4096)

        var codeSize = 9
        var avail = 258
        var oldCode = -1

        var bitBuf = 0
        var bitCnt = 0
        var inAt = 0

        fun readCode(): Int {
            while (bitCnt < codeSize) {
                if (inAt == input.size) return -1
                bitBuf = (bitBuf shl 8) or (input[inAt++].toInt() and 0xFF)
                bitCnt += 8
            }
            val code = (bitBuf ushr (bitCnt - codeSize)) and ((1 shl codeSize) - 1)
            bitCnt -= codeSize
            return code
        }

        fun emit(code: Int) {
            var c = code
            var sp = 0
            while (c >= clear) {
                stack[sp++] = suffix[c]
                c = prefix[c]
                if (sp >= stack.size) err("corrupt LZW chain")
            }
            stack[sp++] = suffix[c]
            while (sp > 0 && outAt < expected) out[outAt++] = stack[--sp]
        }

        while (outAt < expected) {
            val code = readCode()
            when {
                code == -1 -> err("LZW data ended after $outAt of $expected bytes")
                code == eoi -> break
                code == clear -> {
                    codeSize = 9
                    avail = 258
                    oldCode = -1
                }
                oldCode == -1 -> {
                    if (code >= clear) err("first LZW code $code not a literal")
                    out[outAt++] = code.toByte()
                    oldCode = code
                }
                else -> {
                    if (code > avail) err("LZW code $code beyond dictionary")
                    if (code == avail) {
                        prefix[avail] = oldCode
                        suffix[avail] = first[oldCode]
                        first[avail] = first[oldCode]
                        avail++
                        emit(code)
                    } else {
                        emit(code)
                        if (avail < 4096) {
                            prefix[avail] = oldCode
                            suffix[avail] = first[code]
                            first[avail] = first[oldCode]
                            avail++
                        }
                    }
                    // EarlyChange: width grows one code before the table fills.
                    if (avail == (1 shl codeSize) - 1 && codeSize < 12) codeSize++
                    oldCode = code
                }
            }
        }
        if (outAt != expected) err("LZW data ended after $outAt of $expected bytes")
        return out
    }

    /** PackBits (Apple RLE). */
    private fun packBits(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var o = 0
        var i = 0
        while (o < expected && i < input.size) {
            val n = input[i++].toInt()
            when {
                n >= 0 -> {
                    val run = n + 1
                    if (i + run > input.size) err("PackBits literal overruns input")
                    for (k in 0 until run) {
                        if (o < expected) out[o++] = input[i + k]
                    }
                    i += run
                }
                n != -128 -> {
                    if (i >= input.size) err("PackBits run overruns input")
                    val b = input[i++]
                    repeat(1 - n) { if (o < expected) out[o++] = b }
                }
                // -128: no-op
            }
        }
        if (o != expected) err("PackBits data ended after $o of $expected bytes")
        return out
    }
}
