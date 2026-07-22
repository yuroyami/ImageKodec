package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.UnsupportedImageException
import io.github.yuroyami.kiteimage.internal.flate.Zlib

/**
 * Baseline TIFF decoder (commons-imaging as the semantic reference). Scope —
 * the strip-based baseline that covers the files people actually have:
 *
 *  - both byte orders (II/MM), first IFD only (multi-page: first page)
 *  - compressions: none (1), CCITT G3-1D (2, byte-aligned rows), G3 via
 *    T4Options bit0=0 (3), G4 (4) — the absorbed [CcittFax] codec — TIFF-LZW
 *    with EarlyChange (5), Deflate (8 / 32946), PackBits (32773)
 *  - photometric 0/1 (bilevel + gray, either polarity), 2 (RGB, optional
 *    alpha via ExtraSamples — treated as straight), 3 (palette, 16-bit
 *    ColorMap entries)
 *  - bits per sample 1 and 8, horizontal-differencing predictor (2),
 *    chunky planar configuration only
 *
 * Tiled TIFFs, 16-bit samples, planar configuration 2 and YCbCr photometric
 * are rejected with named errors.
 */
internal object TiffDecoder {

    private const val MAX_DIMENSION = 1 shl 24
    private const val MAX_PIXELS = 1L shl 28

    private fun err(msg: String): Nothing = throw ImageDecodeException("TIFF: $msg")

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

        fun single(tag: Int, default: Long? = null): Long =
            entries[tag]?.let { values(it)[0] } ?: default ?: err("missing required tag $tag")

