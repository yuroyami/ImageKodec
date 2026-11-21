package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.lzw.Lzw
import io.github.yuroyami.archivekodec.codec.lzw.LzwOptions
import io.github.yuroyami.archivekodec.codec.packbits.PackBits
import io.github.yuroyami.archivekodec.codec.packbits.PackBitsOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.internal.color.Ink
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
 *    Deflate (8 / 32946), PackBits (32773), LZMA2 in an xz stream (34925) and
 *    Zstandard (50000), the two libtiff writes with `-c lzma` and `-c zstd`
 *  - photometric 0/1 (bilevel + gray, either polarity, optional alpha), 2 (RGB,
 *    optional associated or straight alpha via ExtraSamples), 3 (palette, 16-bit
 *    ColorMap entries), 5 (**CMYK** separations, InkSet 1, at 8 or 16 bits, drawn
 *    as libtiff's RGBA interface draws them, or through the page's profile by
 *    [decodeInk]), 6 (**YCbCr**, including chroma subsampling in both the
 *    chunky unit layout and separate planes)
 *  - unsigned and signed integer samples of 1 to 32 bits, and IEEE floating-point
 *    samples of 16, 24, 32 and 64 bits ([widen]); [decode] narrows samples deeper
 *    than 8 bits to their high byte, and [decode16] keeps 16 bits of them
 *  - the horizontal-differencing predictor (2) for samples of 8, 16, 32 and 64
 *    bits, and the floating-point predictor (3) of Adobe's Photoshop TIFF
 *    Technical Note 3
 *  - **both planar configurations**: chunky (1) and separate planes (2)
 *  - both FillOrder values, independent of sample byte order and compression
 *  - CCITT's uncompressed mode, which T4Options bit 1 allows
 *
 * What is left out is named at the point of failure, by [sampleRefusal] and
 * [predictorRefusal] among others, which the probe shares.
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
     * The SampleFormat (339) of a page: 1 for unsigned integers, 2 for signed ones, 3 for IEEE
     * floating point, or another value this decoder refuses. TIFF 6.0 section 19 reads 4, undefined
     * data, as unsigned, and so does libtiff. A page whose samples are of several formats gives -1.
     */
    internal fun sampleFormat(formats: List<Long>): Int {
        val defined = formats.map { if (it == 4L) 1L else it }.distinct()
        return if (defined.size > 1) -1 else defined.firstOrNull()?.toInt() ?: 1
    }

    /**
     * Why samples of [bits] bits in SampleFormat [format] cannot be read on a page of [photometric]
     * and [compression], or null. TIFF 6.0 indexes a palette and codes YCbCr with unsigned integers,
     * and no reader takes YCbCr past 8 bits: libtiff's RGBA interface refuses it, ImageMagick hands
     * the samples on without converting them and ImageIO misreads them, so there is nothing to
     * check a reading against. Complex samples (5 and 6) have no picture to show.
     */
    internal fun sampleRefusal(bits: Int, format: Int, photometric: Int, compression: Int): String? = when {
        format == -1 -> "samples of several SampleFormat values"
        format == 5 || format == 6 -> "complex samples (SampleFormat $format)"
        format !in 1..3 -> "SampleFormat $format"
        format == 3 && bits !in intArrayOf(16, 24, 32, 64) -> "$bits-bit floating-point samples (16, 24, 32 or 64)"
        format != 3 && bits !in 1..32 -> "$bits-bit integer samples (1 to 32)"
        compression == 7 && (format != 1 || (bits != 8 && bits != 12)) -> "JPEG with $bits-bit samples (8 or 12)"
        compression == 6 && (format != 1 || bits != 8) -> "old-style JPEG with $bits-bit samples (8-bit only)"
        photometric == 3 && (format != 1 || bits > 16) -> "a palette indexed by $bits-bit ${if (format == 1) "" else "signed or floating-point "}samples"
        photometric == 6 && compression != 7 && (format != 1 || bits != 8) -> "YCbCr with $bits-bit samples (8-bit only)"
        else -> null
    }

    /**
     * Why [predictor] cannot be undone for samples of [bits] bits in SampleFormat [format], or null.
     * libtiff differences samples of 8, 16, 32 and 64 bits, and takes the floating-point predictor
     * for floating-point samples alone; the JPEG codecs set up no predictor at all.
     */
    internal fun predictorRefusal(predictor: Int, bits: Int, format: Int, compression: Int): String? = when {
        predictor == 1 || compression == 6 || compression == 7 -> null
        predictor == 2 -> if (bits in intArrayOf(8, 16, 32, 64)) null else "predictor 2 with $bits-bit samples"
        predictor == 3 -> if (format == 3) null else "predictor 3 with integer samples"
        else -> "predictor $predictor"
    }

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
    fun decode(data: ByteArray, page: Int = 0, cancel: (() -> Unit)? = null): KiteBitmap = read(data, page, cancel).argb()

    /** Decodes page [page] at 16 bits a sample, in the channels it stores. */
    fun decode16(data: ByteArray, page: Int = 0, cancel: (() -> Unit)? = null): KiteBitmap16 = read(data, page, cancel).wide()

    /**
     * The ink of page [page] when it is CMYK, at 16 bits with [wide] and else at 8, for
     * conversion through its profile; null for any other photometric interpretation.
     */
    fun decodeInk(data: ByteArray, page: Int = 0, wide: Boolean = false, cancel: (() -> Unit)? = null): Ink? = read(data, page, cancel).ink(wide)

    /** A page whose samples are read and ready to convert, at 8 or at 16 bits a channel. */
    private interface Raster {
        fun argb(): KiteBitmap
        fun wide(): KiteBitmap16
        fun ink(wide: Boolean): Ink?
    }

    /** [cancel] runs before each strip or tile, the coarse step of a TIFF decode (#123). */
    private fun read(data: ByteArray, page: Int, cancel: (() -> Unit)? = null): Raster {
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
        val planarTag = single(284, 1).toInt()
        if (planarTag != 1 && planarTag != 2) err("unknown planar configuration $planarTag")

        // TIFF 6.0 specifies exact shapes for these optional fields. Defaults
        // apply to absent tags; a present malformed field cannot supply them.
        fun optionalValues(tag: Int, name: String, type: Int, count: Long): LongArray? =
            entries[tag]?.let {
                if (it.type != type || it.count != count) {
                    err("$name ($tag) requires type $type and $count values, got type ${it.type} and ${it.count}")
                }
                values(it)
            }
        // Bit 1, the uncompressed mode, needs nothing of its own: CcittFax reads it wherever it comes.
        val t4Options = if (compression == 3) optionalValues(292, "T4Options", 4, 1)?.first() ?: 0L else 0L
        val fillOrder = optionalValues(266, "FillOrder", 3, 1)?.first()?.toInt() ?: 1
        if (fillOrder != 1 && fillOrder != 2) err("unknown FillOrder $fillOrder")

        // JPEG strips and tiles (Technical Note 2). Each is a JPEG stream of its own,
        // usually abbreviated: the tables they share sit in JPEGTables (347).
        val jpeg = compression == 7
        val jpegTables = if (jpeg) entries[347]?.let { bytesOf(it) } else null

        val bitsEntry = entries[258]?.let { values(it) }?.takeIf { it.isNotEmpty() } ?: longArrayOf(1)
        val bits = bitsEntry[0].toInt()
        if (bitsEntry.any { it != bits.toLong() }) err("heterogeneous bits per sample")
        if (bits < 1) err("$bits bits per sample")
        val sampleFormats = optionalValues(339, "SampleFormat", 3, spp.toLong()) ?: longArrayOf(1)
        val format = sampleFormat(sampleFormats.toList())
        sampleRefusal(bits, format, photometric, compression)?.let { throw UnsupportedImageException("TIFF: $it is not supported") }
        predictorRefusal(predictor, bits, format, compression)?.let { throw UnsupportedImageException("TIFF: $it is not supported") }
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

        // A 12-bit JPEG strip leaves the JPEG decoder as 16-bit samples, so the page is laid out at 16.
        val jpeg12 = jpeg && bits == 12
        val layoutBits = if (jpeg12) 16 else bits
        // JPEG YCbCr in one plane leaves the JPEG decoder as RGB, upsampled and converted
        // the way libtiff's RGB color mode has libjpeg do it, which is what ImageMagick and
        // Pillow read; the TIFF fields about subsampling and ranges then describe nothing
        // that is left. In separate planes each plane is a JPEG stream of one component,
        // chroma at its subsampled size, and libtiff hands the samples on unconverted.
        val jpegPlanes = jpeg && photometric == 6 && planarTag == 2
        val jpegRgb = jpeg && photometric == 6 && !jpegPlanes
        val pixels = if (jpegRgb) 2 else photometric

        val tiled = entries.containsKey(322)
        val oldJpeg = if (compression == 6) {
            if (bits != 8) throw UnsupportedImageException("TIFF: old-style JPEG with $bits-bit samples is not supported (8-bit only)")
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
                separate = planarTag == 2 && spp > 1,
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
        // An old-style JPEG image comes out of its one frame with every sample of a pixel together.
        val planar = if (oldJpeg != null) 1 else planarTag

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
        if (photometric == 5) {
            // TIFF 6.0 section 16: InkSet 1 is CMYK in that order; any other set of inks
            // has no meaning without a separation of its own.
            val inkSet = optionalValues(332, "InkSet", 3, 1)?.first() ?: 1L
            if (inkSet != 1L) throw UnsupportedImageException("TIFF: InkSet $inkSet (inks other than CMYK) is not supported")
            val inks = optionalValues(334, "NumberOfInks", 3, 1)?.first() ?: 4L
            if (inks != 4L) throw UnsupportedImageException("TIFF: $inks inks are not supported (CMYK only)")
            if (spp < 4) err("CMYK with $spp samples")
        }
        if (photometric == 6) {
            if (subH !in intArrayOf(1, 2, 4) || subV !in intArrayOf(1, 2, 4)) {
                err("YCbCr subsampling ${subH}x$subV is not legal")
            }
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
            rowBytes(width, samplesPerPlane, layoutBits).toInt()
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
            if ((ycbcrUnits || jpegPlanes) && (tileWidth % subH != 0 || tileLength % subV != 0)) {
                err("tile ${tileWidth}x$tileLength does not align with YCbCr subsampling ${subH}x$subV")
            }
            val tileBytes = if (ycbcrUnits) {
                (tileWidth / subH).toLong() * (tileLength / subV) * unitBytes
            } else rowBytes(tileWidth, samplesPerPlane, layoutBits) * tileLength
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
            cancel?.invoke()
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
            if (jpeg) return jpegBlock(index, data.copyOfRange(ofs, ofs + len), jpegTables, rows, columns, samplesPerPlane, jpegRgb, jpeg12, le, cancel)
            val comp = normalizeFillOrder(data.copyOfRange(ofs, ofs + len), fillOrder)
            fun fax(k: Int, byteAligned: Boolean, endOfLine: Boolean = false): ByteArray {
                if (bits != 1 || spp != 1) err("CCITT requires one bilevel sample")
                return ccittStrip(comp, k, columns, rows, byteAligned, endOfLine)
            }
            val decoded = when (compression) {
                5 -> tiffLzw(comp, expect)
                8, 32946 -> try {
                    Zlib.decompress(comp, expect.toLong(), cancel = cancel)
                } catch (e: ArchiveException) {
                    throw ImageDecodeException("TIFF: strip inflate failed: ${e.message}", e)
                }
                32773 -> packBits(comp, expect)
                34925 -> unpack(Codecs.XZ, "LZMA", comp, expect)
                50000 -> unpack(Codecs.ZSTD, "ZSTD", comp, expect)
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
            val stride = samplesPerPlane
            if (predictor == 3) {
                floatingPointPredictor(buffer, rowBytes, rows, stride, bits / 8, le)
                return
            }
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
                    32 -> for (i in stride until rowBytes / 4) {
                        val at = ro + i * 4
                        writeWord(buffer, at, 4, readWord(buffer, at, 4, le) + readWord(buffer, at - stride * 4, 4, le), le)
                    }
                    64 -> for (i in stride until rowBytes / 8) {
                        val at = ro + i * 8
                        writeWord(buffer, at, 8, readWord(buffer, at, 8, le) + readWord(buffer, at - stride * 8, 8, le), le)
                    }
                    else -> err("predictor 2 with $bits-bit samples")
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
                else rowBytes(tileWidth, samplesPerPlane, layoutBits).toInt()
            val tileRows = if (ycbcrUnits) tileLength / subV else tileLength
            val tilesPerPlane = across * down
            for (p in 0 until planes) {
                val plane = ByteArray(planeRowBytes * planeRows)
                if (jpegPlanes && p > 0) {
                    jpegChromaTiles(plane, across, down, tileWidth / subH, tileLength / subV, (width + subH - 1) / subH,
                        (height + subV - 1) / subV) { index, w, h -> block(p * tilesPerPlane + index, w * h, h, w) }
                    blocks.add(plane)
                    continue
                }
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
            if (jpegPlanes && rowsPerStrip < planeRows && rowsPerStrip % subV != 0) {
                err("strips of $rowsPerStrip rows do not hold whole rows of ${subH}x$subV chroma")
            }
            for (p in 0 until planes) {
                val plane = ByteArray(planeRowBytes * planeRows)
                var at = 0
                // A chroma plane in a JPEG strip is at its subsampled size, packed from the start.
                val chroma = jpegPlanes && p > 0
                val columns = if (chroma) (width + subH - 1) / subH else width
                for (s in 0 until stripsPerPlane) {
                    val fullRows = minOf(rowsPerStrip, planeRows - s * rowsPerStrip)
                    if (fullRows <= 0) break
                    val stripRows = if (chroma) (fullRows + subV - 1) / subV else fullRows
                    val expect = if (chroma) columns * stripRows else planeRowBytes * stripRows
                    val strip = block(p * stripsPerPlane + s, expect, stripRows, columns)
                    restorePredictor(strip, planeRowBytes, stripRows)
                    strip.copyInto(plane, at, 0, minOf(expect, strip.size))
                    at += expect
                }
                blocks.add(plane)
            }
        }

        // --- sample access --------------------------------------------------------
        // Samples of any other depth or format become 16-bit unsigned ones here, so what
        // follows reads one of the five layouts it always has. A 12-bit JPEG strip is
        // already 16-bit.
        val widened = !jpeg12 && (format != 1 || bits !in intArrayOf(1, 2, 4, 8, 16))
        val sampleRowBytes = if (widened) width * samplesPerPlane * 2 else planeRowBytes
        if (widened) {
            if (sampleRowBytes.toLong() * planeRows * planes > MAX_BUFFER_BYTES) {
                err("16-bit samples of ${sampleRowBytes.toLong() * planeRows * planes} bytes exceed safety limits")
            }
            for (p in blocks.indices) {
                blocks[p] = widen(blocks[p], planeRowBytes, planeRows, width * samplesPerPlane, bits, format, le, index = photometric == 3)
            }
        }
        val sBits = if (widened || jpeg12) 16 else bits

        /** Sample [c] of pixel ([x], [y]) at its native bit depth. */
        fun sample(x: Int, y: Int, c: Int): Int {
            val plane = if (planar == 2) blocks[c] else blocks[0]
            val index = if (planar == 2) x else x * spp + c
            val row = y * sampleRowBytes
            return when (sBits) {
                8 -> plane[row + index].toInt() and 0xFF
                16 -> read16(plane, row + index * 2, le)
                else -> {
                    val bitPos = index * sBits
                    val byte = plane[row + (bitPos ushr 3)].toInt() and 0xFF
                    (byte ushr (8 - sBits - (bitPos and 7))) and ((1 shl sBits) - 1)
                }
            }
        }

        /** Native-depth sample to 8 bits, by replication for the small depths. */
        fun scale8(v: Int): Int = when (sBits) {
            1 -> v * 255
            2 -> v * 85
            4 -> v * 17
            8 -> v
            else -> v ushr 8                     // 16-bit narrows to its high byte
        }

        val sampleMax = (1 shl sBits) - 1
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
                5 -> for (y in 0 until height) for (x in 0 until width) {
                    val opacity = opacity(x, y)
                    val rgb = if (alpha.associated && opacity == 0) 0 else if (sBits == 8) {
                        // putRGBcontig8bitCMYKtile, truncating as libtiff does.
                        val k = 255 - straight(sample(x, y, 3), opacity)
                        fun channel(c: Int) = k * (255 - straight(sample(x, y, c), opacity)) / 255
                        (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                    } else {
                        // libtiff draws no 16-bit CMYK; this is the high byte of what wide() gives.
                        fun channel(c: Int) = cmyk16(straight(sample(x, y, c), opacity), straight(sample(x, y, 3), opacity)) ushr 8
                        (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                    }
                    argb[y * width + x] = (scale8(opacity) shl 24) or rgb
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
            fun scale16(v: Int): Int = when (sBits) {
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
                5 -> if (sBits == 8) {
                    // Exactly what argb() draws, widened.
                    KiteBitmap16.widened(argb(), alpha = withAlpha)
                } else {
                    val channels = if (withAlpha) 4 else 3
                    val out = ShortArray(width * height * channels)
                    for (y in 0 until height) for (x in 0 until width) {
                        val opacity = opacity(x, y)
                        val at = (y * width + x) * channels
                        if (!(alpha.associated && opacity == 0)) {
                            val k = straight(sample(x, y, 3), opacity)
                            for (c in 0 until 3) out[at + c] = cmyk16(straight(sample(x, y, c), opacity), k).toShort()
                        }
                        if (withAlpha) out[at + 3] = opacity.toShort()
                    }
                    KiteBitmap16(width, height, channels, out)
                }
                // YCbCr is 8-bit here, so its 8-bit conversion widens without loss.
                else -> KiteBitmap16.widened(argb(), alpha = false)
            }
        }

        override fun ink(wide: Boolean): Ink? {
            if (pixels != 5) return null
            val n = width * height
            val withAlpha = alpha.sample >= 0
            val ink8 = if (wide) null else ByteArray(n * 4)
            val ink16 = if (wide) ShortArray(n * 4) else null
            val alpha8 = if (withAlpha && !wide) ByteArray(n) else null
            val alpha16 = if (withAlpha && wide) ShortArray(n) else null
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                val opacity = opacity(x, y)
                for (c in 0 until 4) {
                    // Associated alpha over nothing leaves no ink to read.
                    val v = if (alpha.associated && opacity == 0) 0 else straight(sample(x, y, c), opacity)
                    if (ink8 != null) ink8[4 * i + c] = scale8(v).toByte()
                    else ink16!![4 * i + c] = (if (sBits == 8) v * 257 else v).toShort()
                }
                if (alpha8 != null) alpha8[i] = scale8(opacity).toByte()
                if (alpha16 != null) alpha16[i] = (if (sBits == 8) opacity * 257 else opacity).toShort()
            }
            return Ink(width, height, ink8, ink16, alpha8, alpha16)
        }
        }
    }

    /** A 16-bit CMYK colorant [c] under black [k] as RGB, the 16-bit form of libtiff's formula, rounded. */
    private fun cmyk16(c: Int, k: Int): Int = (((65535L - k) * (65535 - c) + 32767) / 65535).toInt()

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
        wide: Boolean,
        le: Boolean,
        cancel: (() -> Unit)?,
    ): ByteArray {
        val stream = if (tables == null) block else {
            val head = if (tables.size >= 4 && marker(tables, tables.size - 2) == 0xD9) tables.size - 2 else tables.size
            val skip = if (block.size >= 2 && marker(block, 0) == 0xD8) 2 else 0
            tables.copyOf(head) + block.copyOfRange(skip, block.size)
        }
        if (wide) return jpegBlock12(index, stream, rows, columns, samples, ycbcr, le, cancel)
        val decoded = try {
            JpegDecoder.decodeComponents(stream, cancel = cancel)
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
     * [jpegBlock] for a page of 12-bit samples: the stream must be 12-bit too, and each sample,
     * converted to RGB with [ycbcr] as libjpeg converts at 12 bits, comes out as a 16-bit one in the
     * file's byte order, scaled as [widen] scales a sample of 12 bits.
     */
    private fun jpegBlock12(
        index: Int, stream: ByteArray, rows: Int, columns: Int, samples: Int, ycbcr: Boolean, le: Boolean, cancel: (() -> Unit)?,
    ): ByteArray {
        val c = try {
            JpegDecoder.decodeWideComponents(stream, ycbcr, cancel)
        } catch (e: UnsupportedImageException) {
            throw UnsupportedImageException("TIFF: JPEG block $index: ${e.message}", e)
        } catch (e: ImageDecodeException) {
            throw ImageDecodeException("TIFF: JPEG block $index: ${e.message}", e)
        }
        if (c.precision != 12) err("JPEG block $index has ${c.precision}-bit samples on a 12-bit page")
        if (c.width != columns || c.height < rows) {
            err("JPEG block $index codes ${c.width}x${c.height} pixels for a ${columns}x$rows block")
        }
        if (c.count != samples) err("JPEG block $index has ${c.count} components for $samples samples")
        val out = ByteArray(columns * rows * samples * 2)
        for (i in 0 until columns * rows * samples) write16(out, i * 2, scale(c.samples[i].toLong(), 12), le)
        return out
    }

    /**
     * Fills [plane], the subsampled chroma plane of a JPEG page in separate planes and tiles,
     * [across] by [down] tiles of [tileWidth] by [tileLength] chroma samples, packed in rows of
     * [width] samples for [height] rows. [tile] decodes one tile by its index in the plane.
     */
    private fun jpegChromaTiles(
        plane: ByteArray, across: Int, down: Int, tileWidth: Int, tileLength: Int, width: Int, height: Int,
        tile: (index: Int, width: Int, height: Int) -> ByteArray,
    ) {
        for (ty in 0 until down) for (tx in 0 until across) {
            val samples = tile(ty * across + tx, tileWidth, tileLength)
            val columns = minOf(tileWidth, width - tx * tileWidth)
            for (row in 0 until minOf(tileLength, height - ty * tileLength)) {
                samples.copyInto(plane, (ty * tileLength + row) * width + tx * tileWidth, row * tileWidth, row * tileWidth + columns)
            }
        }
    }

    /**
     * Adobe's floating-point predictor (Photoshop TIFF Technical Note 3), undone in place on [rows]
     * rows of [rowBytes] bytes, [stride] samples a pixel of [bytes] bytes each. The encoder splits
     * each row into planes of bytes, the most significant byte of every sample first, and then
     * differences the bytes with a step of [stride]; the samples come back in the file's byte order.
     */
    private fun floatingPointPredictor(buffer: ByteArray, rowBytes: Int, rows: Int, stride: Int, bytes: Int, le: Boolean) {
        val count = rowBytes / bytes
        val tmp = ByteArray(count * bytes)
        for (y in 0 until rows) {
            val ro = y * rowBytes
            for (i in stride until count * bytes) buffer[ro + i] = (buffer[ro + i] + buffer[ro + i - stride]).toByte()
            buffer.copyInto(tmp, 0, ro, ro + count * bytes)
            for (i in 0 until count) for (b in 0 until bytes) {
                buffer[ro + i * bytes + (if (le) bytes - 1 - b else b)] = tmp[b * count + i]
            }
        }
    }

    /**
     * A plane of [rows] rows of [rowBytes] bytes, each row [count] samples of [bits] bits in
     * SampleFormat [format], as 16-bit unsigned samples in the file's byte order. Samples of a
     * multiple of 8 bits are in the file's byte order, and other depths are packed from the most
     * significant bit, as libtiff reads them.
     *
     * - An unsigned sample scales to 16 bits rounded, as ImageMagick scales one, which for 1, 2, 4
     *   and 8 bits is the replication [decode] has always used. A palette [index] keeps its value.
     * - A signed sample moves up by half its range first, so the most negative value is black and
     *   zero the middle, as ImageMagick reads signed gray and this library a signed JPEG 2000 sample.
     * - A floating-point sample is a fraction of full scale, as ImageMagick and ImageIO read it:
     *   0 or below, and NaN, give 0, and 1 or above give 65535.
     */
    private fun widen(plane: ByteArray, rowBytes: Int, rows: Int, count: Int, bits: Int, format: Int, le: Boolean, index: Boolean): ByteArray {
        val out = ByteArray(rows * count * 2)
        val whole = bits % 8 == 0
        val size = bits / 8
        val mask = if (bits == 64) -1L else (1L shl bits) - 1
        for (y in 0 until rows) {
            val row = y * rowBytes
            for (i in 0 until count) {
                val raw = if (whole) {
                    readWord(plane, row + i * size, size, le)
                } else {
                    val bit = i.toLong() * bits
                    val first = row + (bit ushr 3).toInt()
                    val skip = (bit and 7).toInt()
                    val span = (skip + bits + 7) / 8
                    var word = 0L
                    for (k in 0 until span) word = (word shl 8) or (plane[first + k].toLong() and 0xFF)
                    (word ushr (span * 8 - skip - bits)) and mask
                }
                val v = when {
                    format == 3 -> fraction16(floatValue(raw, bits))
                    index -> raw.toInt()
                    format == 2 -> scale(raw xor (1L shl (bits - 1)), bits)
                    else -> scale(raw, bits)
                }
                write16(out, (y * count + i) * 2, v, le)
            }
        }
        return out
    }

    /** An unsigned sample of [bits] bits, 1 to 32, at 16 bits: v * 65535 / (2^bits - 1), rounded. */
    private fun scale(v: Long, bits: Int): Int {
        if (bits == 16) return v.toInt()
        val max = (1L shl bits) - 1
        return ((v * 65535 + max / 2) / max).toInt()
    }

    /** A fraction of full scale at 16 bits, rounded, with NaN and the values outside 0 to 1 clipped. */
    private fun fraction16(f: Double): Int = if (!(f > 0)) 0 else if (f >= 1) 65535 else (f * 65535 + 0.5).toInt()

    /**
     * The value of an IEEE 754 sample of [bits] bits: binary16, binary32, binary64, or the 24-bit
     * float of Adobe's DNG specification, which has a sign, a 7-bit exponent biased by 63 and a 16-bit
     * fraction. The shorter ones are widened exactly, so every target reads the same value.
     */
    private fun floatValue(raw: Long, bits: Int): Double = when (bits) {
        64 -> Double.fromBits(raw)
        32 -> Float.fromBits(raw.toInt()).toDouble()
        else -> {
            val fractionBits = if (bits == 16) 10 else 16
            val exponentBits = bits - 1 - fractionBits
            val bias = (1 shl (exponentBits - 1)) - 1
            val e = ((raw ushr fractionBits) and ((1L shl exponentBits) - 1)).toInt()
            val m = raw and ((1L shl fractionBits) - 1)
            val sign = if ((raw ushr (bits - 1)) and 1L != 0L) -1.0 else 1.0
            when (e) {
                0 -> sign * m * twoTo(1 - bias - fractionBits)
                (1 shl exponentBits) - 1 -> if (m == 0L) sign * Double.POSITIVE_INFINITY else Double.NaN
                else -> sign * ((1L shl fractionBits) + m) * twoTo(e - bias - fractionBits)
            }
        }
    }

    /** 2 to the power [e], exactly, for an exponent a binary64 holds as a normal number. */
    private fun twoTo(e: Int): Double = Double.fromBits((1023L + e) shl 52)

    /** A word of [size] bytes, 1 to 8, at [at] in the byte order [le] says. */
    private fun readWord(d: ByteArray, at: Int, size: Int, le: Boolean): Long {
        var v = 0L
        for (k in 0 until size) v = (v shl 8) or (d[at + if (le) size - 1 - k else k].toLong() and 0xFF)
        return v
    }

    private fun writeWord(d: ByteArray, at: Int, size: Int, v: Long, le: Boolean) {
        for (k in 0 until size) d[at + if (le) k else size - 1 - k] = (v ushr (8 * k)).toByte()
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

    /** TIFF LZW (MSB-first codes with early change), decoded by ArchiveKodec. */
    private fun tiffLzw(input: ByteArray, expected: Int): ByteArray = try {
        Lzw.decode(input, LzwOptions { expectedSize = expected.toLong() }).output
    } catch (e: ArchiveException) {
        throw ImageDecodeException("TIFF: ${e.message}", e)
    }

    /**
     * A strip or tile compressed with [codec], decoded by ArchiveKodec. The block geometry gives the
     * exact output size, so that size is the limit, and the ratio check is off: a blank strip of
     * zstd or xz can pass 10,000:1.
     */
    private fun unpack(codec: Codec, name: String, input: ByteArray, expected: Int): ByteArray = try {
        codec.decompress(input, ArchiveLimits {
            maxUncompressedBytes = maxOf(expected, 1).toLong()
            maxCompressionRatio = Int.MAX_VALUE
        })
    } catch (e: ArchiveException) {
        throw ImageDecodeException("TIFF: $name strip failed: ${e.message}", e)
    }

    /** PackBits (Apple RLE), decoded by ArchiveKodec. */
    private fun packBits(input: ByteArray, expected: Int): ByteArray = try {
        PackBits.decode(input, PackBitsOptions { expectedSize = expected.toLong() }).output
    } catch (e: ArchiveException) {
        throw ImageDecodeException("TIFF: ${e.message}", e)
    }
}
