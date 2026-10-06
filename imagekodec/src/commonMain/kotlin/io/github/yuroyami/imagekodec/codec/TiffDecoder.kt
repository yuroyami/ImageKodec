package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.internal.flate.InflateException
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * Baseline TIFF decoder (commons-imaging as the semantic reference). Scope:
 * the strip-based baseline that covers the files people actually have:
 *
 *  - both byte orders (II/MM), first IFD only (multi-page: first page)
 *  - **strips and tiles**: tiled files assemble edge-padded tiles back into full
 *    rows after restoring any per-tile horizontal predictor
 *  - compressions: none (1), CCITT G3-1D (2, byte-aligned rows), G3 via
 *    T4Options bit0=0 (3), G4 (4), the absorbed [CcittFax] codec, TIFF-LZW
 *    with EarlyChange (5), Deflate (8 / 32946), PackBits (32773)
 *  - photometric 0/1 (bilevel + gray, either polarity, optional alpha), 2 (RGB,
 *    optional alpha via ExtraSamples: treated as straight), 3 (palette, 16-bit
 *    ColorMap entries), 6 (**YCbCr**, including chroma subsampling in both the
 *    chunky unit layout and separate planes)
 *  - bits per sample 1, 2, 4, 8 and 16 (16-bit narrows to its high byte, as
 *    everywhere else in ImageKodec), horizontal-differencing predictor (2) for
 *    both 8- and 16-bit samples
 *  - **both planar configurations**: chunky (1) and separate planes (2)
 *
 * What is left out is named at the point of failure: JPEG-in-TIFF (compression 6
 * and 7) is a container trick rather than a TIFF encoding, and floating-point
 * samples have no home in an 8-bit-per-channel bitmap.
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

    fun decode(data: ByteArray): KiteBitmap {
        if (data.size < 8) err("too short")
        val le = data[0].toInt() == 'I'.code && data[1].toInt() == 'I'.code
        val be = data[0].toInt() == 'M'.code && data[1].toInt() == 'M'.code
        if (!le && !be) err("bad byte-order mark")
        val r = Reader(data, le)
        if (r.u16(2) != 42) err("bad magic")

        val ifdOfs = r.u32(4)
        if (ifdOfs < 8 || ifdOfs >= data.size) err("bad IFD offset $ifdOfs")

        // --- IFD walk -----------------------------------------------------------
        val entries = HashMap<Int, Entry>()
        val count = r.u16(ifdOfs.toInt())
        for (i in 0 until count) {
            val at = ifdOfs.toInt() + 2 + i * 12
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
        if (!Budget.fits(width, height, data.size)) {
            err("${width}x$height cannot come from ${data.size} bytes")
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

        val bitsEntry = entries[258]?.let { values(it) }?.takeIf { it.isNotEmpty() } ?: longArrayOf(1)
        val bits = bitsEntry[0].toInt()
        if (bitsEntry.any { it != bits.toLong() }) err("heterogeneous bits per sample")
        if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) {
            throw UnsupportedImageException("TIFF: $bits bits per sample is not supported (1, 2, 4, 8 or 16)")
        }
        if (bits == 1 && spp != 1 && photometric != 6) err("1-bit with $spp samples")

        // Chroma subsampling only exists for YCbCr; everything else is 1:1.
        val subSampling = if (photometric == 6) {
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
        val tiled = entries.containsKey(322)
        val planes = if (planar == 2) spp else 1
        val samplesPerPlane = if (planar == 2) 1 else spp

        // A subsampled YCbCr row group stores h×v luma plus one of each chroma per
        // unit, so its "row" is a group of `subV` image rows.
        val ycbcrUnits = photometric == 6 && planar == 1 && (subH > 1 || subV > 1)
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
            ?: if (compression == 1) LongArray(offsets.size) { Long.MAX_VALUE } else err("missing block byte counts")
        if (offsets.size != counts.size) err("block offset/count mismatch")

        /** Decompress block [index], which is expected to hold [expect] bytes. */
        fun block(index: Int, expect: Int, rows: Int, columns: Int): ByteArray {
            if (index >= offsets.size) err("block $index beyond the ${offsets.size} declared")
            val ofs = offsets[index].toInt()
            if (ofs < 0 || ofs > data.size) err("block $index offset out of range")
            if (compression == 1) {
                // TIFF's uncompressed size is determined by geometry. Like libtiff,
                // recover a bogus byte count only when all those bytes are present.
                if (expect > data.size - ofs) err("block $index ends early: need $expect bytes, have ${data.size - ofs}")
                return data.copyOfRange(ofs, ofs + expect)
            }
            val len = minOf(counts[index], (data.size - ofs).toLong()).toInt()
            val comp = data.copyOfRange(ofs, ofs + len)
            return when (compression) {
                5 -> tiffLzw(comp, expect)
                8, 32946 -> try {
                    val decoded = Zlib.decompress(comp, expect.toLong())
                    if (decoded.size != expect) err("block $index inflate ended after ${decoded.size} of $expect bytes")
                    decoded
                } catch (e: InflateException) {
                    throw ImageDecodeException("TIFF: strip inflate failed: ${e.message}", e)
                }
                32773 -> packBits(comp, expect)
                2 -> ccittStrip(comp, k = 0, columns, rows, byteAligned = true)
                3 -> {
                    if (t4Options and 1L != 0L) throw UnsupportedImageException("TIFF: G3 2D (T4Options bit 0) is not supported")
                    ccittStrip(comp, k = 0, columns, rows, byteAligned = (t4Options and 4L) != 0L)
                }
                4 -> ccittStrip(comp, k = -1, columns, rows, byteAligned = false)
                else -> throw UnsupportedImageException("TIFF: compression $compression is not supported")
            }
        }

        // TIFF 6.0 sections 14/15: prediction starts anew at each tile row,
        // including padding, before that row is joined to its neighbours.
        fun restorePredictor(buffer: ByteArray, rowBytes: Int, rows: Int) {
            if (predictor == 1) return
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

        if (tiled) {
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
            // Clamp before narrowing: the default 2^32 - 1 is -1 as an Int.
            val rowsPerStrip = single(278, 0xFFFFFFFFL).coerceAtMost(planeRows.toLong()).toInt()
            if (rowsPerStrip <= 0) err("rows per strip is zero")
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

        // --- to ARGB ------------------------------------------------------------
        val argb = IntArray(width * height)
        when (photometric) {
            0, 1 -> {
                val invert = photometric == 0    // WhiteIsZero
                for (y in 0 until height) for (x in 0 until width) {
                    var g = scale8(sample(x, y, 0))
                    if (invert) g = 255 - g
                    val a = if (spp >= 2) scale8(sample(x, y, 1)) else 0xFF
                    argb[y * width + x] = (a shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
            2 -> {
                if (spp < 3) err("RGB with $spp samples")
                val hasAlpha = spp >= 4
                for (y in 0 until height) for (x in 0 until width) {
                    val a = if (hasAlpha) scale8(sample(x, y, 3)) else 0xFF
                    argb[y * width + x] = (a shl 24) or
                        (scale8(sample(x, y, 0)) shl 16) or
                        (scale8(sample(x, y, 1)) shl 8) or
                        scale8(sample(x, y, 2))
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
                    val rr = (map[idx] shr 8).toInt() and 0xFF
                    val gg = (map[n + idx] shr 8).toInt() and 0xFF
                    val bb = (map[2 * n + idx] shr 8).toInt() and 0xFF
                    argb[y * width + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            6 -> {
                val refs = entries[532]?.let { values(it) }
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
                    argb[y * width + x] = ycbcrToArgb(yy, cb, cr, refs)
                }
            }
            else -> throw UnsupportedImageException("TIFF: photometric $photometric is not supported")
        }

        return KiteBitmap(width, height, argb)
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
     * YCbCr → RGB with the CCIR 601-1 coefficients TIFF specifies, in fixed point
     * so every target agrees. [refs] is the ReferenceBlackWhite tag: six values
     * giving the black and white points of each channel. Its default (0/255 for
     * luma, 128/255 for chroma) is the full-range case, which is what almost every
     * writer emits, so that path stays a plain integer transform.
     */
    private fun ycbcrToArgb(y: Int, cb: Int, cr: Int, refs: LongArray?): Int {
        var luma = y
        var chromaB = cb - 128
        var chromaR = cr - 128
        if (refs != null && refs.size >= 6) {
            val yBlack = refs[0].toInt(); val yWhite = refs[1].toInt()
            val cbBlack = refs[2].toInt(); val cbWhite = refs[3].toInt()
            val crBlack = refs[4].toInt(); val crWhite = refs[5].toInt()
            if (yWhite != yBlack) luma = (y - yBlack) * 255 / (yWhite - yBlack)
            if (cbWhite != cbBlack) chromaB = (cb - cbBlack) * 255 / (cbWhite - cbBlack) - 128
            if (crWhite != crBlack) chromaR = (cr - crBlack) * 255 / (crWhite - crBlack) - 128
        }
        val fixed = (luma shl 16) + (1 shl 15)
        val r = (fixed + 91881 * chromaR) shr 16
        val g = (fixed - 22554 * chromaB - 46802 * chromaR) shr 16
        val b = (fixed + 116130 * chromaB) shr 16
        return (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
    }

    private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /** CCITT strip → packed 1-bpp rows (TIFF polarity: 0 = white ⇒ blackIs1=false). */
    private fun ccittStrip(comp: ByteArray, k: Int, width: Int, rows: Int, byteAligned: Boolean): ByteArray {
        val opts = CcittOptions(
            columns = width, rows = rows, endOfBlock = false,
            blackIs1 = false, encodedByteAlign = byteAligned, endOfLine = false,
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