        val width = single(256).toInt()
        val height = single(257).toInt()
        if (width <= 0 || height <= 0) err("bad dimensions ${width}x$height")
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
            err("${width}x$height exceeds safety limits")
        }

        val compression = single(259, 1).toInt()
        val photometric = single(262).toInt()
        val spp = single(277, 1).toInt()
        val predictor = single(317, 1).toInt()
        val planar = single(284, 1).toInt()
        if (planar != 1) throw UnsupportedImageException("TIFF: planar configuration $planar (planes) is not supported")
        if (entries.containsKey(322)) throw UnsupportedImageException("TIFF: tiled files are not supported (strips only)")
        if (photometric == 6) throw UnsupportedImageException("TIFF: YCbCr photometric is not supported")

        val bitsEntry = entries[258]?.let { values(it) } ?: longArrayOf(1)
        val bits = bitsEntry[0].toInt()
        if (bitsEntry.any { it != bits.toLong() }) err("heterogeneous bits per sample")
        if (bits != 1 && bits != 8) {
            throw UnsupportedImageException("TIFF: $bits bits per sample is not supported (1 or 8)")
        }
        if (bits == 1 && spp != 1) err("1-bit with $spp samples")

        val rowsPerStrip = single(278, 0xFFFFFFFFL).toInt().coerceAtMost(height)
        val stripOffsets = entries[273]?.let { values(it) } ?: err("missing strip offsets")
        val stripCounts = entries[279]?.let { values(it) }
            ?: if (compression == 1) LongArray(stripOffsets.size) { Long.MAX_VALUE } else err("missing strip byte counts")
        if (stripOffsets.size != stripCounts.size) err("strip offset/count mismatch")

        val rowBytes = (width * spp * bits + 7) / 8

        // --- decompress strips into one raw sample buffer -----------------------
        val raw = ByteArray(rowBytes * height)
        var rawAt = 0
        for (s in stripOffsets.indices) {
            val stripRows = minOf(rowsPerStrip, height - s * rowsPerStrip)
            if (stripRows <= 0) break
            val expect = rowBytes * stripRows
            val ofs = stripOffsets[s].toInt()
            val len = minOf(stripCounts[s], (data.size - ofs).toLong()).toInt()
            if (ofs < 0 || ofs > data.size) err("strip $s offset out of range")
            val comp = data.copyOfRange(ofs, ofs + len)
            val strip = when (compression) {
                1 -> comp.copyOf(expect)
                5 -> tiffLzw(comp, expect)
                8, 32946 -> Zlib.decompress(comp, expect.toLong()).copyOf(expect)
                32773 -> packBits(comp, expect)
                2 -> ccittStrip(comp, k = 0, width, stripRows, byteAligned = true)
                3 -> {
                    val t4 = entries[292]?.let { values(it)[0] } ?: 0L
                    if (t4 and 1L != 0L) throw UnsupportedImageException("TIFF: G3 2D (T4Options bit 0) is not supported")
                    ccittStrip(comp, k = 0, width, stripRows, byteAligned = (t4 and 4L) != 0L)
                }
                4 -> ccittStrip(comp, k = -1, width, stripRows, byteAligned = false)
                else -> throw UnsupportedImageException("TIFF: compression $compression is not supported")
            }
            strip.copyInto(raw, rawAt, 0, minOf(expect, strip.size))
            rawAt += expect
        }

        // --- predictor ----------------------------------------------------------
        if (predictor == 2) {
            if (bits != 8) err("predictor 2 with $bits-bit samples")
            for (y in 0 until height) {
                val ro = y * rowBytes
                for (i in spp until rowBytes) {
                    raw[ro + i] = (raw[ro + i] + raw[ro + i - spp]).toByte()
                }
            }
        } else if (predictor != 1) {
            throw UnsupportedImageException("TIFF: predictor $predictor is not supported")
        }

        // --- to ARGB ------------------------------------------------------------
        val argb = IntArray(width * height)
        when (photometric) {
            0, 1 -> {
                val invert = photometric == 0
                for (y in 0 until height) {
                    val ro = y * rowBytes
                    for (x in 0 until width) {
                        var g = if (bits == 1) {
                            val bit = (raw[ro + (x ushr 3)].toInt() ushr (7 - (x and 7))) and 1
                            bit * 255
                        } else {
                            raw[ro + x * spp].toInt() and 0xFF
                        }
                        if (bits == 1 && !invert) g = g          // BlackIsZero: 1 = white
                        if (invert) g = 255 - g                  // WhiteIsZero flips
                        argb[y * width + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                    }
                }
            }
            2 -> {
                if (spp < 3) err("RGB with $spp samples")
                val hasAlpha = spp >= 4
                for (y in 0 until height) {
                    val ro = y * rowBytes
                    for (x in 0 until width) {
                        val p = ro + x * spp
                        val a = if (hasAlpha) raw[p + 3].toInt() and 0xFF else 0xFF
                        argb[y * width + x] = (a shl 24) or
                            ((raw[p].toInt() and 0xFF) shl 16) or
                            ((raw[p + 1].toInt() and 0xFF) shl 8) or
                            (raw[p + 2].toInt() and 0xFF)
                    }
                }
            }
            3 -> {
                val mapEntry = entries[320] ?: err("palette image without ColorMap")
                val map = values(mapEntry)
                val n = 1 shl bits
                if (map.size < 3 * n) err("ColorMap too small")
                for (y in 0 until height) {
                    val ro = y * rowBytes
                    for (x in 0 until width) {
                        val idx = if (bits == 1) {
                            (raw[ro + (x ushr 3)].toInt() ushr (7 - (x and 7))) and 1
                        } else {
                            raw[ro + x].toInt() and 0xFF
                        }
                        // ColorMap entries are 16-bit; take the high byte.
                        val rr = (map[idx] shr 8).toInt() and 0xFF
                        val gg = (map[n + idx] shr 8).toInt() and 0xFF
                        val bb = (map[2 * n + idx] shr 8).toInt() and 0xFF
                        argb[y * width + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            }
            else -> throw UnsupportedImageException("TIFF: photometric $photometric is not supported")
        }

        return KiteBitmap(width, height, argb)
    }

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
        return out
    }
}
